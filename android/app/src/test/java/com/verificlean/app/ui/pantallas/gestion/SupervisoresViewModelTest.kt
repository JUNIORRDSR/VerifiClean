package com.verificlean.app.ui.pantallas.gestion

import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.dobles.CatalogoFalso
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.repository.SesionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * Alta de supervisores.
 *
 * Dos cosas concretas que ya han salido mal en formularios así: el 409 del servidor
 * llegando a la pantalla como "Conflicto" —que no le dice al admin qué corregir— y el
 * doble toque en Guardar creando dos usuarios con el mismo correo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupervisoresViewModelTest {

    private val despachador = StandardTestDispatcher()
    private lateinit var catalogo: CatalogoFalso

    @Before
    fun before() {
        Dispatchers.setMain(despachador)
        catalogo = CatalogoFalso()
    }

    @After
    fun after() = Dispatchers.resetMain()

    private fun formularioValido() = FormularioUsuario(
        nombre = "Diana Pérez",
        email = "diana@verificlean.test",
        password = "clave-larga-1",
    )

    private fun nuevoVm(usuarioId: String = "u_admin") = SupervisoresViewModel(
        catalogo,
        object : SesionRepository {
            override val usuarioEnSesion: Flow<Usuario?> = MutableStateFlow(
                CatalogoFalso.usuario(id = usuarioId, rol = Rol.ADMIN),
            )

            override suspend fun iniciarSesion(email: String, password: String) =
                Resultado.Exito(CatalogoFalso.usuario(id = usuarioId, rol = Rol.ADMIN))

            override suspend fun cerrarSesion() = Unit
        },
    )

    // ------------------------------------------------------------- validación

    @Test
    fun `la contrasena exige 8 caracteres, igual que el servidor`() {
        assertFalse(FormularioUsuario(password = "1234567").passwordValida)
        assertTrue(FormularioUsuario(password = "12345678").passwordValida)
    }

    @Test
    fun `un campo vacio no muestra error todavia`() {
        val vacio = FormularioUsuario()
        assertNull(vacio.errorPassword)
        assertNull(vacio.errorEmail)
        assertFalse(vacio.puedeGuardar)
    }

    @Test
    fun `un correo sin arroba muestra error`() {
        assertNotNull(FormularioUsuario(email = "diana.verificlean.test").errorEmail)
        assertNull(FormularioUsuario(email = "d@x.co").errorEmail)
    }

    @Test
    fun `un nombre de una letra no basta`() {
        assertFalse(formularioValido().copy(nombre = "D").puedeGuardar)
        assertFalse(formularioValido().copy(nombre = "  ").puedeGuardar)
        assertTrue(formularioValido().puedeGuardar)
    }

    // ------------------------------------------------------------------ carga

    @Test
    fun `los supervisores van antes que los administradores`() = runTest(despachador) {
        catalogo.respuestaUsuarios = Resultado.Exito(
            listOf(
                CatalogoFalso.usuario(id = "u_admin", rol = Rol.ADMIN),
                CatalogoFalso.usuario(id = "u_sup", rol = Rol.SUPERVISOR),
            ),
        )
        val vm = nuevoVm()
        advanceUntilIdle()

        // El admin gestiona supervisores a diario y a sí mismo casi nunca.
        assertEquals(listOf("u_sup", "u_admin"), vm.estado.value.usuarios.map { it.id })
        assertFalse(vm.estado.value.cargando)
    }

    @Test
    fun `un fallo de red deja mensaje y quita el cargando`() = runTest(despachador) {
        catalogo.respuestaUsuarios = Resultado.Fallo(ErrorApp.Red)
        val vm = nuevoVm()
        advanceUntilIdle()

        assertFalse(vm.estado.value.cargando)
        assertNotNull(vm.estado.value.error)
    }

    // --------------------------------------------------------------- guardado

    @Test
    fun `un correo repetido se explica, no se reporta como conflicto generico`() =
        runTest(despachador) {
            catalogo.respuestaCrearUsuario = { Resultado.Fallo(ErrorApp.Conflicto("Conflicto")) }
            val vm = nuevoVm()
            advanceUntilIdle()

            vm.editarFormulario { formularioValido() }
            vm.guardar()
            advanceUntilIdle()

            assertEquals("Ya existe un usuario con ese correo.", vm.estado.value.error)
            assertFalse(vm.estado.value.guardando)
        }

    @Test
    fun `el doble toque en guardar crea un solo usuario`() = runTest(despachador) {
        val vm = nuevoVm()
        advanceUntilIdle()

        vm.editarFormulario { formularioValido() }

        // La compuerta reproduce la ventana real: la petición está en vuelo y todavía no
        // ha respondido cuando llega el segundo toque.
        val compuerta = CompletableDeferred<Unit>()
        catalogo.compuerta = compuerta

        vm.guardar()
        advanceUntilIdle()
        assertTrue("El estado debe seguir en guardando", vm.estado.value.guardando)

        vm.guardar()
        advanceUntilIdle()

        compuerta.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, catalogo.usuariosCreados.size)
    }

    @Test
    fun `un formulario invalido no llega al servidor`() = runTest(despachador) {
        val vm = nuevoVm()
        advanceUntilIdle()

        vm.editarFormulario { formularioValido().copy(password = "corta") }
        vm.guardar()
        advanceUntilIdle()

        assertTrue(catalogo.usuariosCreados.isEmpty())
    }

    @Test
    fun `al crear, el usuario aparece arriba y el aviso recuerda no mandar la clave por chat`() =
        runTest(despachador) {
            catalogo.respuestaUsuarios = Resultado.Exito(listOf(CatalogoFalso.usuario(id = "u_viejo")))
            val vm = nuevoVm()
            advanceUntilIdle()

            vm.editarFormulario { formularioValido() }
            vm.guardar()
            advanceUntilIdle()

            val estado = vm.estado.value
            assertEquals(listOf("usr_nuevo", "u_viejo"), estado.usuarios.map { it.id })
            assertFalse("El formulario se cierra al crear", estado.formularioAbierto)
            // La contraseña la escribe el admin y el supervisor la recibe por fuera de la
            // app; el aviso es lo único que evita que acabe en un grupo de WhatsApp.
            assertTrue(estado.mensaje!!.contains("canal seguro"))
        }

    @Test
    fun `abrir el formulario descarta lo escrito antes`() = runTest(despachador) {
        val vm = nuevoVm()
        advanceUntilIdle()

        vm.editarFormulario { formularioValido() }
        vm.cerrarFormulario()
        vm.abrirFormulario()

        // Sin esto, la contraseña del supervisor anterior seguiría en pantalla.
        assertEquals(FormularioUsuario(), vm.estado.value.formulario)
    }
}
