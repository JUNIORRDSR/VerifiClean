package com.verificlean.app.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Ubicación puntual en el momento de disparar la foto.
 *
 * Tres decisiones de consumo, en orden de impacto:
 *
 * 1. **`getCurrentLocation`, no `requestLocationUpdates`.** Una lectura y el sensor se
 *    apaga. Un flujo de actualizaciones dejaría el GPS despierto todo el rato que la
 *    pantalla de captura esté abierta.
 * 2. **`PRIORITY_BALANCED_POWER_ACCURACY`.** Da 30-60 m usando wifi y torres antes que
 *    GPS puro. Para una geocerca de 150 m sobra, y `HIGH_ACCURACY` multiplica el gasto
 *    por encender el receptor GNSS en cada captura.
 * 3. **Nada en segundo plano.** No hay servicio ni geofencing del sistema: fuera de esta
 *    llamada, la app no toca la ubicación.
 */
@Singleton
class ProveedorUbicacion @Inject constructor(
    @ApplicationContext private val contexto: Context,
) {

    private val cliente by lazy { LocationServices.getFusedLocationProviderClient(contexto) }

    fun tienePermiso(): Boolean =
        ContextCompat.checkSelfPermission(contexto, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(contexto, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Devuelve la posición actual, o `null` si no hay permiso o el sensor no responde
     * a tiempo. Nunca lanza: quedarse sin ubicación no puede impedir tomar la foto —
     * el servidor marcará la evidencia, que es lo correcto.
     */
    /**
     * Devuelve la posición, o `null` si de verdad no se pudo obtener.
     *
     * Intenta tres cosas antes de rendirse, en orden de coste creciente. La razón es que
     * una evidencia sin ubicación queda marcada, y marcar a un supervisor honesto porque
     * el wifi tardó dos segundos de más convierte el panel de alertas en ruido:
     *
     * 1. **Balanceada**, que es la barata: wifi y torres antes que GNSS.
     * 2. **Última conocida**, si la hay y es reciente. El supervisor lleva el teléfono
     *    encima; una lectura de hace un minuto sigue situándolo en el mismo edificio.
     * 3. **Alta precisión**, encendiendo el receptor GNSS. Sólo si las dos anteriores
     *    fallaron — que es justo cuando gastar batería está justificado.
     *
     * Nunca lanza: quedarse sin ubicación no puede impedir tomar la foto. El servidor la
     * marcará `SIN_UBICACION`, y eso es información honesta, no una acusación.
     */
    suspend fun ubicacionActual(): UbicacionCapturada? {
        if (!tienePermiso()) return null

        return try {
            actual(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                ?: ultimaConocidaReciente()
                ?: actual(Priority.PRIORITY_HIGH_ACCURACY)
        } catch (e: SecurityException) {
            // El permiso se revocó entre la comprobación y la llamada.
            null
        }
    }

    @Suppress("MissingPermission") // el permiso se comprueba en `ubicacionActual`
    private suspend fun actual(prioridad: Int): UbicacionCapturada? {
        val peticion = CurrentLocationRequest.Builder()
            .setPriority(prioridad)
            // Una lectura de hasta 10 s de antigüedad vale: el supervisor no se movió
            // 150 m en ese tiempo, y reutilizarla evita despertar el sensor.
            .setMaxUpdateAgeMillis(10_000)
            .setDurationMillis(TIEMPO_MAXIMO_MS)
            .build()

        val cancelacion = CancellationTokenSource()
        val localizacion = suspendCancellableCoroutine<Location?> { continuacion ->
            cliente.getCurrentLocation(peticion, cancelacion.token)
                .addOnSuccessListener { continuacion.resume(it) }
                .addOnFailureListener { continuacion.resume(null) }
            continuacion.invokeOnCancellation { cancelacion.cancel() }
        }
        return localizacion?.let(::aCapturada)
    }

    @Suppress("MissingPermission")
    private suspend fun ultimaConocidaReciente(): UbicacionCapturada? {
        val localizacion = suspendCancellableCoroutine<Location?> { continuacion ->
            cliente.lastLocation
                .addOnSuccessListener { continuacion.resume(it) }
                .addOnFailureListener { continuacion.resume(null) }
        } ?: return null

        // Se descarta si es vieja: una lectura de hace media hora puede situar al
        // supervisor en la sede anterior, y eso sí sería un dato engañoso.
        val antiguedad = System.currentTimeMillis() - localizacion.time
        if (antiguedad > ANTIGUEDAD_MAXIMA_MS) return null

        return aCapturada(localizacion)
    }

    private fun aCapturada(localizacion: Location) = UbicacionCapturada(
        lat = localizacion.latitude,
        lng = localizacion.longitude,
        precisionMetros = if (localizacion.hasAccuracy()) localizacion.accuracy.toDouble() else null,
        esSimulada = esSimulada(localizacion),
    )

    /**
     * Detecta ubicación simulada.
     *
     * `isMock` (API 31+) reemplazó a `isFromMockProvider`, deprecado pero funcional en
     * versiones anteriores. Se consultan ambos según la versión porque la señal es
     * demasiado valiosa como para perderla en la mitad del parque de dispositivos: una
     * app de GPS falso es el ataque más barato contra este producto.
     */
    private fun esSimulada(localizacion: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            localizacion.isMock
        } else {
            @Suppress("DEPRECATION")
            localizacion.isFromMockProvider
        }

    private companion object {
        /**
         * Más allá de esto no se espera por intento: la foto ya está tomada y el usuario
         * mirando. Con los tres intentos, el peor caso son ~16 s antes de rendirse.
         */
        const val TIEMPO_MAXIMO_MS = 8_000L

        /**
         * Antigüedad máxima de una lectura reutilizable. Dos minutos cubren el trayecto
         * del portal al piso doce sin llegar a situar al supervisor en la sede anterior.
         */
        const val ANTIGUEDAD_MAXIMA_MS = 2 * 60 * 1000L
    }
}

data class UbicacionCapturada(
    val lat: Double,
    val lng: Double,
    val precisionMetros: Double?,
    val esSimulada: Boolean,
)
