package com.verificlean.app.core

/**
 * Resultado de una operación que puede fallar de forma esperada.
 *
 * Se usa esto y no `kotlin.Result` porque `Result` sólo transporta un `Throwable`, y la
 * UI necesita distinguir "sin conexión" (se reintenta solo, mensaje tranquilo) de
 * "sesión caducada" (hay que volver al login) de "el servidor rechazó los datos"
 * (hay que mostrar qué campo). Esa diferencia no cabe en una excepción sin hacer
 * `when` sobre tipos de excepción, que es peor.
 */
sealed interface Resultado<out T> {
    data class Exito<T>(val datos: T) : Resultado<T>
    data class Fallo(val error: ErrorApp) : Resultado<Nothing>
}

inline fun <T, R> Resultado<T>.map(transformar: (T) -> R): Resultado<R> = when (this) {
    is Resultado.Exito -> Resultado.Exito(transformar(datos))
    is Resultado.Fallo -> this
}

inline fun <T> Resultado<T>.alExito(accion: (T) -> Unit): Resultado<T> {
    if (this is Resultado.Exito) accion(datos)
    return this
}

inline fun <T> Resultado<T>.alFallo(accion: (ErrorApp) -> Unit): Resultado<T> {
    if (this is Resultado.Fallo) accion(error)
    return this
}

fun <T> Resultado<T>.datosONulo(): T? = (this as? Resultado.Exito)?.datos

/**
 * Errores que la UI sabe manejar.
 *
 * `Red` se separa a propósito del resto: es el único que **no** es culpa de nadie y que
 * se resuelve solo. En una app cuyo caso de uso es "un supervisor en un sótano sin
 * señal", tratarlo como un error genérico rompería la promesa de trabajar sin conexión.
 */
sealed interface ErrorApp {
    /** Sin conexión o timeout. Cada pantalla decide si puede reintentar. */
    data object Red : ErrorApp

    /** Token ausente o caducado: hay que volver al login. */
    data object SesionCaducada : ErrorApp

    /** El rol no alcanza para esta operación. */
    data object Prohibido : ErrorApp

    data object NoEncontrado : ErrorApp

    /** El servidor rechazó los datos. `porCampo` viene del envelope de la API. */
    data class Validacion(val mensaje: String, val porCampo: Map<String, String> = emptyMap()) : ErrorApp

    /** Conflicto de estado: visita ya cerrada, checklist incompleto, etc. */
    data class Conflicto(val mensaje: String) : ErrorApp

    data class Desconocido(val mensaje: String) : ErrorApp
}

/** Texto por defecto. Las pantallas pueden dar uno más específico. */
fun ErrorApp.mensajeLegible(): String = when (this) {
    ErrorApp.Red -> "No se pudo conectar. Revisa la red y vuelve a intentarlo."
    ErrorApp.SesionCaducada -> "Tu sesión expiró. Vuelve a iniciar sesión."
    ErrorApp.Prohibido -> "No tienes permiso para esta acción."
    ErrorApp.NoEncontrado -> "No se encontró lo que buscabas."
    is ErrorApp.Validacion -> mensaje
    is ErrorApp.Conflicto -> mensaje
    is ErrorApp.Desconocido -> mensaje
}
