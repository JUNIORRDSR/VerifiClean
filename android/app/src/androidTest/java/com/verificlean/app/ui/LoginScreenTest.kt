package com.verificlean.app.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.repository.SesionRepository
import com.verificlean.app.ui.pantallas.login.LoginScreen
import com.verificlean.app.ui.pantallas.login.LoginViewModel
import com.verificlean.app.ui.theme.VerifiCleanTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pantalla de login, renderizada de verdad en el emulador.
 *
 * Se construye el ViewModel a mano con un repositorio falso en vez de pasar por Hilt: lo
 * que se prueba aquí es la UI, y montar el grafo de inyección sólo añadiría partes que
 * pueden fallar por motivos ajenos a lo que se quiere verificar.
 *
 * Correr con:  ./gradlew connectedDebugAndroidTest
 */
class LoginScreenTest {

    @get:Rule val compose = createComposeRule()

    private class SesionFalsa(
        private val resultado: Resultado<Usuario> = Resultado.Exito(
            Usuario("u1", "Carlos", "c@x.test", Rol.SUPERVISOR, activo = true),
        ),
    ) : SesionRepository {
        val intentos = AtomicInteger(0)
        var ultimoEmail: String? = null

        override val usuarioEnSesion: Flow<Usuario?> = MutableStateFlow(null)

        override suspend fun iniciarSesion(email: String, password: String): Resultado<Usuario> {
            intentos.incrementAndGet()
            ultimoEmail = email
            return resultado
        }

        override suspend fun cerrarSesion() = Unit
    }

    private fun montar(sesion: SesionRepository): LoginViewModel {
        val vm = LoginViewModel(sesion)
        compose.setContent { VerifiCleanTheme { LoginScreen(viewModel = vm) } }
        return vm
    }

    @Test
    fun muestraTituloYCamposAlAbrir() {
        montar(SesionFalsa())

        compose.onNodeWithText("VerifiClean").assertExists()
        compose.onNodeWithText("Correo").assertExists()
        compose.onNodeWithText("Contraseña").assertExists()
        compose.onNodeWithText("Entrar").assertExists()
    }

    @Test
    fun elBotonEstaApagadoHastaQueHayCorreoYContrasena() {
        montar(SesionFalsa())

        // Es la protección más barata contra una petición que el servidor rechazaría.
        compose.onNodeWithText("Entrar").assertIsNotEnabled()

        compose.onNodeWithText("Correo").performTextInput("carlos@verificlean.test")
        compose.onNodeWithText("Entrar").assertIsNotEnabled()

        compose.onNodeWithText("Contraseña").performTextInput("secreta")
        compose.onNodeWithText("Entrar").assertIsEnabled()
    }

    @Test
    fun pulsarEntrarEnviaLasCredencialesTalComoSeEscribieron() {
        val sesion = SesionFalsa()
        montar(sesion)

        compose.onNodeWithText("Correo").performTextInput("  Carlos@VerifiClean.test  ")
        compose.onNodeWithText("Contraseña").performTextInput("secreta")
        compose.onNodeWithText("Entrar").performClick()
        compose.waitForIdle()

        assertEquals(1, sesion.intentos.get())
        // El ViewModel recorta espacios; normalizar mayúsculas es cosa del servidor.
        assertEquals("Carlos@VerifiClean.test", sesion.ultimoEmail)
    }

    @Test
    fun unaCredencialInvalidaMuestraElMensajeYNoNavega() {
        val sesion = SesionFalsa(
            Resultado.Fallo(ErrorApp.Validacion("Correo o contraseña incorrectos.")),
        )
        montar(sesion)

        compose.onNodeWithText("Correo").performTextInput("quien@sea.test")
        compose.onNodeWithText("Contraseña").performTextInput("mal")
        compose.onNodeWithText("Entrar").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Correo o contraseña incorrectos.").assertExists()
        // Y la pantalla sigue ahí, permitiendo corregir.
        compose.onNodeWithText("Entrar").assertExists()
    }

    @Test
    fun unUsuarioInactivoRecibeUnMensajeEspecificoNoUnGenerico() {
        // El backend responde 403 a un usuario desactivado. "No tienes permiso" no le
        // diría nada útil a un supervisor al que dieron de baja.
        val sesion = SesionFalsa(Resultado.Fallo(ErrorApp.Prohibido))
        montar(sesion)

        compose.onNodeWithText("Correo").performTextInput("pedro@verificlean.test")
        compose.onNodeWithText("Contraseña").performTextInput("x")
        compose.onNodeWithText("Entrar").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Tu usuario está inactivo o no está dado de alta.").assertExists()
    }

    @Test
    fun escribirDeNuevoLimpiaElErrorAnterior() {
        val sesion = SesionFalsa(Resultado.Fallo(ErrorApp.Validacion("Correo o contraseña incorrectos.")))
        montar(sesion)

        compose.onNodeWithText("Correo").performTextInput("a@b.test")
        compose.onNodeWithText("Contraseña").performTextInput("x")
        compose.onNodeWithText("Entrar").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Correo o contraseña incorrectos.").assertExists()

        compose.onNodeWithText("a@b.test").performTextInput("c")
        compose.waitForIdle()

        compose.onNodeWithText("Correo o contraseña incorrectos.").assertDoesNotExist()
    }

    /** Login que no responde hasta que el test lo suelta: modela la petición en vuelo. */
    private class SesionLenta : SesionRepository {
        val intentos = AtomicInteger(0)
        private val puerta = CompletableDeferred<Unit>()

        override val usuarioEnSesion: Flow<Usuario?> = MutableStateFlow(null)

        override suspend fun iniciarSesion(email: String, password: String): Resultado<Usuario> {
            intentos.incrementAndGet()
            puerta.await()
            return Resultado.Exito(Usuario("u1", "Carlos", "c@x.test", Rol.SUPERVISOR, true))
        }

        fun responder() = puerta.complete(Unit)

        override suspend fun cerrarSesion() = Unit
    }

    @Test
    fun elGuardDelViewModelIgnoraOtroEnvioConElLoginEnVuelo() {
        // La UI ya hace imposible el segundo toque —el botón desaparece mientras carga—,
        // así que intentar pulsarlo dos veces falla por mecánica de test, no por lógica.
        // Lo que sí importa verificar es el guard de debajo: aunque algo llame a `entrar()`
        // otra vez (una recomposición, un evento de accesibilidad, un cambio futuro en la
        // pantalla), no debe salir una segunda petición.
        val sesion = SesionLenta()
        val vm = montar(sesion)

        compose.onNodeWithText("Correo").performTextInput("carlos@verificlean.test")
        compose.onNodeWithText("Contraseña").performTextInput("secreta")
        compose.onNodeWithText("Entrar").performClick()
        compose.waitForIdle()

        // Petición en vuelo. Cualquier reintento debe rebotar contra `puedeEnviar`.
        repeat(3) { vm.entrar() }
        compose.waitForIdle()

        assertEquals("Con el login en vuelo sólo debe salir una petición", 1, sesion.intentos.get())

        sesion.responder()
        compose.waitForIdle()
    }

    @Test
    fun elBotonSeApagaMientrasElLoginEstaEnVuelo() {
        val sesion = SesionLenta()
        montar(sesion)

        compose.onNodeWithText("Correo").performTextInput("carlos@verificlean.test")
        compose.onNodeWithText("Contraseña").performTextInput("secreta")
        compose.onNodeWithText("Entrar").assertIsEnabled()

        compose.onNodeWithText("Entrar").performClick()
        compose.waitForIdle()

        // Mientras carga, el texto del botón se reemplaza por el indicador de progreso.
        compose.onNodeWithText("Entrar").assertDoesNotExist()

        sesion.responder()
        compose.waitForIdle()
    }
}
