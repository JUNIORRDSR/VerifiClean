package com.verificlean.app.capture

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Token de Play Integrity para que el servidor verifique el dispositivo.
 *
 * La app **no** interpreta el veredicto: pide el token y lo reenvía. Quien lo descifra y
 * decide es el backend. Si la app lo evaluara, un APK modificado simplemente diría
 * siempre "todo bien" — y un APK modificado es justo lo que hay que detectar.
 */
interface ProveedorIntegridad {
    /**
     * Token opaco, o `null` si no se pudo obtener.
     *
     * `nonce` liga el token a una evidencia concreta —visita, id de evidencia y hash de la
     * foto— para que un token legítimo obtenido antes no pueda reutilizarse en otra
     * subida. El servidor recalcula el mismo valor y lo compara.
     */
    suspend fun token(nonce: String): String?
}

@Singleton
class ProveedorIntegridadPlay @Inject constructor(
    @ApplicationContext private val contexto: Context,
    private val numeroProyectoCloud: Long,
) : ProveedorIntegridad {

    private val gestor by lazy { IntegrityManagerFactory.create(contexto) }

    override suspend fun token(nonce: String): String? = conTiempoLimiteIntegridad {
        suspendCancellableCoroutine { continuacion ->
            gestor
                .requestIntegrityToken(
                    IntegrityTokenRequest.builder()
                        .setNonce(nonce)
                        .setCloudProjectNumber(numeroProyectoCloud)
                        .build(),
                )
                .addOnSuccessListener {
                    if (continuacion.isActive) continuacion.resume(it.token())
                }
                .addOnFailureListener { error ->
                    // Sin Play Services, sin red o con cuota agotada. La evidencia se
                    // sube igual; perder la foto por esto sería mucho peor.
                    Log.w(TAG, "No se pudo obtener token de Play Integrity", error)
                    if (continuacion.isActive) continuacion.resume(null)
                }
        }
    }

    private companion object {
        const val TAG = "ProveedorIntegridad"
    }
}

/**
 * Evita que una tarea de Play Services que no complete deje la evidencia eternamente en
 * PREPARANDO. El resultado nulo sigue exactamente el mismo camino visual que un token
 * disponible; el supervisor nunca conoce esta señal.
 */
internal suspend fun <T> conTiempoLimiteIntegridad(
    tiempoMaximoMs: Long = 10_000L,
    bloque: suspend () -> T?,
): T? = withTimeoutOrNull(tiempoMaximoMs) { bloque() }

/**
 * Modo desarrollo. Play Integrity necesita un número de proyecto de Google Cloud y una
 * app publicada en Play; en un build de depuración la llamada real siempre falla.
 *
 * Devuelve el mismo token que entiende el backend con `INTEGRITY_MODE=fake`, así que la
 * ruta completa —app, red, servidor, reglas antifraude— se puede probar sin GCP.
 * Cambia `tokenSimulado` a `"fake:no_confiable"` para ver la alerta de dispositivo
 * comprometido en el panel del admin.
 */
@Singleton
class ProveedorIntegridadFalso @Inject constructor() : ProveedorIntegridad {
    /**
     * Valores que entiende el backend en `INTEGRITY_MODE=fake`: `fake:confiable`,
     * `fake:no_confiable`, `fake:no_evaluado`, `fake:nonce_malo`, `fake:caducado`.
     */
    var tokenSimulado: String = "fake:confiable"

    // El nonce se ignora: el backend falso no lo verifica, precisamente para que el modo
    // desarrollo no dependa de que las dos implementaciones del cálculo coincidan. Esa
    // coincidencia la comprueba un test dedicado.
    override suspend fun token(nonce: String): String = tokenSimulado
}
