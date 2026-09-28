package com.verificlean.app.ui.pantallas.captura

import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.data.repository.VisitaRepositoryImpl
import com.verificlean.app.domain.model.EvidenciaPendiente
import com.verificlean.app.domain.repository.EntradaEvidencia
import com.verificlean.app.domain.repository.EvidenciaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * El cerrojo contra la doble captura.
 *
 * Entre pulsar el botón y recibir el callback de CameraX pasan cientos de milisegundos.
 * Antes el bloqueo sólo se activaba al recibir el resultado, así que dos toques rápidos
 * disparaban la cámara dos veces y encolaban dos evidencias con UUID distintos — el
 * supervisor subía dos fotos casi idénticas y el servidor marcaba la segunda como
 * duplicada, ensuciando el panel del admin con una alerta que no era fraude.
 *
 * Estos tests son de JVM a propósito: el cerrojo es lógica pura, y verificar concurrencia
 * real en un emulador sería más lento y menos determinista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapturaViewModelTest {

    private lateinit var evidenciaRepository: EvidenciaRepository
    private lateinit var visitaRepository: VisitaRepositoryImpl
    private lateinit var viewModel: CapturaViewModel

    private val despachador = UnconfinedTestDispatcher()

    /** Doble del repositorio que cuenta capturas y permite bloquearlas a voluntad. */
    private class RepositorioEspia(
        private val resultado: Resultado<String> = Resultado.Exito("ev-1"),
    ) : EvidenciaRepository {
        val capturas = AtomicInteger(0)
        var barrera: CountDownLatch? = null

        override suspend fun capturarEvidencia(entrada: EntradaEvidencia): Resultado<String> {
            capturas.incrementAndGet()
            barrera?.await(2, TimeUnit.SECONDS)
            return resultado
        }

        override fun observarPendientes(): Flow<List<EvidenciaPendiente>> = flowOf(emptyList())
        override fun sincronizarAhora() = Unit
        override suspend fun reintentar(clienteEvidenciaId: String) = Resultado.Exito(Unit)
    }

    @Before
    fun preparar() {
        Dispatchers.setMain(despachador)
        evidenciaRepository = mockk(relaxed = true)
        visitaRepository = mockk(relaxed = true)
        coEvery { visitaRepository.detalleCacheado(any()) } returns null
        viewModel = CapturaViewModel(evidenciaRepository, visitaRepository)
    }

    @After
    fun limpiar() {
        Dispatchers.resetMain()
    }

    @Test
    fun `el primer turno se concede y el segundo se rechaza`() {
        assertTrue("El primer disparo debe poder tomar el turno", viewModel.intentarTomarElTurno())
        assertFalse("El segundo disparo NO debe poder", viewModel.intentarTomarElTurno())
    }

    @Test
    fun `tomar el turno marca la pantalla como procesando`() {
        viewModel.intentarTomarElTurno()
        assertTrue(viewModel.estado.value.procesando)
    }

    @Test
    fun `liberar el turno permite volver a disparar`() {
        assertTrue(viewModel.intentarTomarElTurno())
        viewModel.liberarTurno("la camara fallo")

        assertFalse(viewModel.estado.value.procesando)
        assertEquals("la camara fallo", viewModel.estado.value.error)
        assertTrue("Tras liberar debe poder dispararse de nuevo", viewModel.intentarTomarElTurno())
    }

    @Test
    fun `diez hilos compitiendo sólo consiguen un turno`() {
        // Reproduce la carrera real: varios toques casi simultáneos antes de que llegue
        // ningún callback. `compareAndSet` garantiza que exactamente uno gane.
        val hilos = 10
        val listos = CountDownLatch(hilos)
        val arranque = CountDownLatch(1)
        val ganadores = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(hilos)

        repeat(hilos) {
            pool.submit {
                listos.countDown()
                arranque.await(2, TimeUnit.SECONDS)
                if (viewModel.intentarTomarElTurno()) ganadores.incrementAndGet()
            }
        }

        listos.await(2, TimeUnit.SECONDS)
        arranque.countDown()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals("Sólo un disparo debe ganar el turno", 1, ganadores.get())
    }

    @Test
    fun `una captura correcta libera el turno y marca guardada`() = runTest(despachador) {
        coEvery { evidenciaRepository.capturarEvidencia(any()) } returns Resultado.Exito("ev-1")

        viewModel.intentarTomarElTurno()
        viewModel.procesarFoto("v1", byteArrayOf(1, 2, 3))

        assertTrue(viewModel.estado.value.guardada)
        assertFalse(viewModel.estado.value.procesando)
        // Y el turno queda libre para la siguiente foto de la misma visita.
        assertTrue(viewModel.intentarTomarElTurno())
    }

    @Test
    fun `una captura fallida libera el turno y muestra el error`() = runTest(despachador) {
        coEvery { evidenciaRepository.capturarEvidencia(any()) } returns
            Resultado.Fallo(ErrorApp.Desconocido("no se pudo cifrar"))

        viewModel.intentarTomarElTurno()
        viewModel.procesarFoto("v1", byteArrayOf(1, 2, 3))

        assertFalse(viewModel.estado.value.guardada)
        assertEquals("no se pudo cifrar", viewModel.estado.value.error)
        assertTrue("Un fallo no debe dejar la pantalla bloqueada", viewModel.intentarTomarElTurno())
    }

    @Test
    fun `un segundo toque con la primera captura en vuelo no encola nada`() {
        // La carrera real: el usuario toca otra vez ANTES de que la primera captura
        // termine. Con `StandardTestDispatcher` la corrutina lanzada por `procesarFoto`
        // queda encolada y no corre hasta que se avance el reloj — que es exactamente el
        // estado en el que está la app mientras cifra la foto y consulta el GPS.
        val programado = kotlinx.coroutines.test.StandardTestDispatcher()
        Dispatchers.setMain(programado)
        try {
            val espia = RepositorioEspia()
            val vm = CapturaViewModel(espia, visitaRepository)

            assertTrue(vm.intentarTomarElTurno())
            vm.procesarFoto("v1", byteArrayOf(1)) // encolada, aún sin ejecutarse

            assertFalse(
                "Con una captura en vuelo, el segundo toque debe rechazarse",
                vm.intentarTomarElTurno(),
            )
            assertEquals("No debe encolarse una segunda evidencia", 0, espia.capturas.get())
        } finally {
            Dispatchers.setMain(despachador)
        }
    }

    @Test
    fun `dos capturas completas y secuenciales sí producen dos evidencias`() = runTest(despachador) {
        // El complemento del anterior: el cerrojo bloquea la doble pulsación, no el uso
        // normal. Un supervisor debe poder tomar varias fotos de la misma visita.
        val espia = RepositorioEspia()
        val vm = CapturaViewModel(espia, visitaRepository)

        if (vm.intentarTomarElTurno()) vm.procesarFoto("v1", byteArrayOf(1))
        if (vm.intentarTomarElTurno()) vm.procesarFoto("v1", byteArrayOf(2))

        assertEquals(2, espia.capturas.get())
    }

    @Test
    fun `no se encola nada si no se tomó el turno`() = runTest(despachador) {
        val espia = RepositorioEspia()
        val vm = CapturaViewModel(espia, visitaRepository)

        vm.intentarTomarElTurno() // lo toma el primero
        // La pantalla respeta el rechazo y no llama a procesarFoto.
        assertFalse(vm.intentarTomarElTurno())
        assertEquals(0, espia.capturas.get())
    }

    @Test
    fun `descartar el error lo limpia sin tocar el turno`() {
        viewModel.intentarTomarElTurno()
        viewModel.liberarTurno("error temporal")
        viewModel.descartarError()

        assertEquals(null, viewModel.estado.value.error)
    }

    @Test
    fun `carga el nombre de la sede desde la cache`() = runTest(despachador) {
        viewModel.cargarSede("v1")
        coVerify { visitaRepository.detalleCacheado("v1") }
    }
}
