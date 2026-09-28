package com.verificlean.app.data.local

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cifra las fotos de evidencia mientras esperan a subirse: AES-256-GCM con una clave que
 * vive en el Android Keystore.
 *
 * ## Por qué no `androidx.security:security-crypto`
 *
 * `EncryptedFile` y `MasterKey` están deprecados sin reemplazo oficial — Google
 * recomienda usar el Keystore directamente. Además arrastraban Tink entero para cifrar
 * archivos de pocos MB. Esto son ~100 líneas contra la API de plataforma, sin
 * dependencias, y con la lógica testeable en JVM.
 *
 * ## Por qué cifrar algo que ya está en almacenamiento interno
 *
 * El almacenamiento interno protege frente a *otras apps*, no frente a alguien con el
 * teléfono en la mano y root o un backup por ADB. Y lo que hay en esta carpeta es
 * exactamente lo que un supervisor deshonesto querría manipular: la foto que todavía no
 * ha llegado al servidor, antes de que exista un hash de referencia con el que
 * contrastarla.
 *
 * ## Decisiones criptográficas
 *
 * - **AES-256-GCM, no CBC.** GCM es AEAD: detecta manipulación. Con CBC un atacante
 *   altera el ciphertext y el descifrado devuelve basura sin avisar.
 * - **IV generado por la plataforma**, nunca por nosotros. Repetir un IV con GCM rompe la
 *   confidencialidad por completo; dejar que lo genere el proveedor elimina la
 *   posibilidad de equivocarse. Viaja en la cabecera del archivo, que es dato público.
 * - **`clienteEvidenciaId` como AAD.** Liga cada ciphertext a su fila de la cola. En un
 *   dispositivo rooteado, intercambiar dos archivos `.enc` entre evidencias hace fallar el
 *   descifrado en vez de subir la foto de otra visita como si fuera de esta.
 *
 * ## Formato del archivo
 *
 * ```
 * [1 byte versión][1 byte longitud IV][IV][ciphertext ‖ tag GCM de 16 bytes]
 * ```
 */
@Singleton
class AlmacenEvidenciaCifrada @Inject constructor(
    @CarpetaEvidencias private val carpeta: File,
    private val proveedorClave: ProveedorClaveEvidencia,
) {

    /**
     * Escribe el JPEG cifrado y devuelve la ruta absoluta.
     *
     * Escribe en un temporal y renombra al final: si el proceso muere a mitad, queda un
     * `.tmp` que la limpieza barre, en vez de un `.enc` truncado que parecería válido
     * hasta que el worker intentara descifrarlo horas después.
     */
    fun guardar(clienteEvidenciaId: String, jpeg: ByteArray): String {
        carpeta.mkdirs()
        val destino = File(carpeta, "$clienteEvidenciaId$EXTENSION")
        val temporal = File(carpeta, "$clienteEvidenciaId$EXTENSION$SUFIJO_TEMPORAL")

        val cifrador = Cipher.getInstance(TRANSFORMACION).apply {
            init(Cipher.ENCRYPT_MODE, proveedorClave.clave())
            updateAAD(clienteEvidenciaId.toByteArray(Charsets.UTF_8))
        }
        val iv = cifrador.iv
        require(iv.size in 1..255) { "IV de tamaño inesperado: ${iv.size}" }

        try {
            FileOutputStream(temporal).use { salida ->
                salida.write(VERSION_FORMATO)
                salida.write(iv.size)
                salida.write(iv)
                salida.write(cifrador.doFinal(jpeg))
                salida.flush()
                // `close()` sólo entrega los bytes al caché del sistema operativo, no al
                // disco. Sin este `sync`, un corte de energía entre la escritura y el
                // rename puede dejar un `.enc` de longitud cero que **parece** válido: la
                // fila en Room lo referencia, el worker lo lee, falla el descifrado y la
                // evidencia se marca fallida. Dos líneas contra perder una foto real.
                salida.fd.sync()
            }
            if (destino.exists() && !destino.delete()) {
                throw IOException("No se pudo reemplazar $destino")
            }
            if (!temporal.renameTo(destino)) {
                throw IOException("No se pudo renombrar $temporal a $destino")
            }
        } catch (e: Exception) {
            temporal.delete()
            throw e
        }

        return destino.absolutePath
    }

    /**
     * Devuelve el JPEG en claro, o `null` si no se puede recuperar.
     *
     * `clienteEvidenciaId` se exige porque es el AAD. La firma lo pide en vez de deducirlo
     * del nombre del archivo a propósito: así el dato viene de la fila de la base de
     * datos, y renombrar archivos en disco no cuela.
     */
    fun leer(clienteEvidenciaId: String, rutaAbsoluta: String): ByteArray? {
        val archivo = File(rutaAbsoluta)
        if (!archivo.exists()) {
            registrar("El archivo cifrado ya no existe: $rutaAbsoluta", null)
            return null
        }

        return try {
            descifrar(clienteEvidenciaId, archivo.readBytes())
        } catch (e: AEADBadTagException) {
            // GCM detectó que los bytes no son los que se cifraron. Aquí eso no es un
            // fallo cualquiera: significa que alguien tocó el archivo en disco, o que se
            // intentó descifrarlo con un id distinto al suyo.
            registrar("Evidencia $clienteEvidenciaId manipulada o desemparejada; se descarta", e)
            null
        } catch (e: KeyPermanentlyInvalidatedException) {
            // La clave se declara sin exigir autenticación, así que esto no debería pasar.
            // Si pasa, lo ya cifrado es irrecuperable: se registra y el worker marca la
            // evidencia como fallida, en vez de reintentar eternamente algo imposible.
            registrar("Clave del Keystore invalidada; evidencias cifradas irrecuperables", e)
            null
        } catch (e: GeneralSecurityException) {
            registrar("No se pudo descifrar $rutaAbsoluta", e)
            null
        } catch (e: IOException) {
            registrar("No se pudo leer $rutaAbsoluta", e)
            null
        }
    }

    /** Separado de `leer` para poder probar el formato sin tocar el sistema de archivos. */
    internal fun descifrar(clienteEvidenciaId: String, bytes: ByteArray): ByteArray {
        if (bytes.size < CABECERA_MINIMA) throw IOException("Archivo cifrado truncado")

        val version = bytes[0].toInt() and 0xFF
        if (version != VERSION_FORMATO) throw IOException("Versión de formato desconocida: $version")

        val tamanoIv = bytes[1].toInt() and 0xFF
        if (tamanoIv == 0 || bytes.size <= 2 + tamanoIv) throw IOException("Archivo cifrado truncado")

        val iv = bytes.copyOfRange(2, 2 + tamanoIv)
        val cifrado = bytes.copyOfRange(2 + tamanoIv, bytes.size)

        return Cipher.getInstance(TRANSFORMACION).run {
            init(Cipher.DECRYPT_MODE, proveedorClave.clave(), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(clienteEvidenciaId.toByteArray(Charsets.UTF_8))
            doFinal(cifrado)
        }
    }

    /** Borra el archivo tras confirmarse la subida. Idempotente. */
    fun borrar(rutaAbsoluta: String): Boolean = File(rutaAbsoluta).delete()

    /**
     * Elimina archivos cifrados sin fila en la cola, y los temporales que dejó un proceso
     * muerto a mitad de escritura.
     *
     * Los huérfanos aparecen cuando el proceso muere entre `guardar` y el insert en Room.
     * Sin esta limpieza se acumulan indefinidamente, y son megas por foto.
     */
    fun limpiarHuerfanos(rutasVigentes: Set<String>): Int {
        val archivos = carpeta.listFiles() ?: return 0
        return archivos.count { archivo ->
            val esTemporal = archivo.name.endsWith(SUFIJO_TEMPORAL)
            (esTemporal || archivo.absolutePath !in rutasVigentes) && archivo.delete()
        }
    }

    /**
     * `android.util.Log` no existe fuera de un dispositivo y lanza en tests de JVM.
     * Envolverlo mantiene esta clase probable sin Robolectric.
     */
    private fun registrar(mensaje: String, error: Throwable?) {
        runCatching { Log.e(TAG, mensaje, error) }
    }

    internal companion object {
        const val TAG = "AlmacenEvidencia"
        const val EXTENSION = ".jpg.enc"
        const val SUFIJO_TEMPORAL = ".tmp"
        const val TRANSFORMACION = "AES/GCM/NoPadding"

        /** Etiqueta de autenticación GCM de 128 bits: el máximo y el único que conviene. */
        const val TAG_BITS = 128

        /** Si el formato cambia, se sube y `descifrar` sabe distinguir versiones. */
        const val VERSION_FORMATO = 1

        /** versión + longitud de IV + al menos un byte de IV + el tag de 16 bytes. */
        const val CABECERA_MINIMA = 2 + 1 + 16
    }
}
