package com.verificlean.app.data.remote

import com.verificlean.app.data.local.IdDispositivo
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

private const val CABECERA_AUTORIZACION = "Authorization"
private const val CABECERA_DEVICE_ID = "X-Device-Id"

/** `true` si la ruta no lleva token: es la que lo consigue. */
private fun esRutaPublica(peticion: Request): Boolean =
    peticion.url.encodedPath.endsWith("/v1/auth/login")

/**
 * Añade `Authorization` y `X-Device-Id` a cada petición.
 *
 * El `runBlocking` es deliberado: los interceptores de OkHttp son síncronos por diseño y
 * ya corren en el hilo de red, nunca en el principal.
 */
@Singleton
class AutenticacionInterceptor @Inject constructor(
    private val proveedorToken: ProveedorToken,
    private val idDispositivo: IdDispositivo,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (esRutaPublica(original)) return chain.proceed(original)

        val token = runBlocking { proveedorToken.token() }

        val constructor = original.newBuilder()
        if (!token.isNullOrBlank()) {
            constructor.header(CABECERA_AUTORIZACION, "Bearer $token")
        }
        // El servidor compara este valor con el `deviceId` registrado del supervisor. Si
        // no coincide, marca la evidencia con DISPOSITIVO_NO_VINCULADO.
        constructor.header(CABECERA_DEVICE_ID, idDispositivo.valor())

        return chain.proceed(constructor.build())
    }
}

/**
 * Renueva el token cuando el servidor responde 401 y repite la petición **una sola vez**.
 *
 * Se usa el `authenticator` de OkHttp y no un interceptor con lógica propia de reintento
 * porque OkHttp ya lleva la cuenta de las respuestas previas de una misma llamada: contar
 * los `priorResponse` es lo que garantiza que un token que sigue siendo rechazado no
 * genere un bucle infinito de renovaciones.
 *
 * Importa especialmente para la cola offline: una evidencia puede pasar horas esperando
 * red y llegar al servidor con el token caducado. Sin este reintento, el worker gastaría
 * sus diez intentos contra un 401 que se arreglaba renovando, y acabaría marcando como
 * fallida una evidencia perfectamente válida.
 */
@Singleton
class AutenticadorDeReintento @Inject constructor(
    private val proveedorToken: ProveedorToken,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (esRutaPublica(response.request)) return null

        // Un solo reintento. Si el token nuevo también recibe 401, el problema no es la
        // caducidad y volver a renovar no lo va a arreglar.
        if (response.priorResponse != null) return null

        val tokenUsado = response.request.header(CABECERA_AUTORIZACION)
            ?.removePrefix("Bearer ")
            ?.trim()

        val nuevo = runBlocking { proveedorToken.refrescarTrasRechazo(tokenUsado) }
            ?: return null // sin sesión que renovar: se deja pasar el 401

        if (nuevo == tokenUsado) return null // no cambió nada; repetir sería inútil

        return response.request.newBuilder()
            .header(CABECERA_AUTORIZACION, "Bearer $nuevo")
            .build()
    }
}
