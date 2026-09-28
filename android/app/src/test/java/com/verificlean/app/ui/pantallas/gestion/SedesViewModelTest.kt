package com.verificlean.app.ui.pantallas.gestion

import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.dobles.CatalogoFalso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SedesViewModelTest {

    private val despachador = StandardTestDispatcher()
    private lateinit var catalogo: CatalogoFalso

    @Before
    fun before() {
        Dispatchers.setMain(despachador)
        catalogo = CatalogoFalso()
    }

    @After
    fun after() = Dispatchers.resetMain()

    private fun formularioValido(f: FormularioSede) = f.copy(
        clienteId = "cli_nuevo",
        nombreCliente = "Cliente Nuevo",
        nombreSede = "Sede Norte",
        direccion = "Cra 7 #100-20",
        lat = "4.686",
        lng = "-74.045",
    )

    @Test
    fun `un reintento conserva la clave y el exito la rota`() = runTest(despachador) {
        var intentos = 0
        catalogo.respuestaCrearSitio = { nuevo ->
            intentos++
            if (intentos == 1) Resultado.Fallo(ErrorApp.Red)
            else Resultado.Exito(CatalogoFalso.sitioDesde(nuevo))
        }
        val vm = SedesViewModel(catalogo)
        advanceUntilIdle()
        vm.abrirFormulario()
        vm.editarFormulario(::formularioValido)

        vm.guardar()
        advanceUntilIdle()
        val clavePrimerIntento = catalogo.clavesCrearSitio.single()
        vm.guardar()
        advanceUntilIdle()

        assertEquals(clavePrimerIntento, catalogo.clavesCrearSitio[1])
        assertTrue(vm.estado.value.formulario.claveIdempotencia != clavePrimerIntento)
    }

    @Test
    fun `editar la carga genera otra clave`() = runTest(despachador) {
        catalogo.respuestaCrearSitio = { Resultado.Fallo(ErrorApp.Red) }
        val vm = SedesViewModel(catalogo)
        advanceUntilIdle()
        vm.abrirFormulario()
        vm.editarFormulario(::formularioValido)
        vm.guardar()
        advanceUntilIdle()
        val claveOriginal = catalogo.clavesCrearSitio.single()

        vm.editarFormulario { it.copy(nombreSede = "Sede Sur") }
        vm.guardar()
        advanceUntilIdle()

        assertTrue(catalogo.clavesCrearSitio[1] != claveOriginal)
    }

    @Test
    fun `crear de nuevo un cliente existente dirige al selector`() = runTest(despachador) {
        catalogo.respuestaSitios = Resultado.Exito(
            listOf(CatalogoFalso.sitio(nombreCliente = "Bogotá Servicios")),
        )
        val vm = SedesViewModel(catalogo)
        advanceUntilIdle()
        vm.abrirFormulario()
        vm.crearClienteNuevo()
        vm.editarFormulario {
            formularioValido(it).copy(
                clienteNuevo = true,
                nombreCliente = "BOGOTA SERVICIOS",
            )
        }

        vm.guardar()
        advanceUntilIdle()

        assertTrue(catalogo.clavesCrearSitio.isEmpty())
        assertTrue(vm.estado.value.formulario.errorNombreCliente.orEmpty().contains("ya existe"))
    }
}
