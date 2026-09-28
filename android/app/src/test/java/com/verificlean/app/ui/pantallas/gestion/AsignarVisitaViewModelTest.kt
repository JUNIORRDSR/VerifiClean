package com.verificlean.app.ui.pantallas.gestion

import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.dobles.CatalogoFalso
import com.verificlean.app.domain.model.Rol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Asignación de visitas.
 *
 * El error caro aquí es asignarle una visita a alguien que ya no puede entrar a la app:
 * la visita queda viva en el panel, nadie la atiende y el reporte de cumplimiento del
 * cliente sale mal sin que nadie sepa por qué.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsignarVisitaViewModelTest {

    private val despachador = StandardTestDispatcher()
    private lateinit var catalogo: CatalogoFalso

    @Before
    fun before() {
        Dispatchers.setMain(despachador)
        catalogo = CatalogoFalso()
    }

    @After
    fun after() = Dispatchers.resetMain()

    private fun conCatalogoLleno() {
        catalogo.respuestaSitios = Resultado.Exito(listOf(CatalogoFalso.sitio()))
        catalogo.respuestaUsuarios = Resultado.Exito(listOf(CatalogoFalso.usuario()))
    }

    // ------------------------------------------------------------------ carga

    @Test
    fun `un supervisor inactivo no aparece en la lista`() = runTest(despachador) {
        catalogo.respuestaSitios = Resultado.Exito(listOf(CatalogoFalso.sitio()))
        catalogo.respuestaUsuarios = Resultado.Exito(
            listOf(
                CatalogoFalso.usuario(id = "u_activo", activo = true),
                CatalogoFalso.usuario(id = "u_baja", activo = false),
            ),
        )
        val vm = AsignarVisitaViewModel(catalogo)
        advanceUntilIdle()

        assertEquals(listOf("u_activo"), vm.estado.value.supervisores.map { it.id })
    }

    @Test
    fun `solo se piden supervisores y sedes activas`() = runTest(despachador) {
        conCatalogoLleno()
        AsignarVisitaViewModel(catalogo)
        advanceUntilIdle()

        // Sin el filtro de rol, los administradores saldrían como candidatos a hacer rondas.
        assertEquals(Rol.SUPERVISOR, catalogo.rolPedido)
        assertEquals(true, catalogo.soloActivosPedido)
    }

    @Test
    fun `las dos consultas van en paralelo`() = runTest(despachador) {
        conCatalogoLleno()
        val compuerta = CompletableDeferred<Unit>()
        catalogo.compuerta = compuerta

        AsignarVisitaViewModel(catalogo)
        advanceUntilIdle()

        // Encadenadas, el admin esperaría la suma de las dos latencias en una pantalla que
        // no puede hacer nada útil hasta tener ambas listas.
        assertEquals(2, catalogo.maximoEnVuelo)

        compuerta.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `si fallan los supervisores, las sedes cargadas se conservan`() = runTest(despachador) {
        catalogo.respuestaSitios = Resultado.Exito(listOf(CatalogoFalso.sitio()))
        catalogo.respuestaUsuarios = Resultado.Fallo(ErrorApp.Red)
        val vm = AsignarVisitaViewModel(catalogo)
        advanceUntilIdle()

        val estado = vm.estado.value
        assertNotNull(estado.errorSupervisores)
        assertEquals(1, estado.sedes.size)
        assertTrue(estado.supervisores.isEmpty())
        assertFalse("Un timeout no puede presentarse como catálogo vacío", estado.faltaCatalogo)
    }

    @Test
    fun `faltaCatalogo no se enciende mientras carga`() = runTest(despachador) {
        val compuerta = CompletableDeferred<Unit>()
        catalogo.compuerta = compuerta
        val vm = AsignarVisitaViewModel(catalogo)
        advanceUntilIdle()

        // De lo contrario la pantalla mostraría "crea una sede primero" durante el arranque,
        // sobre un catálogo que sí existe.
        assertFalse(vm.estado.value.faltaCatalogo)

        compuerta.complete(Unit)
        advanceUntilIdle()
    }

    // -------------------------------------------------------------- asignación

    @Test
    fun `sin sede o sin supervisor no se puede asignar`() = runTest(despachador) {
        conCatalogoLleno()
        val vm = AsignarVisitaViewModel(catalogo)
        advanceUntilIdle()

        assertFalse(vm.estado.value.puedeAsignar)
        vm.asignar()
        advanceUntilIdle()
        assertTrue(catalogo.asignaciones.isEmpty())

        vm.elegirSede(CatalogoFalso.sitio())
        assertFalse(vm.estado.value.puedeAsignar)
    }

    @Test
    fun `la visita se asigna a las 8 de la manana local`() = runTest(despachador) {
        conCatalogoLleno()
        val vm = AsignarVisitaViewModel(catalogo)
        advanceUntilIdle()

        val dia = LocalDate.of(2026, 3, 4)
        vm.elegirSede(CatalogoFalso.sitio(id = "site_9"))
        vm.elegirSupervisor(CatalogoFalso.usuario(id = "usr_9"))
        vm.elegirFecha(dia)
        vm.asignar()
        advanceUntilIdle()

        val asignacion = catalogo.asignaciones.single()
        assertEquals("site_9", asignacion.siteId)
        assertEquals("usr_9", asignacion.supervisorId)

        val local = asignacion.fechaAsignada.atZone(ZoneId.systemDefault())
        assertEquals(dia, local.toLocalDate())
        assertEquals(8, local.hour)
    }

    @Test
    fun `tras asignar se limpia el supervisor pero no la sede ni la fecha`() =
        runTest(despachador) {
            conCatalogoLleno()
            val vm = AsignarVisitaViewModel(catalogo)
            advanceUntilIdle()

            val dia = LocalDate.of(2026, 3, 4)
            vm.elegirSede(CatalogoFalso.sitio(id = "site_9"))
            vm.elegirSupervisor(CatalogoFalso.usuario(id = "usr_9"))
            vm.elegirFecha(dia)
            vm.asignar()
            advanceUntilIdle()

            val estado = vm.estado.value
            // Planificar una jornada es repartir la misma sede y el mismo día entre varias
            // personas; borrar los tres campos obligaría a reescribirlos cada vez.
            assertNull(estado.supervisorElegido)
            assertEquals("site_9", estado.sedeElegida?.id)
            assertEquals(dia, estado.fecha)
            assertNotNull(estado.asignada)
            assertFalse(estado.asignando)
        }

    @Test
    fun `un fallo al asignar se muestra y no dice que se asigno`() = runTest(despachador) {
        conCatalogoLleno()
        catalogo.respuestaAsignar = { Resultado.Fallo(ErrorApp.Conflicto("Ya tiene visita ese día")) }
        val vm = AsignarVisitaViewModel(catalogo)
        advanceUntilIdle()

        vm.elegirSede(CatalogoFalso.sitio())
        vm.elegirSupervisor(CatalogoFalso.usuario())
        vm.asignar()
        advanceUntilIdle()

        val estado = vm.estado.value
        assertEquals("Ya tiene visita ese día", estado.error)
        assertNull(estado.asignada)
        // La selección se conserva para poder corregir la fecha y reintentar.
        assertNotNull(estado.supervisorElegido)
    }

    @Test
    fun `un reintento conserva la clave y el exito la rota`() = runTest(despachador) {
        conCatalogoLleno()
        var intentos = 0
        catalogo.respuestaAsignar = { asignacion ->
            intentos++
            if (intentos == 1) Resultado.Fallo(ErrorApp.Red)
            else Resultado.Exito(CatalogoFalso.visitaDesde(asignacion))
        }
        val vm = AsignarVisitaViewModel(catalogo)
        advanceUntilIdle()
        vm.elegirSede(CatalogoFalso.sitio())
        vm.elegirSupervisor(CatalogoFalso.usuario())

        vm.asignar()
        advanceUntilIdle()
        val clavePrimerIntento = catalogo.asignaciones.single().claveIdempotencia
        vm.asignar()
        advanceUntilIdle()

        assertEquals(clavePrimerIntento, catalogo.asignaciones[1].claveIdempotencia)
        assertTrue(vm.estado.value.claveIdempotencia != clavePrimerIntento)
    }

    @Test
    fun `cambiar la carga despues de un fallo genera otra clave`() = runTest(despachador) {
        conCatalogoLleno()
        catalogo.respuestaAsignar = { Resultado.Fallo(ErrorApp.Red) }
        val vm = AsignarVisitaViewModel(catalogo)
        advanceUntilIdle()
        vm.elegirSede(CatalogoFalso.sitio())
        vm.elegirSupervisor(CatalogoFalso.usuario(id = "u_1"))
        vm.asignar()
        advanceUntilIdle()
        val claveOriginal = catalogo.asignaciones.single().claveIdempotencia

        vm.elegirSupervisor(CatalogoFalso.usuario(id = "u_2"))
        vm.asignar()
        advanceUntilIdle()

        assertTrue(catalogo.asignaciones[1].claveIdempotencia != claveOriginal)
    }

    @Test
    fun `el doble toque asigna una sola visita`() = runTest(despachador) {
        conCatalogoLleno()
        val vm = AsignarVisitaViewModel(catalogo)
        advanceUntilIdle()

        vm.elegirSede(CatalogoFalso.sitio())
        vm.elegirSupervisor(CatalogoFalso.usuario())

        val compuerta = CompletableDeferred<Unit>()
        catalogo.compuerta = compuerta

        vm.asignar()
        advanceUntilIdle()
        vm.asignar()
        advanceUntilIdle()

        compuerta.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, catalogo.asignaciones.size)
    }
}
