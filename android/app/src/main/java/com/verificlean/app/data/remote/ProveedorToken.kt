package com.verificlean.app.data.remote

import com.verificlean.app.capture.Autenticador
import com.verificlean.app.data.local.SesionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Único sitio del que sale el token que viaja en `Authorization`.
 *
 * ## El bug que esto arregla
 *
 * Antes el interceptor leía el token persistido en `SesionStore` y lo mandaba tal cual.
 * Un ID token de Firebase **caduca en una hora**. Pasada esa hora, todas las peticiones
 * respondían 401; el worker de sincronización clasifica el 401 como transitorio y
 * reintenta, pero como nadie renovaba nada, los diez reintentos se agotaban contra el
 * mismo token muerto y la evidencia terminaba `FALLIDA`. Es decir: dejar la app cerrada
 * una hora bastaba para perder la ronda de un supervisor.
 *
 * ## Cómo funciona ahora
 *
 * Se le pide el token al `Autenticador`, que en el caso de Firebase renueva solo cuando
 * quedan menos de cinco minutos de vigencia. El valor persistido queda como respaldo para
 * el arranque en frío: WorkManager puede despertar el proceso horas después sin que nadie
 * haya abierto una pantalla, y en ese momento el SDK de Firebase todavía no ha restaurado
 * su sesión en memoria.
 */
@Singleton
class ProveedorToken @Inject constructor(
    private val autenticador: Autenticador,
    private val sesionStore: SesionStore,
) {
    /**
     * Serializa los refrescos. Sin esto, cinco subidas en paralelo que reciben 401 a la
     * vez dispararían cinco renovaciones simultáneas del mismo token.
     */
    private val cerrojo = Mutex()

    /** Token vigente para la siguiente petición. */
    suspend fun token(): String? = cerrojo.withLock { resolver(forzarRefresco = false) }

    /**
     * Renueva tras un 401 y devuelve el nuevo token, o `null` si ya no hay sesión que
     * renovar. Lo llama el `Authenticator` de OkHttp una única vez por petición.
     */
    suspend fun refrescarTrasRechazo(tokenUsado: String?): String? = cerrojo.withLock {
        val actual = sesionStore.token()
        // Otra petición en paralelo ya lo renovó mientras esperábamos el cerrojo: se
        // aprovecha en vez de pedir otro.
        if (actual != null && actual != tokenUsado) return@withLock actual
        resolver(forzarRefresco = true)
    }

    private suspend fun resolver(forzarRefresco: Boolean): String? {
        val delAutenticador = autenticador.tokenVigente(forzarRefresco)
        if (delAutenticador != null) {
            // Se persiste para que el próximo arranque en frío del worker tenga algo con
            // lo que empezar aunque el SDK aún no haya restaurado la sesión.
            sesionStore.guardarTokenProvisional(delAutenticador)
            return delAutenticador
        }
        // Sin autenticador disponible —proceso recién despertado por WorkManager— se usa
        // el último token conocido. Si está caducado, el 401 disparará el refresco.
        return if (forzarRefresco) null else sesionStore.token()
    }
}
