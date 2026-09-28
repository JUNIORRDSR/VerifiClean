package com.verificlean.app.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.verificlean.app.core.Resultado
import com.verificlean.app.data.local.AlmacenEvidenciaCifrada
import com.verificlean.app.data.local.EvidenciaPendienteDao
import com.verificlean.app.data.local.EvidenciaPendienteEntity
import com.verificlean.app.data.local.SesionStore
import com.verificlean.app.data.remote.VerifiCleanApi
import com.verificlean.app.data.remote.llamadaApi
import com.verificlean.app.domain.model.EstadoSincronizacion
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/**
 * Sube las evidencias en cola.
 *
 * Diseñado alrededor de una idea: **nunca perder una foto**. De ahí las reglas que
 * gobiernan el flujo:
 *
 * 1. La fila y el archivo cifrado sólo se borran tras un 2xx confirmado.
 * 2. Un fallo transitorio devuelve `retry()` y deja todo intacto para que WorkManager
 *    reintente con backoff exponencial.
 * 3. Un fallo definitivo marca la fila como `FALLIDA` pero **no la borra**: el supervisor
 *    tiene que poder verla y reintentarla a mano.
 * 4. Tras `MAX_INTENTOS`, un fallo transitorio pasa a `FALLIDA`. Sin ese tope, una
 *    evidencia que el servidor nunca va a aceptar por una causa que parece de red dejaría
 *    al worker despertándose para siempre.
 * 5. Sólo se sube la cola **del supervisor en sesión**. La cola sobrevive al logout a
 *    propósito, pero subir la evidencia de otro con el token nuevo produciría un 403 y la
 *    marcaría definitivamente fallida — destruyendo trabajo válido de un compañero.
 */
@HiltWorker
class SincronizarEvidenciasWorker @AssistedInject constructor(
    @Assisted contexto: Context,
    @Assisted parametros: WorkerParameters,
    private val dao: EvidenciaPendienteDao,
    private val almacen: AlmacenEvidenciaCifrada,
    private val api: VerifiCleanApi,
    private val sesionStore: SesionStore,
    private val programador: ProgramadorSincronizacion,
) : CoroutineWorker(contexto, parametros) {

    override suspend fun doWork(): Result {
        promoverPreparandoAbandonadas()

        val ownerUid = sesionStore.usuario.first()?.id
        if (ownerUid == null) {
            // Sin sesión no se puede subir nada, pero la cola se conserva: cuando el
            // supervisor vuelva a entrar, `reconciliarAlArrancar` la retoma.
            Log.i(TAG, "Sin sesión activa; la cola queda a la espera")
            return Result.success()
        }

        val pendientes = dao.siguientesParaSubir(ownerUid, LOTE)
        if (pendientes.isEmpty()) {
            // La fase durable responde a la UI antes de pedir GPS e integridad. Si esta
            // ejecución gana la carrera y encuentra una fila todavía PREPARANDO, no debe
            // terminar para siempre: un retry conserva una red de seguridad por si Android
            // mata el proceso antes de que el enriquecimiento la pase a PENDIENTE.
            if (dao.contarPreparando(ownerUid) > 0) return Result.retry()
            limpiarHuerfanos()
            return Result.success()
        }

        for (fila in pendientes) {
            when (val resultado = subir(fila)) {
                is ResultadoSubida.Subida -> {
                    dao.borrar(fila.clienteEvidenciaId)
                    almacen.borrar(fila.rutaArchivoCifrado)
                }

                is ResultadoSubida.Transitorio -> {
                    val intentos = fila.intentos + 1
                    if (ClasificadorSubida.agotoReintentos(intentos)) {
                        Log.w(TAG, "Evidencia ${fila.clienteEvidenciaId} agotó reintentos")
                        marcar(fila, EstadoSincronizacion.FALLIDA, intentos, "Agotados los reintentos: ${resultado.mensaje}")
                        continue
                    }
                    marcar(fila, EstadoSincronizacion.PENDIENTE, intentos, resultado.mensaje)
                    // Se corta el lote: si no hay red para esta, tampoco la habrá para las
                    // siguientes, y seguir sólo gasta batería. `retry()` aplica el backoff.
                    return Result.retry()
                }

                is ResultadoSubida.Definitivo -> {
                    Log.w(TAG, "Evidencia rechazada sin remedio: ${resultado.mensaje}")
                    marcar(fila, EstadoSincronizacion.FALLIDA, fila.intentos + 1, resultado.mensaje)
                }
            }
        }

        // Quedan más de las que cabían en el lote. Se encadena una ejecución nueva en vez
        // de devolver `retry()`: `retry()` arrastra el backoff exponencial y sube el
        // contador de intentos del WorkRequest, así que una cola de treinta fotos acabaría
        // esperando horas entre lotes con la red perfectamente disponible. La ejecución
        // encadenada empieza con el contador a cero.
        if (dao.siguientesParaSubir(ownerUid, 1).isNotEmpty()) {
            programador.encolarSincronizacion()
            return Result.success()
        }

        limpiarHuerfanos()
        return Result.success()
    }

    /**
     * Recupera capturas que quedaron a medio enriquecer.
     *
     * Una fila `PREPARANDO` significa que la foto está cifrada en disco pero el proceso
     * murió antes de resolver ubicación e integridad. Se sube tal cual: sin ubicación el
     * servidor la marcará, y una evidencia marcada y visible vale mucho más que una foto
     * que se queda para siempre en un estado que nadie procesa.
     *
     * El margen de antigüedad evita pisar una captura que ahora mismo está esperando el
     * GPS de forma legítima.
     */
    private suspend fun promoverPreparandoAbandonadas() {
        val limite = System.currentTimeMillis() - MARGEN_PREPARANDO_MS
        val abandonadas = dao.preparandoAbandonadas(limite)
        for (fila in abandonadas) {
            Log.w(TAG, "Promoviendo evidencia ${fila.clienteEvidenciaId} que quedó en PREPARANDO")
            dao.cambiarEstado(fila.clienteEvidenciaId, EstadoSincronizacion.PENDIENTE.name)
        }
    }

    private suspend fun marcar(
        fila: EvidenciaPendienteEntity,
        estado: EstadoSincronizacion,
        intentos: Int,
        error: String?,
    ) = dao.actualizarEstado(fila.clienteEvidenciaId, estado.name, intentos, error)

    private suspend fun subir(fila: EvidenciaPendienteEntity): ResultadoSubida {
        // El id va como AAD del cifrado: si el archivo fue manipulado o intercambiado con
        // el de otra evidencia, GCM lo detecta y esto devuelve null.
        val jpeg = almacen.leer(fila.clienteEvidenciaId, fila.rutaArchivoCifrado)
            ?: return ResultadoSubida.Definitivo(
                "El archivo cifrado no está, fue manipulado, o no corresponde a esta evidencia.",
            )

        val parteFoto = MultipartBody.Part.createFormData(
            "foto",
            "${fila.clienteEvidenciaId}.jpg",
            jpeg.toRequestBody(TIPO_JPEG),
        )

        val respuesta = llamadaApi {
            api.subirEvidencia(
                visitId = fila.visitId,
                foto = parteFoto,
                clienteEvidenciaId = texto(fila.clienteEvidenciaId),
                // Sin ubicación NO se manda 0,0: el servidor calculaba entonces 8.241 km
                // —del golfo de Guinea a Bogotá— y el panel del admin lo mostraba como una
                // medición real. Se omiten los campos y el servidor marca SIN_UBICACION,
                // que es la verdad: no prueba presencia, pero tampoco la desmiente.
                lat = fila.lat?.let { texto(it.toString()) },
                lng = fila.lng?.let { texto(it.toString()) },
                precisionMetros = fila.precisionMetros?.let { texto(it.toString()) },
                mockLocationReportada = texto(fila.mockLocationReportada.toString()),
                // El servidor no decide nada con esto; lo guarda sólo como auditoría. El
                // timestamp que cuenta lo pone él al recibir la petición.
                capturadoEn = texto(Instant.ofEpochMilli(fila.capturadoEnMillis).toString()),
                hashClienteReportado = texto(fila.hashSHA256),
                integrityToken = fila.integrityToken?.let { texto(it) },
                checklist = texto(fila.checklistJson),
            )
        }

        // El servidor responde 201 la primera vez y 200 ante un reenvío idempotente. Los
        // dos significan "está registrada", así que el worker los trata igual.
        return when (respuesta) {
            is Resultado.Exito -> ResultadoSubida.Subida
            is Resultado.Fallo -> ClasificadorSubida.clasificar(respuesta.error)
        }
    }

    private suspend fun limpiarHuerfanos() {
        val vigentes = dao.todasLasRutas().toSet()
        val borrados = almacen.limpiarHuerfanos(vigentes)
        if (borrados > 0) Log.i(TAG, "Limpiados $borrados archivos cifrados huérfanos")
    }

    private fun texto(valor: String): RequestBody = valor.toRequestBody(TIPO_TEXTO)

    companion object {
        private const val TAG = "SyncEvidencias"

        /** Fotos por ejecución. Con 8 MB de tope cada una, más sería arriesgar el límite. */
        const val LOTE = 5

        /** Antes de este margen, una fila `PREPARANDO` puede estar esperando el GPS. */
        const val MARGEN_PREPARANDO_MS = 2 * 60 * 1000L

        private val TIPO_JPEG = "image/jpeg".toMediaType()
        private val TIPO_TEXTO = "text/plain".toMediaType()
    }
}
