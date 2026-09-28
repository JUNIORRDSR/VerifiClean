package com.verificlean.app.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Origen de la clave con la que se cifran las evidencias.
 *
 * Existe como interfaz por una razón concreta y no por gusto de abstraer: el Keystore de
 * Android **no existe en la JVM**, así que sin este corte toda la lógica de cifrado
 * —formato del archivo, manejo del IV, AAD, detección de manipulación— sólo se podría
 * probar con un emulador arrancado. Con el corte, esa lógica se prueba en JVM con una
 * clave AES de software, y lo único que queda sin cubrir es la declaración de la clave,
 * que son quince líneas de configuración sin ramas.
 */
interface ProveedorClaveEvidencia {
    fun clave(): SecretKey
}

/**
 * Clave AES-256 residente en el Android Keystore. No es exportable: el material nunca
 * sale del almacén, y en dispositivos con StrongBox o TEE ni siquiera sale del hardware.
 */
@Singleton
class ProveedorClaveKeystore @Inject constructor() : ProveedorClaveEvidencia {

    override fun clave(): SecretKey {
        val keystore = KeyStore.getInstance(PROVEEDOR).apply { load(null) }
        (keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEEDOR)
        generador.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Obliga a que el IV lo genere la plataforma. Va activo por defecto; se
                // deja explícito porque desactivarlo por descuido —y reutilizar un IV con
                // GCM— rompe la confidencialidad por completo.
                .setRandomizedEncryptionRequired(true)
                // Sin autenticación de usuario: WorkManager sube la cola en segundo plano,
                // posiblemente con el teléfono bloqueado. Exigirla dejaría evidencias sin
                // subir hasta que alguien desbloquee, y además invalidaría la clave al
                // cambiar el patrón de bloqueo, perdiendo evidencia válida. El cifrado
                // aquí protege contra extracción del dispositivo, no contra su dueño.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generador.generateKey()
    }

    private companion object {
        const val PROVEEDOR = "AndroidKeyStore"
        const val ALIAS = "verificlean_evidencia_v1"
    }
}
