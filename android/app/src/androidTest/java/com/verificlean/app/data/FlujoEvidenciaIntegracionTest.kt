package com.verificlean.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.verificlean.app.data.local.AlmacenEvidenciaCifrada
import com.verificlean.app.data.local.EvidenciaPendienteEntity
import com.verificlean.app.data.local.ProveedorClaveKeystore
import com.verificlean.app.data.local.VerifiCleanDatabase
import com.verificlean.app.domain.model.EstadoSincronizacion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Room real + Keystore real, juntos.
 *
 * Cada pieza tiene sus tests aislados; esto comprueba que **encajan**: que la ruta que
 * Room guarda apunta a un archivo que el Keystore puede descifrar, que el ciclo de vida de
 * la cola (PREPARANDO → PENDIENTE → borrada) deja el disco consistente, y que la limpieza
 * de huérfanos respeta lo que sigue referenciado.
 *
 * Es el único test que ejercita la promesa completa del producto contra las APIs de verdad.
 *
 * Correr con:  ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class FlujoEvidenciaIntegracionTest {

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var bd: VerifiCleanDatabase
    private lateinit var carpeta: File
    private lateinit var almacen: AlmacenEvidenciaCifrada

    private val jpeg = "foto-real-de-la-sede".toByteArray()

    @Before
    fun preparar() {
        bd = Room.inMemoryDatabaseBuilder(contexto, VerifiCleanDatabase::class.java).build()
        carpeta = File(contexto.filesDir, "test_flujo").apply {
            deleteRecursively()
            mkdirs()
        }
        almacen = AlmacenEvidenciaCifrada(carpeta, ProveedorClaveKeystore())
    }

    @After
    fun limpiar() {
        bd.close()
        carpeta.deleteRecursively()
    }

    private fun fila(id: String, ruta: String, estado: EstadoSincronizacion) =
        EvidenciaPendienteEntity(
            clienteEvidenciaId = id,
            ownerUid = "u_sup_1",
            visitId = "visit_demo_1",
            visitaNombreSede = "Torre Bavaria",
            rutaArchivoCifrado = ruta,
            hashSHA256 = "a".repeat(64),
            lat = null,
            lng = null,
            precisionMetros = null,
            mockLocationReportada = false,
            integrityToken = null,
            capturadoEnMillis = System.currentTimeMillis(),
            checklistJson = "[]",
            estado = estado.name,
            intentos = 0,
            ultimoError = null,
        )

    @Test
    fun laRutaGuardadaEnRoomApuntaAUnArchivoDescifrable() = runBlocking {
        val id = "ev-1"
        val ruta = almacen.guardar(id, jpeg)
        bd.evidenciaPendienteDao().insertar(fila(id, ruta, EstadoSincronizacion.PREPARANDO))

        val guardada = bd.evidenciaPendienteDao().obtener(id)
        assertNotNull(guardada)
        assertArrayEquals(jpeg, almacen.leer(guardada!!.clienteEvidenciaId, guardada.rutaArchivoCifrado))
    }

    @Test
    fun elCicloCompletoDejaDiscoYBaseConsistentes() = runBlocking {
        val dao = bd.evidenciaPendienteDao()
        val id = "ev-ciclo"
        val ruta = almacen.guardar(id, jpeg)

        // 1. Fase durable: la foto ya está a salvo aunque falte todo lo demás.
        dao.insertar(fila(id, ruta, EstadoSincronizacion.PREPARANDO))
        assertTrue(File(ruta).exists())
        assertEquals(0, dao.siguientesParaSubir("u_sup_1", 10).size)

        // 2. Enriquecimiento: entra en la cola de subida.
        dao.enriquecer(id, 4.70057, -74.06366, 12.0, false, "fake:confiable", "[]", EstadoSincronizacion.PENDIENTE.name)
        assertEquals(1, dao.siguientesParaSubir("u_sup_1", 10).size)

        // 3. Subida confirmada: se borran fila y archivo, en ese orden.
        dao.borrar(id)
        almacen.borrar(ruta)

        assertNull(dao.obtener(id))
        assertFalse(File(ruta).exists())
        assertEquals(0, carpeta.listFiles()!!.size)
    }

    @Test
    fun laLimpiezaDeHuerfanosRespetaLoQueSigueEnLaCola() = runBlocking {
        val dao = bd.evidenciaPendienteDao()

        val enCola = almacen.guardar("ev-en-cola", jpeg)
        val fallida = almacen.guardar("ev-fallida", jpeg)
        val huerfano = almacen.guardar("ev-sin-fila", jpeg)

        dao.insertar(fila("ev-en-cola", enCola, EstadoSincronizacion.PENDIENTE))
        dao.insertar(fila("ev-fallida", fallida, EstadoSincronizacion.FALLIDA))

        val borrados = almacen.limpiarHuerfanos(dao.todasLasRutas().toSet())

        assertEquals(1, borrados)
        assertTrue(File(enCola).exists())
        // La fallida conserva su archivo: el supervisor todavía puede reintentarla, y
        // borrarlo la dejaría apuntando a la nada.
        assertTrue("La evidencia fallida no debe perder su foto", File(fallida).exists())
        assertFalse(File(huerfano).exists())
    }

    @Test
    fun unaFilaAbandonadaEnPreparandoSeDetectaYConservaSuFoto() = runBlocking {
        val dao = bd.evidenciaPendienteDao()
        val id = "ev-abandonada"
        val ruta = almacen.guardar(id, jpeg)

        // Simula que el proceso murió hace diez minutos entre guardar y enriquecer.
        dao.insertar(
            fila(id, ruta, EstadoSincronizacion.PREPARANDO)
                .copy(capturadoEnMillis = System.currentTimeMillis() - 10 * 60_000),
        )

        val abandonadas = dao.preparandoAbandonadas(System.currentTimeMillis() - 2 * 60_000)

        assertEquals(1, abandonadas.size)
        assertArrayEquals(jpeg, almacen.leer(id, ruta))

        dao.cambiarEstado(id, EstadoSincronizacion.PENDIENTE.name)
        assertEquals(1, dao.siguientesParaSubir("u_sup_1", 10).size)
    }

    @Test
    fun laColaDeOtroSupervisorNoSeToca() = runBlocking {
        val dao = bd.evidenciaPendienteDao()
        val ruta = almacen.guardar("ev-ajena", jpeg)
        dao.insertar(fila("ev-ajena", ruta, EstadoSincronizacion.PENDIENTE).copy(ownerUid = "u_sup_2"))

        assertEquals(0, dao.siguientesParaSubir("u_sup_1", 10).size)
        // Pero sigue ahí, con su foto intacta, esperando a que su dueño vuelva.
        assertEquals(1, dao.siguientesParaSubir("u_sup_2", 10).size)
        assertArrayEquals(jpeg, almacen.leer("ev-ajena", ruta))
    }
}
