package com.verificlean.app.data.sync

import com.verificlean.app.core.ErrorApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La decisión de reintentar o rendirse.
 *
 * Equivocarse aquí tiene dos formas y las dos son caras: descartar evidencia válida por
 * la que un supervisor hizo el trayecto, o dejar al worker martilleando el servidor para
 * siempre. Por eso cada rama tiene su caso, incluida la del error desconocido.
 */
class ClasificadorSubidaTest {

    @Test
    fun `sin conexion se reintenta`() {
        assertTrue(ClasificadorSubida.clasificar(ErrorApp.Red) is ResultadoSubida.Transitorio)
    }

    @Test
    fun `sesion caducada se reintenta, no se descarta`() {
        // Es transitorio a propósito: la sesión se renueva al volver a abrir la app, y la
        // cola sigue ahí. Tratarlo como definitivo borraría evidencia real.
        val resultado = ClasificadorSubida.clasificar(ErrorApp.SesionCaducada)
        assertTrue(resultado is ResultadoSubida.Transitorio)
    }

    @Test
    fun `un error desconocido se reintenta por prudencia`() {
        // Ante lo que no se reconoce, la opción que no pierde datos es reintentar: en el
        // peor caso se gastan unos intentos; en el otro, se tira una evidencia real.
        val resultado = ClasificadorSubida.clasificar(ErrorApp.Desconocido("HTTP 418"))
        assertTrue(resultado is ResultadoSubida.Transitorio)
    }

    @Test
    fun `validacion es definitivo y conserva el mensaje del servidor`() {
        val resultado = ClasificadorSubida.clasificar(ErrorApp.Validacion("La foto no es un JPEG."))
        assertTrue(resultado is ResultadoSubida.Definitivo)
        assertEquals("La foto no es un JPEG.", (resultado as ResultadoSubida.Definitivo).mensaje)
    }

    @Test
    fun `conflicto es definitivo`() {
        val resultado = ClasificadorSubida.clasificar(ErrorApp.Conflicto("La visita ya está completada."))
        assertTrue(resultado is ResultadoSubida.Definitivo)
        assertEquals("La visita ya está completada.", (resultado as ResultadoSubida.Definitivo).mensaje)
    }

    @Test
    fun `visita inexistente es definitivo`() {
        assertTrue(ClasificadorSubida.clasificar(ErrorApp.NoEncontrado) is ResultadoSubida.Definitivo)
    }

    @Test
    fun `sin permiso es definitivo`() {
        assertTrue(ClasificadorSubida.clasificar(ErrorApp.Prohibido) is ResultadoSubida.Definitivo)
    }

    @Test
    fun `ningun error se queda sin clasificar`() {
        // Si mañana se añade un ErrorApp nuevo, el `when` exhaustivo del clasificador no
        // compilará. Este test cubre el otro lado: que ninguno devuelva algo inesperado.
        val todos = listOf(
            ErrorApp.Red,
            ErrorApp.SesionCaducada,
            ErrorApp.Prohibido,
            ErrorApp.NoEncontrado,
            ErrorApp.Validacion("x"),
            ErrorApp.Conflicto("x"),
            ErrorApp.Desconocido("x"),
        )
        todos.forEach { error ->
            val resultado = ClasificadorSubida.clasificar(error)
            assertTrue(
                "$error quedó sin clasificar",
                resultado is ResultadoSubida.Transitorio || resultado is ResultadoSubida.Definitivo,
            )
        }
    }

    @Test
    fun `el tope de reintentos se agota justo en MAX_INTENTOS`() {
        assertFalse(ClasificadorSubida.agotoReintentos(ClasificadorSubida.MAX_INTENTOS - 1))
        assertTrue(ClasificadorSubida.agotoReintentos(ClasificadorSubida.MAX_INTENTOS))
        assertTrue(ClasificadorSubida.agotoReintentos(ClasificadorSubida.MAX_INTENTOS + 5))
    }

    @Test
    fun `el tope reparte al menos ocho horas de ventana de reintento`() {
        // Backoff exponencial desde 30 s: 30, 60, 120, 240… La suma de los MAX_INTENTOS
        // primeros términos es la ventana real, y son horas **con red disponible**: la
        // restricción NetworkType.CONNECTED impide que el worker corra sin conexión, así
        // que estar en un sótano no consume intentos.
        var segundos = 30L
        var total = 0L
        repeat(ClasificadorSubida.MAX_INTENTOS) {
            total += segundos
            segundos *= 2
        }
        assertTrue("La ventana es de sólo ${total / 3600} h", total >= 8 * 3600)
    }
}
