package com.verificlean.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encola la subida de evidencias.
 *
 * ## Por qué `APPEND_OR_REPLACE` y no `KEEP`
 *
 * Con `KEEP`, encolar mientras ya existe un trabajo no terminado **descarta la solicitud
 * nueva**. Eso abre una carrera perdedora: si una captura encola justo cuando el worker
 * está terminando su última consulta, la petición se descarta, el worker devuelve éxito
 * sin haber visto la fila recién insertada, y esa evidencia se queda en la base sin
 * ningún trabajo futuro que la suba. Se quedaría ahí hasta que el usuario capturara otra
 * cosa — o para siempre.
 *
 * `APPEND_OR_REPLACE` encadena la nueva ejecución detrás de la que está corriendo, sin
 * cancelarla a mitad de una transferencia. Cuesta alguna ejecución de más que no encuentra
 * nada que hacer y termina en milisegundos; a cambio, ninguna fila se queda huérfana.
 */
@Singleton
class ProgramadorSincronizacion @Inject constructor(
    @ApplicationContext private val contexto: Context,
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(contexto)

    fun encolarSincronizacion(expedito: Boolean = false) {
        val restricciones = Constraints.Builder()
            // Única restricción: que haya red. No se exige carga ni batería alta — una
            // evidencia que espera a estar enchufado puede tardar horas, y el supervisor
            // necesita que su ronda quede registrada hoy.
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val peticion = OneTimeWorkRequestBuilder<SincronizarEvidenciasWorker>()
            .setConstraints(restricciones)
            // Backoff exponencial desde 30 s. Con un servidor caído, los reintentos se
            // espacian solos en vez de martillearlo desde cada dispositivo a la vez.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .apply {
                // Expedito sólo cuando el usuario pulsa "reintentar": tiene cuota limitada
                // por app y gastarla en cada captura la agotaría justo cuando de verdad
                // hace falta.
                if (expedito) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .addTag(ETIQUETA)
            .build()

        workManager.enqueueUniqueWork(TRABAJO_UNICO, ExistingWorkPolicy.APPEND_OR_REPLACE, peticion)
    }

    /**
     * Red de seguridad contra filas que quedaron sin trabajo asociado.
     *
     * Se llama al arrancar la app. Cubre los casos que ninguna política de encolado
     * arregla: el sistema canceló el trabajo por presión de recursos, el usuario forzó la
     * detención de la app, o una fila quedó en `PREPARANDO` porque el proceso murió a
     * mitad del enriquecimiento. Es barato — si no hay nada pendiente, el worker termina
     * en milisegundos.
     */
    fun reconciliarAlArrancar() = encolarSincronizacion()

    /** Cancela la cola. Sólo al cerrar sesión de forma explícita. */
    fun cancelar() {
        workManager.cancelUniqueWork(TRABAJO_UNICO)
    }

    companion object {
        const val TRABAJO_UNICO = "verificlean-sync-evidencias"
        const val ETIQUETA = "sync-evidencias"
    }
}
