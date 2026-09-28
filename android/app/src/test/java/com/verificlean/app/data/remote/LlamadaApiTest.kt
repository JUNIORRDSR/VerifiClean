package com.verificlean.app.data.remote

import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * La clasificación de errores decide si WorkManager reintenta o se rinde. Equivocarse
 * aquí tiene dos formas: reintentar para siempre algo que nunca va a pasar, o descartar
 * una evidencia que sólo necesitaba red. Las dos se testean.
 */
class LlamadaApiTest {

    private val tipoJson = "application/json".toMediaType()

    private fun errorConEnvelope(codigo: Int, cuerpo: String): Response<String> =
        Response.error(codigo, cuerpo.toResponseBody(tipoJson))

    @Test
    fun `una respuesta correcta devuelve los datos`() = runTest {
        val resultado = llamadaApi { Response.success("ok") }
        assertEquals(Resultado.Exito("ok"), resultado)
    }

    @Test
    fun `401 se traduce a sesion caducada`() = runTest {
        val resultado = llamadaApi {
            errorConEnvelope(401, """{"error":{"codigo":"NO_AUTORIZADO","mensaje":"Token inválido"}}""")
        }
        assertEquals(Resultado.Fallo(ErrorApp.SesionCaducada), resultado)
    }

    @Test
    fun `403 se traduce a prohibido`() = runTest {
        val resultado = llamadaApi {
            errorConEnvelope(403, """{"error":{"codigo":"PROHIBIDO","mensaje":"Sin permiso"}}""")
        }
        assertEquals(Resultado.Fallo(ErrorApp.Prohibido), resultado)
    }

    @Test
    fun `400 conserva el mensaje y los detalles por campo`() = runTest {
        val resultado = llamadaApi {
            errorConEnvelope(
                400,
                """{"error":{"codigo":"SOLICITUD_INVALIDA","mensaje":"Campos inválidos","detalles":{"lat":"fuera de rango"}}}""",
            )
        }
        val error = (resultado as Resultado.Fallo).error as ErrorApp.Validacion
        assertEquals("Campos inválidos", error.mensaje)
        assertEquals("fuera de rango", error.porCampo["lat"])
    }

    @Test
    fun `409 se traduce a conflicto con su mensaje`() = runTest {
        val resultado = llamadaApi {
            errorConEnvelope(409, """{"error":{"codigo":"CONFLICTO","mensaje":"La visita ya está completada."}}""")
        }
        val error = (resultado as Resultado.Fallo).error as ErrorApp.Conflicto
        assertEquals("La visita ya está completada.", error.mensaje)
    }

    @Test
    fun `500 se trata como red para que se reintente`() = runTest {
        val resultado = llamadaApi { errorConEnvelope(500, "") }
        assertEquals(Resultado.Fallo(ErrorApp.Red), resultado)
        assertTrue((resultado as Resultado.Fallo).error.esTransitorio())
    }

    @Test
    fun `429 tambien se reintenta`() = runTest {
        val resultado = llamadaApi { errorConEnvelope(429, "") }
        assertEquals(Resultado.Fallo(ErrorApp.Red), resultado)
    }

    @Test
    fun `un cuerpo de error que no es el envelope no provoca crash`() = runTest {
        // Un proxy o un balanceador puede devolver HTML en vez del JSON de la API.
        val resultado = llamadaApi { errorConEnvelope(403, "<html><body>Forbidden</body></html>") }
        assertEquals(Resultado.Fallo(ErrorApp.Prohibido), resultado)
    }

    @Test
    fun `los cortes de transporte se clasifican como red`() = runTest {
        listOf(
            UnknownHostException("sin DNS"),
            SocketTimeoutException("timeout"),
            IOException("conexión perdida"),
        ).forEach { excepcion ->
            val resultado = llamadaApi<String> { throw excepcion }
            assertEquals(
                "$excepcion debería clasificarse como red",
                Resultado.Fallo(ErrorApp.Red),
                resultado,
            )
        }
    }

    @Test
    fun `solo los errores de red son transitorios`() {
        assertTrue(ErrorApp.Red.esTransitorio())
        assertTrue(!ErrorApp.Prohibido.esTransitorio())
        assertTrue(!ErrorApp.Conflicto("x").esTransitorio())
        assertTrue(!ErrorApp.Validacion("x").esTransitorio())
    }
}
