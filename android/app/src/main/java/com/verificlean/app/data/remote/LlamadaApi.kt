package com.verificlean.app.data.remote

import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.data.remote.dto.ErrorEnvelopeDto
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Traductor único de respuestas HTTP a `Resultado`.
 *
 * Existe para que ningún repositorio tenga que repetir el `try/catch` + `isSuccessful` +
 * parseo del envelope de error. Y sobre todo, para que la distinción entre "no hay red"
 * y "el servidor dijo que no" se decida en un solo sitio: de esa distinción depende que
 * WorkManager reintente o se rinda.
 */

private val jsonPermisivo = Json { ignoreUnknownKeys = true }

suspend fun <T> llamadaApi(bloque: suspend () -> Response<T>): Resultado<T> =
    try {
        val respuesta = bloque()
        val cuerpo = respuesta.body()
        if (respuesta.isSuccessful && cuerpo != null) {
            Resultado.Exito(cuerpo)
        } else {
            Resultado.Fallo(aErrorApp(respuesta))
        }
    } catch (e: UnknownHostException) {
        // DNS no resuelve: en la práctica, sin conexión.
        Resultado.Fallo(ErrorApp.Red)
    } catch (e: SocketTimeoutException) {
        Resultado.Fallo(ErrorApp.Red)
    } catch (e: IOException) {
        // Cualquier otro corte de transporte. `IOException` es la señal que OkHttp usa
        // para "no llegó al servidor", que es exactamente lo que hay que reintentar.
        Resultado.Fallo(ErrorApp.Red)
    } catch (e: Exception) {
        Resultado.Fallo(ErrorApp.Desconocido(e.message ?: "Error inesperado"))
    }

fun <T> aErrorApp(respuesta: Response<T>): ErrorApp {
    val detalle = leerEnvelope(respuesta)

    return when (respuesta.code()) {
        401 -> ErrorApp.SesionCaducada
        403 -> ErrorApp.Prohibido
        404 -> ErrorApp.NoEncontrado
        400, 422 -> ErrorApp.Validacion(
            detalle?.mensaje ?: "Datos inválidos.",
            detalle?.detallesComoTexto().orEmpty(),
        )
        409 -> ErrorApp.Conflicto(detalle?.mensaje ?: "La operación entra en conflicto con el estado actual.")
        413 -> ErrorApp.Validacion("La foto es demasiado grande.")
        // 5xx y 429 se tratan como fallo de red, no de datos: son transitorios y la
        // respuesta correcta es reintentar con backoff, igual que ante un corte.
        in 500..599, 429 -> ErrorApp.Red
        else -> ErrorApp.Desconocido(detalle?.mensaje ?: "HTTP ${respuesta.code()}")
    }
}

private fun <T> leerEnvelope(respuesta: Response<T>) = try {
    respuesta.errorBody()?.string()?.takeIf { it.isNotBlank() }?.let {
        jsonPermisivo.decodeFromString<ErrorEnvelopeDto>(it).error
    }
} catch (e: Exception) {
    // Un cuerpo de error que no es el envelope esperado (un HTML de proxy, por
    // ejemplo) no debe convertir un 403 legible en un crash.
    null
}

/**
 * `true` si vale la pena reintentar. Lo consulta el Worker de sincronización para
 * decidir entre `Result.retry()` y marcar la evidencia como fallida.
 */
fun ErrorApp.esTransitorio(): Boolean = this is ErrorApp.Red
