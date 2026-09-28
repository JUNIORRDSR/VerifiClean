package com.verificlean.app.capture

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El autenticador de desarrollo tiene que producir exactamente el formato de token que
 * el backend acepta con `AUTH_MODE=fake` (`dev:<uid>`). Si se desalinean, la app deja de
 * poder hablar con el backend local y el fallo aparece como un 401 opaco.
 */
class AutenticadorFalsoTest {

    private val autenticador = AutenticadorFalso()

    @Test
    fun `traduce los correos del seed al uid correspondiente`() = runTest {
        assertEquals("dev:u_admin", autenticador.iniciarSesion("admin@verificlean.test", "x"))
        assertEquals("dev:u_sup_1", autenticador.iniciarSesion("carlos@verificlean.test", "x"))
        assertEquals("dev:u_sup_2", autenticador.iniciarSesion("diana@verificlean.test", "x"))
    }

    @Test
    fun `ignora mayusculas y espacios sobrantes`() = runTest {
        assertEquals("dev:u_admin", autenticador.iniciarSesion("  Admin@VerifiClean.test  ", "x"))
    }

    @Test
    fun `acepta un uid escrito directamente para probar usuarios fuera del seed`() = runTest {
        assertEquals("dev:u_otro_supervisor", autenticador.iniciarSesion("u_otro_supervisor", "x"))
    }

    @Test
    fun `rechaza un correo desconocido que no parece uid`() = runTest {
        assertNull(autenticador.iniciarSesion("cualquiera@ejemplo.com", "x"))
    }

    @Test
    fun `rechaza credenciales vacias`() = runTest {
        assertNull(autenticador.iniciarSesion("", "x"))
        assertNull(autenticador.iniciarSesion("admin@verificlean.test", ""))
    }

    @Test
    fun `el token queda disponible tras entrar y desaparece al salir`() = runTest {
        autenticador.iniciarSesion("carlos@verificlean.test", "x")
        assertEquals("dev:u_sup_1", autenticador.tokenVigente())

        autenticador.cerrarSesion()
        assertNull(autenticador.tokenVigente())
    }
}
