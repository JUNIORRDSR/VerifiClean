package com.verificlean.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.verificlean.app.data.sync.ProgramadorSincronizacion
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * WorkManager se configura a mano (y su inicializador automático está desactivado en el
 * manifiesto) porque `SincronizarEvidenciasWorker` recibe dependencias por Hilt. La
 * fábrica tiene que estar registrada antes de que WorkManager instancie el primer
 * worker; con la inicialización automática ese orden no está garantizado y el worker
 * falla al construirse.
 */
@HiltAndroidApp
class VerifiCleanApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var programadorSincronizacion: ProgramadorSincronizacion

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Red de seguridad para filas que quedaron sin trabajo asociado: el sistema pudo
        // cancelar el trabajo por presión de recursos, el usuario pudo forzar la detención
        // de la app, o una captura pudo quedarse en PREPARANDO porque el proceso murió a
        // mitad. Sin esto, esas evidencias esperarían a que alguien capturara otra cosa.
        // Es barato: si no hay nada pendiente el worker termina en milisegundos.
        programadorSincronizacion.reconciliarAlArrancar()
    }
}
