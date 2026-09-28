package com.verificlean.app.data.repository

import com.verificlean.app.capture.ProveedorIntegridad
import com.verificlean.app.capture.ProveedorUbicacion
import com.verificlean.app.capture.UbicacionCapturada
import com.verificlean.app.core.Resultado
import com.verificlean.app.data.local.AlmacenEvidenciaCifrada
import com.verificlean.app.data.local.EvidenciaPendienteDao
import com.verificlean.app.data.local.EvidenciaPendienteEntity
import com.verificlean.app.data.local.SesionStore
import com.verificlean.app.data.local.VisitaDao
import com.verificlean.app.data.sync.ProgramadorSincronizacion
import com.verificlean.app.domain.model.ChecklistItem
import com.verificlean.app.domain.model.EstadoSincronizacion
import com.verificlean.app.domain.model.RespuestaChecklist
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.repository.EntradaEvidencia
import io.mockk.Ordering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * El contrato de `capturarEvidencia`: **cuando la foto está cifrada en disco y su fila
 * insertada, la evidencia ya no se puede perder**, pase lo que pase después.
 *
 * Esa garantía es el producto entero. Un supervisor que hizo el trayecto, subió al piso
 * doce y tomó la foto no puede quedarse sin nada porque el GPS tardó y el sistema mató la
 * app. Por eso el orden de operaciones se comprueba de forma explícita.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenciaRepositoryImplTest {

    private lateinit var dao: EvidenciaPendienteDao
    private lateinit var visitaDao: VisitaDao
    private lateinit var almacen: AlmacenEvidenciaCifrada
    private lateinit var programador: ProgramadorSincronizacion
    private lateinit var ubicacion: ProveedorUbicacion
    private lateinit var integridad: ProveedorIntegridad
    private lateinit var sesion: SesionStore
    private lateinit var repositorio: EvidenciaRepositoryImpl

    /**
     * El repositorio hace `withContext(io)`, así que su dispatcher tiene que compartir
     * scheduler con el de `runTest` — si no, kotlinx-coroutines aborta con
     * "different schedulers".
     */
    private val despachador = UnconfinedTestDispatcher()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02, 0x03)
    private val supervisor = Usuario("u_sup_1", "Carlos", "c@x.test", Rol.SUPERVISOR, activo = true)

    @Before
    fun preparar() {
        dao = mockk(relaxed = true)
        visitaDao = mockk(relaxed = true)
        almacen = mockk(relaxed = true)
        programador = mockk(relaxed = true)
        ubicacion = mockk(relaxed = true)
        integridad = mockk(relaxed = true)
        sesion = mockk(relaxed = true)

        every { almacen.guardar(any(), any()) } answers { "/data/evidencias/${firstArg<String>()}.enc" }
        every { sesion.usuario } returns flowOf(supervisor)
        coEvery { ubicacion.ubicacionActual() } returns
            UbicacionCapturada(4.70057, -74.06366, 12.0, esSimulada = false)
        coEvery { integridad.token(any()) } returns "fake:confiable"

        repositorio = EvidenciaRepositoryImpl(
            dao = dao,
            visitaDao = visitaDao,
            almacen = almacen,
            programador = programador,
            proveedorUbicacion = ubicacion,
            proveedorIntegridad = integridad,
            sesionStore = sesion,
            io = despachador,
            scopeAplicacion = CoroutineScope(SupervisorJob() + despachador),
        )
    }

    private fun entrada(bytes: ByteArray = jpeg) = EntradaEvidencia(
        visitId = "v1",
        visitaNombreSede = "Torre Bavaria",
        jpeg = bytes,
        checklist = listOf(ChecklistItem("banos", "¿Baños limpios?", RespuestaChecklist.SI, null)),
    )

    // ------------------------------------------------- la garantía de no perder la foto

    @Test
    fun `la foto se cifra y se encola ANTES de pedir ubicacion e integridad`() {
        runTest(despachador) {
            repositorio.capturarEvidencia(entrada())

            // Este orden es la garantía entera. Si se invirtiera, la foto viviría sólo en
            // memoria durante los segundos que tardan GPS y Play Integrity, y una muerte
            // del proceso ahí la perdería.
            coVerify(ordering = Ordering.ORDERED) {
                almacen.guardar(any(), any())
                dao.insertar(any())
                ubicacion.ubicacionActual()
                integridad.token(any())
                dao.enriquecer(any(), any(), any(), any(), any(), any(), any(), any())
            }
        }
    }

    @Test
    fun `la fila entra en PREPARANDO y solo pasa a PENDIENTE al enriquecerse`() {
        runTest(despachador) {
            val fila = slot<EvidenciaPendienteEntity>()
            coEvery { dao.insertar(capture(fila)) } returns Unit
            val estadoFinal = slot<String>()
            coEvery {
                dao.enriquecer(any(), any(), any(), any(), any(), any(), any(), capture(estadoFinal))
            } returns Unit

            repositorio.capturarEvidencia(entrada())

            assertEquals(EstadoSincronizacion.PREPARANDO.name, fila.captured.estado)
            assertNull("La ubicación aún no se conoce al insertar", fila.captured.lat)
            assertNull(fila.captured.integrityToken)
            assertEquals(EstadoSincronizacion.PENDIENTE.name, estadoFinal.captured)
        }
    }

    @Test
    fun `si falla la ubicacion la evidencia sigue encolada`() {
        runTest(despachador) {
            coEvery { ubicacion.ubicacionActual() } returns null

            val resultado = repositorio.capturarEvidencia(entrada())

            assertTrue(resultado is Resultado.Exito)
            // Sin ubicación el servidor la marcará, que es lo correcto. Lo que no puede
            // pasar es que la foto desaparezca.
            coVerify { dao.enriquecer(any(), null, null, any(), any(), any(), any(), EstadoSincronizacion.PENDIENTE.name) }
        }
    }

    @Test
    fun `si falla Play Integrity la evidencia sigue encolada`() {
        runTest(despachador) {
            coEvery { integridad.token(any()) } returns null

            assertTrue(repositorio.capturarEvidencia(entrada()) is Resultado.Exito)
            coVerify { dao.enriquecer(any(), any(), any(), any(), any(), null, any(), any()) }
        }
    }

    @Test
    fun `confirma al terminar la fase durable sin esperar integridad`() = runTest(despachador) {
        val integridadBloqueada = CompletableDeferred<String?>()
        coEvery { integridad.token(any()) } coAnswers { integridadBloqueada.await() }

        val resultado = repositorio.capturarEvidencia(entrada())

        assertTrue(resultado is Resultado.Exito)
        coVerify { dao.insertar(any()) }
        coVerify(exactly = 0) {
            dao.enriquecer(any(), any(), any(), any(), any(), any(), any(), any())
        }

        integridadBloqueada.complete("fake:confiable")
        advanceUntilIdle()
        coVerify { dao.enriquecer(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `graba el uid del supervisor para que otro usuario no suba su evidencia`() {
        runTest(despachador) {
            val fila = slot<EvidenciaPendienteEntity>()
            coEvery { dao.insertar(capture(fila)) } returns Unit

            repositorio.capturarEvidencia(entrada())

            assertEquals("u_sup_1", fila.captured.ownerUid)
        }
    }

    @Test
    fun `sin sesion no se captura`() {
        runTest(despachador) {
            every { sesion.usuario } returns flowOf(null)

            val resultado = repositorio.capturarEvidencia(entrada())

            assertTrue(resultado is Resultado.Fallo)
            coVerify(exactly = 0) { almacen.guardar(any(), any()) }
        }
    }

    // ------------------------------------------------------------------ hash y nonce

    @Test
    fun `calcula el SHA-256 de los bytes en claro antes de cifrar`() {
        runTest(despachador) {
            val fila = slot<EvidenciaPendienteEntity>()
            coEvery { dao.insertar(capture(fila)) } returns Unit

            repositorio.capturarEvidencia(entrada())

            val esperado = MessageDigest.getInstance("SHA-256").digest(jpeg)
                .joinToString("") { "%02x".format(it) }
            assertEquals(esperado, fila.captured.hashSHA256)
        }
    }

    @Test
    fun `el nonce liga el token a la visita, la evidencia y la foto`() {
        val nonce = EvidenciaRepositoryImpl.calcularNonce("v1", "e1", "h1")

        assertEquals(nonce, EvidenciaRepositoryImpl.calcularNonce("v1", "e1", "h1"))
        assertTrue(nonce != EvidenciaRepositoryImpl.calcularNonce("v2", "e1", "h1"))
        assertTrue(nonce != EvidenciaRepositoryImpl.calcularNonce("v1", "e2", "h1"))
        assertTrue(nonce != EvidenciaRepositoryImpl.calcularNonce("v1", "e1", "h2"))
    }

    @Test
    fun `el nonce coincide byte a byte con el que calcula el backend`() {
        // Valor fijado a partir de `calcularNonceEsperado` de backend/src/lib/nonce.ts para
        // la entrada v1:e1:h1. Si una de las dos implementaciones cambia sin la otra, este
        // test lo dice — de lo contrario el síntoma sería que TODAS las evidencias salen
        // marcadas como token reutilizado, sin ninguna pista de por qué.
        assertEquals(
            "YrxEOmy2NqR3gCbiWxGqA_P0FuyDNXYoKh2I5n5wseE",
            EvidenciaRepositoryImpl.calcularNonce("v1", "e1", "h1"),
        )
    }

    @Test
    fun `dos capturas reciben claves de idempotencia distintas`() {
        runTest(despachador) {
            val filas = mutableListOf<EvidenciaPendienteEntity>()
            coEvery { dao.insertar(capture(filas)) } returns Unit

            repositorio.capturarEvidencia(entrada())
            repositorio.capturarEvidencia(entrada())

            assertEquals(2, filas.size)
            assertTrue(filas[0].clienteEvidenciaId != filas[1].clienteEvidenciaId)
        }
    }

    // ------------------------------------------------------------------ modos de fallo

    @Test
    fun `rechaza una foto vacia sin tocar el disco`() {
        runTest(despachador) {
            val resultado = repositorio.capturarEvidencia(entrada(bytes = ByteArray(0)))

            assertTrue(resultado is Resultado.Fallo)
            coVerify(exactly = 0) { dao.insertar(any()) }
        }
    }

    @Test
    fun `si falla el insert borra el archivo, que sin fila nadie subiria`() {
        runTest(despachador) {
            coEvery { dao.insertar(any()) } throws IllegalStateException("disco lleno")

            val resultado = repositorio.capturarEvidencia(entrada())

            assertTrue(resultado is Resultado.Fallo)
            // Es un huérfano de verdad: sin fila no hay quien lo suba nunca. Distinto de
            // borrar una foto viva por un fallo posterior recuperable.
            coVerify(exactly = 1) { almacen.borrar(any()) }
        }
    }

    @Test
    fun `si falla el cifrado no encola nada`() {
        runTest(despachador) {
            every { almacen.guardar(any(), any()) } throws SecurityException("keystore no disponible")

            val resultado = repositorio.capturarEvidencia(entrada())

            assertTrue(resultado is Resultado.Fallo)
            coVerify(exactly = 0) { dao.insertar(any()) }
            coVerify(exactly = 0) { programador.encolarSincronizacion(any()) }
        }
    }

    // ------------------------------------------------------------------- reintento

    @Test
    fun `reintentar reinicia el contador de intentos y fuerza la sincronizacion`() {
        runTest(despachador) {
            coEvery { dao.obtener("ev1") } returns filaFallida()

            val resultado = repositorio.reintentar("ev1")

            assertTrue(resultado is Resultado.Exito)
            coVerify { dao.actualizarEstado("ev1", EstadoSincronizacion.PENDIENTE.name, 0, null) }
            coVerify { programador.encolarSincronizacion(expedito = true) }
        }
    }

    @Test
    fun `reintentar algo que no existe devuelve fallo`() {
        runTest(despachador) {
            coEvery { dao.obtener("no-existe") } returns null
            assertTrue(repositorio.reintentar("no-existe") is Resultado.Fallo)
        }
    }

    @Test
    fun `no permite reintentar la evidencia de otro supervisor`() = runTest(despachador) {
        coEvery { dao.obtener("ev1") } returns filaFallida().copy(ownerUid = "u_sup_2")

        val resultado = repositorio.reintentar("ev1")

        assertTrue(resultado is Resultado.Fallo)
        coVerify(exactly = 0) { dao.actualizarEstado(any(), any(), any(), any()) }
        coVerify(exactly = 0) { programador.encolarSincronizacion(expedito = true) }
    }

    @Test
    fun `observa solamente la cola del supervisor en sesion`() = runTest(despachador) {
        every { dao.observarPorPropietario("u_sup_1") } returns flowOf(listOf(filaFallida()))

        val filas = repositorio.observarPendientes().first()

        assertEquals(listOf("ev1"), filas.map { it.clienteEvidenciaId })
    }

    @Test
    fun `encola la sincronizacion tras una captura correcta`() {
        runTest(despachador) {
            repositorio.capturarEvidencia(entrada())
            // Una ejecución protege la fila PREPARANDO y otra se encadena al terminar el
            // enriquecimiento, por si la primera ganó la carrera.
            coVerify(exactly = 2) { programador.encolarSincronizacion(any()) }
            assertNotNull(repositorio)
        }
    }

    private fun filaFallida() = EvidenciaPendienteEntity(
        clienteEvidenciaId = "ev1",
        ownerUid = "u_sup_1",
        visitId = "v1",
        visitaNombreSede = "Sede",
        rutaArchivoCifrado = "/tmp/ev1.enc",
        hashSHA256 = "a".repeat(64),
        lat = 4.7,
        lng = -74.06,
        precisionMetros = null,
        mockLocationReportada = false,
        integrityToken = null,
        capturadoEnMillis = 0L,
        checklistJson = "[]",
        estado = EstadoSincronizacion.FALLIDA.name,
        intentos = 7,
        ultimoError = "409",
    )
}
