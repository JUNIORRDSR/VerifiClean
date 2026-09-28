package com.verificlean.app.ui.pantallas.reportes

import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.dobles.CatalogoFalso
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
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reportes de cumplimiento por cliente.
 *
 * Dos riesgos propios de esta pantalla: la lista de clientes no viene del servidor —se
 * deriva agrupando sedes— y el reporte se relanza al tocar cualquier filtro, así que una
 * consulta lenta puede aterrizar después de otra más nueva y enseñarle al admin las cifras
 * del cliente equivocado bajo el nombre correcto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportesViewModelTest {

    private val despachador = StandardTestDispatcher()
    private lateinit var catalogo: CatalogoFalso

    @Before
    fun before() {
        Dispatchers.setMain(despachador)
        catalogo = CatalogoFalso()
    }

    @After
    fun after() = Dispatchers.resetMain()

    private fun conSedes(vararg sedes: Pair<String, String>) {
        catalogo.respuestaSitios = Resultado.Exito(
            sedes.mapIndexed { i, (clienteId, nombreCliente) ->
                CatalogoFalso.sitio(
                    id = "site_$i",
                    clienteId = clienteId,
                    nombreCliente = nombreCliente,
                    nombreSede = "Sede $i",
                )
            },
        )
    }

    // ------------------------------------------------------ derivación de clientes

    @Test
    fun `los clientes salen de agrupar las sedes, ordenados por nombre`() = runTest(despachador) {
        conSedes(
            "cli_z" to "Zeta S.A.",
            "cli_a" to "Alfa Ltda.",
            "cli_a" to "Alfa Ltda.",
        )
        val vm = ReportesViewModel(catalogo)
        advanceUntilIdle()

        val clientes = vm.estado.value.clientes
        assertEquals(listOf("Alfa Ltda.", "Zeta S.A."), clientes.map { it.nombreCliente })
        assertEquals(2, clientes.first().sedes)
        assertEquals(1, clientes.last().sedes)
        assertFalse(vm.estado.value.cargandoClientes)
    }

    @Test
    fun `se piden todas las sedes, tambien las inactivas`() = runTest(despachador) {
        conSedes("cli_a" to "Alfa Ltda.")
        ReportesViewModel(catalogo)
        advanceUntilIdle()

        // Un cliente cuyas sedes se dieron de baja este mes sigue teniendo historial que
        // reportar; filtrarlo aquí lo borraría del desplegable.
        assertEquals(false, catalogo.soloActivosPedido)
    }

    @Test
    fun `con un solo cliente se elige solo y ya trae el reporte`() = runTest(despachador) {
        conSedes("cli_a" to "Alfa Ltda.")
        val vm = ReportesViewModel(catalogo)
        advanceUntilIdle()

        assertEquals("cli_a", vm.estado.value.clienteElegido?.clienteId)
        assertEquals(listOf("cli_a", "cli_a"), catalogo.reportesPedidos.map { it.clienteId })
        assertNotNull(vm.estado.value.reporte)
        assertNotNull(vm.estado.value.reporteAnterior)
    }

    @Test
    fun `con varios clientes no se elige ninguno ni se consulta nada`() = runTest(despachador) {
        conSedes("cli_a" to "Alfa Ltda.", "cli_b" to "Beta Ltda.")
        val vm = ReportesViewModel(catalogo)
        advanceUntilIdle()

        // Elegir por el admin sería inventarse cuál le interesa.
        assertNull(vm.estado.value.clienteElegido)
        assertTrue(catalogo.reportesPedidos.isEmpty())
    }

    @Test
    fun `sin sedes no hay clientes ni error`() = runTest(despachador) {
        val vm = ReportesViewModel(catalogo)
        advanceUntilIdle()

        assertTrue(vm.estado.value.clientes.isEmpty())
        assertNull(vm.estado.value.error)
    }

    @Test
    fun `un fallo cargando sedes se muestra`() = runTest(despachador) {
        catalogo.respuestaSitios = Resultado.Fallo(ErrorApp.Red)
        val vm = ReportesViewModel(catalogo)
        advanceUntilIdle()

        assertFalse(vm.estado.value.cargandoClientes)
        assertNotNull(vm.estado.value.error)
    }

    // ------------------------------------------------------------ rango y consulta

    @Test
    fun `generar sin cliente no llama al servidor`() = runTest(despachador) {
        conSedes("cli_a" to "Alfa Ltda.", "cli_b" to "Beta Ltda.")
        val vm = ReportesViewModel(catalogo)
        advanceUntilIdle()

        vm.generar()
        advanceUntilIdle()

        assertTrue(catalogo.reportesPedidos.isEmpty())
    }

    @Test
    fun `una semana son siete fechas naturales contando hoy, no ocho`() = runTest(despachador) {
        conSedes("cli_a" to "Alfa Ltda.")
        val vm = ReportesViewModel(catalogo)
        advanceUntilIdle()

        vm.elegirRango(RangoReporte.ULTIMA_SEMANA)
        advanceUntilIdle()

        val peticion = catalogo.reportesPedidos.takeLast(2).first()
        val zona = ZoneId.systemDefault()
        // Los dos extremos son inclusivos en el servidor. Con `minusDays(7)` el reporte
        // abarcaba ocho días bajo la etiqueta "última semana", y los totales no cuadraban
        // con el período que decía cubrir.
        assertEquals(
            LocalDate.now().minusDays(6),
            peticion.desde.atZone(zona).toLocalDate(),
        )
        // El "hasta" llega al final del día de hoy: cortar a la hora actual dejaría fuera
        // las visitas de esta misma tarde.
        assertEquals(LocalDate.now(), peticion.hasta.atZone(zona).toLocalDate())
        assertTrue(Duration.between(peticion.desde, peticion.hasta).toDays() < 7)
    }

    @Test
    fun `el periodo anterior tiene la misma cantidad de fechas y no se solapa`() =
        runTest(despachador) {
            conSedes("cli_a" to "Alfa Ltda.")
            ReportesViewModel(catalogo)
            advanceUntilIdle()

            val (actual, anterior) = catalogo.reportesPedidos.takeLast(2)
            val zona = ZoneId.systemDefault()
            assertEquals(
                actual.desde.atZone(zona).toLocalDate().minusDays(1),
                anterior.hasta.atZone(zona).toLocalDate(),
            )
            assertEquals(
                Duration.between(actual.desde, actual.hasta).toDays(),
                Duration.between(anterior.desde, anterior.hasta).toDays(),
            )
        }

    @Test
    fun `nombres divergentes bloquean un reporte facturable`() = runTest(despachador) {
        conSedes("cli_a" to "Alfa Ltda.", "cli_a" to "Alfa Servicios")
        val vm = ReportesViewModel(catalogo)
        advanceUntilIdle()

        assertTrue(vm.estado.value.clientes.isEmpty())
        assertNotNull(vm.estado.value.error)
        assertTrue(catalogo.reportesPedidos.isEmpty())
    }

    @Test
    fun `el reporte viejo desaparece al empezar una consulta nueva`() = runTest(despachador) {
        conSedes("cli_a" to "Alfa Ltda.")
        val vm = ReportesViewModel(catalogo)
        advanceUntilIdle()
        assertNotNull("Precondición: hay un reporte cargado", vm.estado.value.reporte)

        val compuerta = CompletableDeferred<Unit>()
        catalogo.compuerta = compuerta
        vm.elegirRango(RangoReporte.ULTIMO_TRIMESTRE)
        advanceUntilIdle()

        // Durante la espera no puede haber cifras en pantalla: serían las del rango
        // anterior debajo del chip del rango nuevo, y de aquí sale una factura.
        assertNull(vm.estado.value.reporte)
        assertTrue(vm.estado.value.cargandoReporte)

        compuerta.complete(Unit)
        advanceUntilIdle()
        assertNotNull(vm.estado.value.reporte)
    }

    @Test
    fun `una consulta vieja que llega tarde no pisa a la nueva`() = runTest(despachador) {
        conSedes("cli_a" to "Alfa Ltda.", "cli_b" to "Beta Ltda.")
        val vm = ReportesViewModel(catalogo)
        advanceUntilIdle()

        val clientes = vm.estado.value.clientes
        val alfa = clientes.first { it.clienteId == "cli_a" }
        val beta = clientes.first { it.clienteId == "cli_b" }

        val compuertaAlfa = CompletableDeferred<Unit>()
        catalogo.compuerta = compuertaAlfa
        vm.elegirCliente(alfa)
        advanceUntilIdle()

        val compuertaBeta = CompletableDeferred<Unit>()
        catalogo.compuerta = compuertaBeta
        vm.elegirCliente(beta)
        advanceUntilIdle()

        // Beta responde primero; Alfa después. Sin cancelar el trabajo anterior, el panel
        // acabaría enseñando las cifras de Alfa con "Beta Ltda." en el encabezado.
        compuertaBeta.complete(Unit)
        advanceUntilIdle()
        compuertaAlfa.complete(Unit)
        advanceUntilIdle()

        assertEquals("cli_b", vm.estado.value.reporte?.clienteId)
        assertEquals("cli_b", vm.estado.value.clienteElegido?.clienteId)
        assertFalse(vm.estado.value.cargandoReporte)
    }

    @Test
    fun `el rango demasiado grande usa el mensaje del servidor y borra el reporte viejo`() =
        runTest(despachador) {
            conSedes("cli_a" to "Alfa Ltda.")
            val vm = ReportesViewModel(catalogo)
            advanceUntilIdle()
            assertNotNull("Precondición: hay un reporte cargado", vm.estado.value.reporte)

            val mensaje = "El rango supera 5000 visitas. Acorta las fechas."
            catalogo.respuestaReporte = { Resultado.Fallo(ErrorApp.Validacion(mensaje)) }
            vm.elegirRango(RangoReporte.ULTIMO_TRIMESTRE)
            advanceUntilIdle()

            val estado = vm.estado.value
            assertEquals(mensaje, estado.error)
            // Dejar el reporte del mes anterior en pantalla junto al error haría creer que
            // esas cifras corresponden al trimestre pedido.
            assertNull(estado.reporte)
            assertFalse(estado.cargandoReporte)
        }
}
