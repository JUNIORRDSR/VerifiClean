package com.verificlean.app.data.sync

import com.verificlean.app.core.ErrorApp

/**
 * Decide si un fallo de subida se reintenta o se da por perdido.
 *
 * Es la decisión más delicada de toda la app y por eso vive aparte, como función pura:
 * equivocarse tiene dos formas y las dos son malas. Marcar como definitivo algo
 * transitorio **descarta evidencia válida** por la que un supervisor hizo el trayecto.
 * Marcar como transitorio algo definitivo deja al worker martilleando el servidor con una
 * petición que nunca va a pasar, gastando batería y datos.
 */
sealed interface ResultadoSubida {
    data object Subida : ResultadoSubida

    /** Se reintentará con backoff. La fila y el archivo se conservan intactos. */
    data class Transitorio(val mensaje: String) : ResultadoSubida

    /**
     * No se arregla reintentando. La fila se marca `FALLIDA` pero **no se borra**: el
     * supervisor tiene que poder ver que algo quedó sin subir, en vez de que desaparezca.
     */
    data class Definitivo(val mensaje: String) : ResultadoSubida
}

object ClasificadorSubida {

    fun clasificar(error: ErrorApp): ResultadoSubida = when (error) {
        // Red, timeout, 5xx y 429: el servidor no dijo que no, es que no llegó la
        // petición o no pudo atenderla ahora.
        ErrorApp.Red -> ResultadoSubida.Transitorio("Sin conexión con el servidor.")

        // Token caducado también es transitorio. Al volver a abrir la app se renueva la
        // sesión y la cola sigue ahí; darlo por perdido borraría evidencia válida por un
        // problema que se resuelve solo.
        ErrorApp.SesionCaducada -> ResultadoSubida.Transitorio("Sesión expirada; se reintentará.")

        // El servidor rechazó los datos. Reintentar con los mismos bytes dará lo mismo.
        is ErrorApp.Validacion -> ResultadoSubida.Definitivo(error.mensaje)
        is ErrorApp.Conflicto -> ResultadoSubida.Definitivo(error.mensaje)
        ErrorApp.NoEncontrado -> ResultadoSubida.Definitivo("La visita ya no existe.")
        ErrorApp.Prohibido -> ResultadoSubida.Definitivo("Sin permiso sobre esta visita.")

        // Ante lo que no se reconoce, se reintenta. Es la opción que no pierde datos: en
        // el peor caso se gastan unos reintentos; en el otro, se tira una evidencia real.
        is ErrorApp.Desconocido -> ResultadoSubida.Transitorio(
            "Error no reconocido (${error.mensaje}); se reintentará.",
        )
    }

    /**
     * Tope de reintentos automáticos antes de rendirse.
     *
     * Con el backoff exponencial desde 30 s que configura `ProgramadorSincronizacion`, 10
     * intentos reparten unas **8,5 horas** de ventana (30·(2¹⁰−1) ≈ 30 700 s).
     *
     * Y esas 8,5 h son de *tiempo con red*, no de reloj: el trabajo lleva la restricción
     * `NetworkType.CONNECTED`, así que WorkManager ni siquiera lo ejecuta mientras el
     * supervisor está en un sótano sin señal. Estar desconectado no gasta intentos. El
     * tope sólo se agota si hay red y aun así falla — es decir, ante una caída larga del
     * servidor, y para eso 8,5 h sobran.
     *
     * Pasado el tope la evidencia queda `FALLIDA` y visible, y el supervisor puede forzar
     * un reintento manual, que reinicia el contador.
     */
    const val MAX_INTENTOS = 10

    fun agotoReintentos(intentos: Int): Boolean = intentos >= MAX_INTENTOS
}
