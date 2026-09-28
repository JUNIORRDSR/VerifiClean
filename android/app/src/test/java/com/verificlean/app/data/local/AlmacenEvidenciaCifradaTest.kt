package com.verificlean.app.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Cifrado de evidencias.
 *
 * Corre en JVM con una clave AES de software en vez del Android Keystore, que no existe
 * fuera de un dispositivo. Lo que se prueba aquí es toda la lógica donde caben bugs
 * —formato del archivo, IV, AAD, detección de manipulación, escritura atómica, limpieza—
 * y eso es idéntico con una clave o con la otra. Lo único que queda sin cubrir es la
 * declaración del `KeyGenParameterSpec`, que no tiene ramas.
 */
class AlmacenEvidenciaCifradaTest {

    @get:Rule val carpetaTemporal = TemporaryFolder()

    private lateinit var carpeta: File
    private lateinit var almacen: AlmacenEvidenciaCifrada

    /** Clave AES-256 de software, equivalente funcional de la del Keystore. */
    private val claveFija: SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val proveedor = object : ProveedorClaveEvidencia {
        override fun clave(): SecretKey = claveFija
    }

    private val jpeg = "contenido-de-la-foto-de-evidencia".toByteArray()
    private val idEvidencia = "0b1f4e6a-3c2d-4a5b-9e8f-1a2b3c4d5e6f"

    @Before
    fun preparar() {
        carpeta = carpetaTemporal.newFolder("evidencias")
        almacen = AlmacenEvidenciaCifrada(carpeta, proveedor)
    }

    // ------------------------------------------------------------- ida y vuelta

    @Test
    fun `lo cifrado se recupera intacto`() {
        val ruta = almacen.guardar(idEvidencia, jpeg)
        assertArrayEquals(jpeg, almacen.leer(idEvidencia, ruta))
    }

    @Test
    fun `el archivo en disco no contiene el contenido en claro`() {
        val ruta = almacen.guardar(idEvidencia, jpeg)
        val enDisco = File(ruta).readBytes()

        // Si el JPEG apareciera literal en el archivo, el cifrado no estaría haciendo nada.
        assertFalse(
            "El contenido en claro aparece en el archivo cifrado",
            String(enDisco, Charsets.ISO_8859_1).contains(String(jpeg, Charsets.ISO_8859_1)),
        )
    }

    @Test
    fun `cifrar dos veces lo mismo produce archivos distintos`() {
        // Si coincidieran, el IV se estaría reutilizando — con GCM eso rompe la
        // confidencialidad por completo, no sólo la teórica.
        val a = File(almacen.guardar("id-a", jpeg)).readBytes()
        val b = File(almacen.guardar("id-b", jpeg)).readBytes()
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `soporta una foto de varios megas`() {
        val grande = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val ruta = almacen.guardar(idEvidencia, grande)
        assertArrayEquals(grande, almacen.leer(idEvidencia, ruta))
    }

    @Test
    fun `soporta un contenido vacio sin romper el formato`() {
        val ruta = almacen.guardar(idEvidencia, ByteArray(0))
        assertArrayEquals(ByteArray(0), almacen.leer(idEvidencia, ruta))
    }

    // ------------------------------------------------------ AAD y manipulación

    @Test
    fun `no se puede descifrar una evidencia con el id de otra`() {
        // Es la defensa contra intercambiar archivos .enc en un dispositivo rooteado para
        // que una visita suba la foto de otra.
        val ruta = almacen.guardar(idEvidencia, jpeg)
        assertNull(almacen.leer("otro-id-distinto", ruta))
    }

    @Test
    fun `un byte alterado del ciphertext invalida el archivo`() {
        val ruta = almacen.guardar(idEvidencia, jpeg)
        val archivo = File(ruta)
        val bytes = archivo.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        archivo.writeBytes(bytes)

        assertNull(almacen.leer(idEvidencia, ruta))
    }

    @Test
    fun `alterar el IV invalida el archivo`() {
        val ruta = almacen.guardar(idEvidencia, jpeg)
        val archivo = File(ruta)
        val bytes = archivo.readBytes()
        bytes[3] = (bytes[3].toInt() xor 0xFF).toByte()
        archivo.writeBytes(bytes)

        assertNull(almacen.leer(idEvidencia, ruta))
    }

    @Test
    fun `un archivo truncado devuelve null en vez de lanzar`() {
        val ruta = almacen.guardar(idEvidencia, jpeg)
        val archivo = File(ruta)
        archivo.writeBytes(archivo.readBytes().copyOfRange(0, 5))

        assertNull(almacen.leer(idEvidencia, ruta))
    }

    @Test
    fun `un archivo vacio devuelve null`() {
        val ruta = almacen.guardar(idEvidencia, jpeg)
        File(ruta).writeBytes(ByteArray(0))
        assertNull(almacen.leer(idEvidencia, ruta))
    }

    @Test
    fun `una version de formato desconocida devuelve null`() {
        val ruta = almacen.guardar(idEvidencia, jpeg)
        val archivo = File(ruta)
        val bytes = archivo.readBytes()
        bytes[0] = 99
        archivo.writeBytes(bytes)

        assertNull(almacen.leer(idEvidencia, ruta))
    }

    @Test
    fun `una clave distinta no puede leer lo cifrado con la anterior`() {
        val ruta = almacen.guardar(idEvidencia, jpeg)

        val otraClave = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val otroAlmacen = AlmacenEvidenciaCifrada(
            carpeta,
            object : ProveedorClaveEvidencia {
                override fun clave(): SecretKey = otraClave
            },
        )

        assertNull(otroAlmacen.leer(idEvidencia, ruta))
    }

    @Test
    fun `un archivo inexistente devuelve null`() {
        assertNull(almacen.leer(idEvidencia, File(carpeta, "no-existe.enc").absolutePath))
    }

    // ----------------------------------------------------- escritura y limpieza

    @Test
    fun `no deja temporales tras una escritura correcta`() {
        almacen.guardar(idEvidencia, jpeg)
        val temporales = carpeta.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("Quedaron temporales: $temporales", temporales.isEmpty())
    }

    @Test
    fun `reescribir la misma evidencia reemplaza el archivo sin dejar restos`() {
        almacen.guardar(idEvidencia, jpeg)
        val nuevoContenido = "otra-foto".toByteArray()
        val ruta = almacen.guardar(idEvidencia, nuevoContenido)

        assertEquals(1, carpeta.listFiles()!!.size)
        assertArrayEquals(nuevoContenido, almacen.leer(idEvidencia, ruta))
    }

    @Test
    fun `la limpieza borra huerfanos y temporales pero respeta lo vigente`() {
        val vigente = almacen.guardar("evidencia-en-cola", jpeg)
        val huerfano = almacen.guardar("evidencia-sin-fila", jpeg)
        val temporal = File(carpeta, "a-medias.jpg.enc.tmp").apply { writeBytes(byteArrayOf(1, 2)) }

        val borrados = almacen.limpiarHuerfanos(setOf(vigente))

        assertEquals(2, borrados)
        assertTrue(File(vigente).exists())
        assertFalse(File(huerfano).exists())
        assertFalse(temporal.exists())
    }

    @Test
    fun `la limpieza no borra nada cuando todo esta vigente`() {
        val a = almacen.guardar("a", jpeg)
        val b = almacen.guardar("b", jpeg)
        assertEquals(0, almacen.limpiarHuerfanos(setOf(a, b)))
    }

    @Test
    fun `borrar es idempotente`() {
        val ruta = almacen.guardar(idEvidencia, jpeg)
        assertTrue(almacen.borrar(ruta))
        assertFalse(almacen.borrar(ruta))
    }

    @Test
    fun `crea la carpeta si no existe`() {
        val nueva = File(carpetaTemporal.root, "todavia-no-existe")
        val almacenNuevo = AlmacenEvidenciaCifrada(nueva, proveedor)

        val ruta = almacenNuevo.guardar(idEvidencia, jpeg)

        assertTrue(nueva.isDirectory)
        assertArrayEquals(jpeg, almacenNuevo.leer(idEvidencia, ruta))
    }
}
