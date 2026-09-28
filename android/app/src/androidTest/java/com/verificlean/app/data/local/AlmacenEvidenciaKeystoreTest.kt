package com.verificlean.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Cifrado contra el **Android Keystore real**, no contra una clave AES de software.
 *
 * `AlmacenEvidenciaCifradaTest` (JVM) ya cubre formato, IV, AAD y detección de
 * manipulación, que es donde vive la lógica. Lo que sólo se puede comprobar en un
 * dispositivo es el comportamiento del almacén de claves: que la clave se cree con los
 * parámetros que pedimos, que **no sea exportable**, que sobreviva entre instancias, y que
 * el descifrado funcione con una clave que nunca sale del hardware.
 *
 * Correr con:  ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AlmacenEvidenciaKeystoreTest {

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var carpeta: File
    private lateinit var almacen: AlmacenEvidenciaCifrada

    private val jpeg = "evidencia-fotografica-de-prueba".toByteArray()
    private val idEvidencia = "0b1f4e6a-3c2d-4a5b-9e8f-1a2b3c4d5e6f"

    @Before
    fun preparar() {
        carpeta = File(contexto.filesDir, "test_evidencias").apply {
            deleteRecursively()
            mkdirs()
        }
        almacen = AlmacenEvidenciaCifrada(carpeta, ProveedorClaveKeystore())
    }

    @After
    fun limpiar() {
        carpeta.deleteRecursively()
    }

    @Test
    fun cifraYDescifraConLaClaveDelKeystore() {
        val ruta = almacen.guardar(idEvidencia, jpeg)
        assertArrayEquals(jpeg, almacen.leer(idEvidencia, ruta))
    }

    @Test
    fun laClaveQuedaRegistradaEnElKeystoreDeAndroid() {
        almacen.guardar(idEvidencia, jpeg)

        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(
            "La clave debería existir en AndroidKeyStore",
            keystore.containsAlias("verificlean_evidencia_v1"),
        )
    }

    @Test
    fun elMaterialDeLaClaveNoEsExportable() {
        almacen.guardar(idEvidencia, jpeg)

        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val clave = (keystore.getEntry("verificlean_evidencia_v1", null) as KeyStore.SecretKeyEntry)
            .secretKey

        // Ésta es la propiedad que justifica usar el Keystore: ni con root se puede copiar
        // la clave para descifrar las evidencias en otro sitio.
        assertNull("El material de la clave no debe poder extraerse", clave.encoded)
    }

    @Test
    fun otraInstanciaDelAlmacenLeeLoQueCifroLaPrimera() {
        // La clave persiste en el Keystore entre instancias y entre arranques del proceso:
        // es lo que permite que WorkManager suba horas después una foto cifrada hoy.
        val ruta = almacen.guardar(idEvidencia, jpeg)

        val otraInstancia = AlmacenEvidenciaCifrada(carpeta, ProveedorClaveKeystore())
        assertArrayEquals(jpeg, otraInstancia.leer(idEvidencia, ruta))
    }

    @Test
    fun cadaArchivoUsaUnIvDistinto() {
        val a = File(almacen.guardar("id-a", jpeg)).readBytes()
        val b = File(almacen.guardar("id-b", jpeg)).readBytes()

        // Mismo contenido, misma clave: si los archivos coincidieran, el Keystore estaría
        // reutilizando el IV y GCM perdería toda su garantía.
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun noSePuedeDescifrarConElIdDeOtraEvidencia() {
        val ruta = almacen.guardar(idEvidencia, jpeg)
        assertNull(almacen.leer("otro-id", ruta))
    }

    @Test
    fun unArchivoManipuladoEnDiscoSeRechaza() {
        val ruta = almacen.guardar(idEvidencia, jpeg)
        val archivo = File(ruta)
        val bytes = archivo.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        archivo.writeBytes(bytes)

        assertNull(almacen.leer(idEvidencia, ruta))
    }

    @Test
    fun soportaUnaFotoDelTamanoRealDeUnaCamara() {
        // 4 MB es lo típico de un JPEG de 12 MP. Con el Keystore el cifrado pasa por el
        // TEE, así que conviene comprobar que no revienta ni tarda de forma absurda.
        val grande = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }

        val ruta = almacen.guardar(idEvidencia, grande)

        assertArrayEquals(grande, almacen.leer(idEvidencia, ruta))
    }

    @Test
    fun noQuedanTemporalesTrasUnaEscrituraCorrecta() {
        almacen.guardar(idEvidencia, jpeg)
        assertFalse(carpeta.listFiles()!!.any { it.name.endsWith(".tmp") })
    }
}
