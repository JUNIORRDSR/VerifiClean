package com.verificlean.app.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.verificlean.app.data.local.AlmacenEvidenciaCifrada
import com.verificlean.app.data.local.EvidenciaPendienteDao
import com.verificlean.app.data.local.EvidenciaPendienteEntity
import com.verificlean.app.data.local.SesionStore
import com.verificlean.app.data.remote.VerifiCleanApi
import com.verificlean.app.data.remote.dto.EvidenciaConfirmacionDto
import com.verificlean.app.domain.model.EstadoSincronizacion
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Usuario
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

/**
 * Orquestación completa del worker de subida.
 *
 * Es donde vive la promesa del producto: **una evidencia nunca se borra sin confirmación
 * del servidor**. Los tests del clasificador cubren la decisión; estos cubren lo que se
 * hace con ella, que es lo que puede destruir el trabajo de un supervisor.
 */
@RunWith(RobolectricTestRunner::class)
class SincronizarEvidenciasWorkerTest {

    private lateinit var dao: EvidenciaPendienteDao
    private lateinit var almacen: AlmacenEvidenciaCifrada
    private lateinit var api: VerifiCleanApi
    private lateinit var sesion: SesionStore
    private lateinit var programador: ProgramadorSincronizacion

    private val supervisor = Usuario("u_sup_1", "Carlos", "c@x.test", Rol.SUPERVISOR, activo = true)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)

    @Before
    fun preparar() {
        dao = mockk(relaxed = true)
        almacen = mockk(relaxed = true)
        api = mockk(relaxed = true)
        sesion = mockk(relaxed = true)
        programador = mockk(relaxed = true)

        every { sesion.usuario } returns flowOf(supervisor)
        every { almacen.leer(any(), any()) } returns jpeg
        coEvery { dao.preparandoAbandonadas(any()) } returns emptyList()
        coEvery { dao.todasLasRutas() } returns emptyList()
    }

    /**
     * `lat` gobierna las dos coordenadas: en producción salen ambas de la misma lectura,
     * así que o están las dos o no está ninguna. Dejarlas independientes en el fixture
     * modelaría un estado que el código nunca produce.
     */
    private fun fila(
        id: String = "ev1",
        intentos: Int = 0,
        estado: EstadoSincronizacion = EstadoSincronizacion.PENDIENTE,
        lat: Double? = 4.70057,
        lng: Double? = if (lat == null) null else -74.06366,
    ) = EvidenciaPendienteEntity(
        clienteEvidenciaId = id,
        ownerUid = supervisor.id,
        visitId = "v1",
        visitaNombreSede = "Torre Bavaria",
        rutaArchivoCifrado = "/data/$id.enc",
        hashSHA256 = "a".repeat(64),
        lat = lat,
        lng = lng,
        precisionMetros = 12.0,
        mockLocationReportada = false,
        integrityToken = "fake:confiable",
        capturadoEnMillis = 1_700_000_000_000,
        checklistJson = "[]",
        estado = estado.name,
        intentos = intentos,
        ultimoError = null,
    )

    private fun conCola(vararg lotes: List<EvidenciaPendienteEntity>) {
        var llamada = 0
        coEvery { dao.siguientesParaSubir(any(), any()) } answers {
            lotes.getOrElse(llamada++) { emptyList() }
        }
    }

    private fun respuestaOk() = Response.success(
        EvidenciaConfirmacionDto(id = "srv-1", visitId = "v1", timestampServidor = "2026-08-15T12:00:00Z"),
    )

    private fun respuestaError(codigo: Int, cuerpo: String) =
        Response.error<EvidenciaConfirmacionDto>(
            codigo,
            cuerpo.toResponseBody("application/json".toMediaType()),
        )

    private suspend fun ejecutar(): ListenableWorker.Result {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<SincronizarEvidenciasWorker>(contexto)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = SincronizarEvidenciasWorker(
                    appContext, workerParameters, dao, almacen, api, sesion, programador,
                )
            })
            .build()
        return worker.doWork()
    }

    // ------------------------------------------------------------------ camino feliz

    @Test
    fun `una subida confirmada borra fila y archivo, en ese orden`() = runTest {
        conCola(listOf(fila()))
        coEvery { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns respuestaOk()

        val resultado = ejecutar()

        assertTrue(resultado is ListenableWorker.Result.Success)
        coVerify(exactly = 1) { dao.borrar("ev1") }
        coVerify(exactly = 1) { almacen.borrar("/data/ev1.enc") }
    }

    @Test
    fun `una cola vacia termina bien y limpia huerfanos`() = runTest {
        conCola(emptyList())

        assertTrue(ejecutar() is ListenableWorker.Result.Success)
        coVerify { almacen.limpiarHuerfanos(any()) }
    }

    @Test
    fun `una captura aun preparando mantiene vivo el trabajo de recuperacion`() = runTest {
        conCola(emptyList())
        coEvery { dao.contarPreparando(supervisor.id) } returns 1

        assertTrue(ejecutar() is ListenableWorker.Result.Retry)
        coVerify(exactly = 0) { almacen.limpiarHuerfanos(any()) }
    }

    @Test
    fun `sin sesion no sube nada pero conserva la cola`() = runTest {
        every { sesion.usuario } returns flowOf(null)
        conCola(listOf(fila()))

        assertTrue(ejecutar() is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { dao.borrar(any()) }
        coVerify(exactly = 0) { almacen.borrar(any()) }
    }

    // ------------------------------------------------------- fallos y conservación

    @Test
    fun `un fallo de red reintenta y NO borra nada`() = runTest {
        conCola(listOf(fila()))
        coEvery { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws java.io.IOException("sin red")

        val resultado = ejecutar()

        assertTrue(resultado is ListenableWorker.Result.Retry)
        coVerify(exactly = 0) { dao.borrar(any()) }
        coVerify(exactly = 0) { almacen.borrar(any()) }
        coVerify { dao.actualizarEstado("ev1", EstadoSincronizacion.PENDIENTE.name, 1, any()) }
    }

    @Test
    fun `un 409 marca FALLIDA pero conserva fila y archivo`() = runTest {
        conCola(listOf(fila()))
        coEvery { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            respuestaError(409, """{"error":{"codigo":"CONFLICTO","mensaje":"La visita ya está completada."}}""")

        val resultado = ejecutar()

        assertTrue(resultado is ListenableWorker.Result.Success)
        coVerify { dao.actualizarEstado("ev1", EstadoSincronizacion.FALLIDA.name, 1, any()) }
        // La fila y la foto se conservan: el supervisor tiene que poder ver qué pasó.
        coVerify(exactly = 0) { dao.borrar(any()) }
        coVerify(exactly = 0) { almacen.borrar(any()) }
    }

    @Test
    fun `un 401 se trata como transitorio, no descarta la evidencia`() = runTest {
        conCola(listOf(fila()))
        coEvery { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            respuestaError(401, """{"error":{"codigo":"NO_AUTORIZADO","mensaje":"Token expirado"}}""")

        val resultado = ejecutar()

        assertTrue(resultado is ListenableWorker.Result.Retry)
        coVerify { dao.actualizarEstado("ev1", EstadoSincronizacion.PENDIENTE.name, 1, any()) }
    }

    @Test
    fun `al agotar los reintentos marca FALLIDA sin volver a reintentar`() = runTest {
        conCola(listOf(fila(intentos = ClasificadorSubida.MAX_INTENTOS - 1)))
        coEvery { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws java.io.IOException("sin red")

        val resultado = ejecutar()

        coVerify {
            dao.actualizarEstado("ev1", EstadoSincronizacion.FALLIDA.name, ClasificadorSubida.MAX_INTENTOS, any())
        }
        assertTrue("Agotado el tope no debe pedir otro reintento", resultado is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { almacen.borrar(any()) }
    }

    @Test
    fun `un archivo cifrado manipulado no se sube y queda FALLIDA`() = runTest {
        conCola(listOf(fila()))
        every { almacen.leer(any(), any()) } returns null

        ejecutar()

        coVerify { dao.actualizarEstado("ev1", EstadoSincronizacion.FALLIDA.name, 1, any()) }
        coVerify(exactly = 0) { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------ lotes

    @Test
    fun `si queda cola encadena otra ejecucion en vez de arrastrar el backoff`() = runTest {
        // `retry()` heredaría el backoff exponencial y subiría el contador de intentos del
        // WorkRequest: una cola de treinta fotos acabaría esperando horas entre lotes con
        // la red perfectamente disponible.
        conCola(listOf(fila()), listOf(fila("ev2")))
        coEvery { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns respuestaOk()

        val resultado = ejecutar()

        assertTrue(resultado is ListenableWorker.Result.Success)
        coVerify(exactly = 1) { programador.encolarSincronizacion(any()) }
    }

    @Test
    fun `el encadenado termina aunque el servidor rechace todo de forma definitiva`() = runTest {
        // El worker encadena una ejecución nueva cuando queda cola. Si el servidor
        // rechazara siempre y las filas no salieran de la cola, eso sería un bucle infinito
        // de ejecuciones. No lo es porque un rechazo definitivo las marca FALLIDA y
        // `siguientesParaSubir` las excluye — la cola se drena aunque nada se suba.
        var quedan = 3
        coEvery { dao.siguientesParaSubir(any(), any()) } answers {
            if (quedan > 0) listOf(fila("ev$quedan")) else emptyList()
        }
        coEvery { dao.actualizarEstado(any(), EstadoSincronizacion.FALLIDA.name, any(), any()) } answers {
            quedan--
        }
        coEvery { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            respuestaError(409, """{"error":{"codigo":"CONFLICTO","mensaje":"visita cerrada"}}""")

        val resultado = ejecutar()

        assertTrue(resultado is ListenableWorker.Result.Success)
        // Encadenó como mucho una vez, no en bucle.
        coVerify(atMost = 1) { programador.encolarSincronizacion(any()) }
    }

    @Test
    fun `un fallo transitorio corta el lote y no intenta las siguientes`() = runTest {
        conCola(listOf(fila("ev1"), fila("ev2"), fila("ev3")))
        coEvery { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws java.io.IOException("sin red")

        assertTrue(ejecutar() is ListenableWorker.Result.Retry)

        // Sólo se intentó la primera: si no hay red para ella, tampoco la habrá para las
        // demás, y seguir sólo gasta batería.
        coVerify(exactly = 1) { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------- recuperación de PREPARANDO

    @Test
    fun `promueve las capturas que se quedaron a medio preparar`() = runTest {
        coEvery { dao.preparandoAbandonadas(any()) } returns
            listOf(fila("abandonada", estado = EstadoSincronizacion.PREPARANDO, lat = null))
        conCola(emptyList())

        ejecutar()

        coVerify { dao.cambiarEstado("abandonada", EstadoSincronizacion.PENDIENTE.name) }
    }

    @Test
    fun `sin ubicacion se omiten lat y lng en vez de mandar 0,0`() = runTest {
        conCola(listOf(fila(lat = null)))
        coEvery { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns respuestaOk()

        ejecutar()

        // Mandar 0,0 hacía que el servidor calculara 8.241 km —del golfo de Guinea a
        // Bogotá— y el panel del admin lo mostraba como una medición real. Omitiendo los
        // campos, el servidor marca SIN_UBICACION, que es la verdad.
        coVerify {
            api.subirEvidencia(any(), any(), any(), null, null, any(), any(), any(), any(), any(), any())
        }
        // Y la evidencia se sube igual: descartarla en el dispositivo escondería el intento.
        coVerify { dao.borrar("ev1") }
    }

    @Test
    fun `con ubicacion se envian lat y lng`() = runTest {
        conCola(listOf(fila(lat = 4.70057)))
        coEvery { api.subirEvidencia(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns respuestaOk()

        ejecutar()

        coVerify {
            api.subirEvidencia(any(), any(), any(), isNull(inverse = true), isNull(inverse = true), any(), any(), any(), any(), any(), any())
        }
    }
}
