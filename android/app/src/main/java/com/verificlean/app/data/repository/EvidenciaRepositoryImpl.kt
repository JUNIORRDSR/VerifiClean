package com.verificlean.app.data.repository

import android.util.Log
import com.verificlean.app.capture.ProveedorIntegridad
import com.verificlean.app.capture.ProveedorUbicacion
import com.verificlean.app.core.DispatcherIo
import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.ScopeAplicacion
import com.verificlean.app.data.local.AlmacenEvidenciaCifrada
import com.verificlean.app.data.local.EvidenciaPendienteDao
import com.verificlean.app.data.local.EvidenciaPendienteEntity
import com.verificlean.app.data.local.SesionStore
import com.verificlean.app.data.local.VisitaDao
import com.verificlean.app.data.local.aDominio
import com.verificlean.app.data.local.serializarChecklist
import com.verificlean.app.data.sync.ProgramadorSincronizacion
import com.verificlean.app.domain.model.EstadoSincronizacion
import com.verificlean.app.domain.model.EvidenciaPendiente
import com.verificlean.app.domain.repository.EntradaEvidencia
import com.verificlean.app.domain.repository.EvidenciaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cola de evidencias.
 *
 * ## El protocolo de dos fases, y por qué existe
 *
 * La captura no es una operación: son dos, y entre ellas hay segundos en los que se pide
 * el GPS y un token a Play Integrity.
 *
 * En la versión anterior el JPEG vivía en memoria durante esa espera. Si el usuario
 * pulsaba atrás, el sistema mataba el proceso o la pantalla se recreaba, la foto
 * desaparecía sin dejar rastro — y el supervisor había hecho el trayecto para nada. En una
 * app cuya única promesa es "nunca perder una evidencia", esa ventana era el peor bug
 * posible.
 *
 * Ahora:
 *
 * 1. **Fase durable.** Se cifra la foto en disco y se inserta la fila en `PREPARANDO`.
 *    Desde este instante la evidencia es recuperable pase lo que pase.
 * 2. **Fase de enriquecimiento.** Se resuelven ubicación e integridad y se completa la
 *    fila, que pasa a `PENDIENTE`.
 *
 * Si el proceso muere entre las dos, el worker encuentra la fila `PREPARANDO` vieja y la
 * promueve tal cual: se subirá sin ubicación y el servidor la marcará. Una evidencia
 * marcada y visible vale infinitamente más que una foto perdida.
 */
@Singleton
class EvidenciaRepositoryImpl @Inject constructor(
    private val dao: EvidenciaPendienteDao,
    private val visitaDao: VisitaDao,
    private val almacen: AlmacenEvidenciaCifrada,
    private val programador: ProgramadorSincronizacion,
    private val proveedorUbicacion: ProveedorUbicacion,
    private val proveedorIntegridad: ProveedorIntegridad,
    private val sesionStore: SesionStore,
    @DispatcherIo private val io: CoroutineDispatcher,
    @ScopeAplicacion private val scopeAplicacion: CoroutineScope,
) : EvidenciaRepository {

    override suspend fun capturarEvidencia(entrada: EntradaEvidencia): Resultado<String> =
        withContext(io) {
            if (entrada.jpeg.isEmpty()) {
                return@withContext Resultado.Fallo(ErrorApp.Validacion("La foto llegó vacía."))
            }

            val ownerUid = sesionStore.usuario.first()?.id
                ?: return@withContext Resultado.Fallo(ErrorApp.SesionCaducada)

            // Este UUID es también la clave de idempotencia que viaja al servidor, y entra
            // en el nonce del token de integridad.
            val clienteEvidenciaId = UUID.randomUUID().toString()
            val hash = sha256(entrada.jpeg)

            val ruta = try {
                almacen.guardar(clienteEvidenciaId, entrada.jpeg)
            } catch (e: Exception) {
                return@withContext Resultado.Fallo(
                    ErrorApp.Desconocido("No se pudo guardar la foto en el dispositivo: ${e.message}"),
                )
            }

            try {
                dao.insertar(
                    EvidenciaPendienteEntity(
                        clienteEvidenciaId = clienteEvidenciaId,
                        ownerUid = ownerUid,
                        visitId = entrada.visitId,
                        visitaNombreSede = entrada.visitaNombreSede,
                        rutaArchivoCifrado = ruta,
                        hashSHA256 = hash,
                        lat = null,
                        lng = null,
                        precisionMetros = null,
                        mockLocationReportada = false,
                        integrityToken = null,
                        capturadoEnMillis = System.currentTimeMillis(),
                        checklistJson = serializarChecklist(entrada.checklist),
                        estado = EstadoSincronizacion.PREPARANDO.name,
                        intentos = 0,
                        ultimoError = null,
                    ),
                )
            } catch (e: Exception) {
                // Sin fila no hay quien suba el archivo, así que aquí sí se borra: es un
                // huérfano de verdad, no una foto viva. Distinto del caso anterior, en el
                // que se borraba la única copia por un fallo posterior recuperable.
                almacen.borrar(ruta)
                return@withContext Resultado.Fallo(
                    ErrorApp.Desconocido("No se pudo encolar la evidencia: ${e.message}"),
                )
            }

            // ---- A partir de aquí la foto ya está a salvo. Nada de lo que siga la pierde.
            // El contador local y el trabajo de recuperación se registran antes de responder
            // a la UI. Así la confirmación visual significa exactamente "está en disco y hay
            // quien la retomará", no "seguimos esperando GPS".
            try {
                visitaDao.incrementarEvidencias(entrada.visitId)
            } catch (error: Exception) {
                // El contador es una ayuda visual, no la evidencia. La fila durable manda.
                Log.w(TAG, "No se pudo actualizar el contador local de ${entrada.visitId}", error)
            }
            try {
                programador.encolarSincronizacion()
            } catch (error: Exception) {
                // `reconciliarAlArrancar` vuelve a crear el trabajo. Nunca se convierte una
                // foto ya durable en un falso error que invite a disparar otra vez.
                Log.w(TAG, "No se pudo programar la sincronización inicial", error)
            }

            // GPS e integridad pueden tardar. No deben alargar la confirmación ni crear un
            // canal temporal que dependa de esas señales. Este scope pertenece al proceso,
            // no a la pantalla; si el proceso muere, el worker promueve la fila PREPARANDO.
            scopeAplicacion.launch {
                enriquecer(
                    visitId = entrada.visitId,
                    checklistJson = serializarChecklist(entrada.checklist),
                    clienteEvidenciaId = clienteEvidenciaId,
                    hash = hash,
                )
            }

            Resultado.Exito(clienteEvidenciaId)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observarPendientes(): Flow<List<EvidenciaPendiente>> =
        sesionStore.usuario.flatMapLatest { usuario ->
            if (usuario == null) {
                flowOf(emptyList())
            } else {
                dao.observarPorPropietario(usuario.id)
                    .map { filas -> filas.map { it.aDominio() } }
            }
        }

    override fun sincronizarAhora() = programador.encolarSincronizacion(expedito = true)

    override suspend fun reintentar(clienteEvidenciaId: String): Resultado<Unit> = withContext(io) {
        val ownerUid = sesionStore.usuario.first()?.id
            ?: return@withContext Resultado.Fallo(ErrorApp.SesionCaducada)
        val fila = dao.obtener(clienteEvidenciaId)
            ?: return@withContext Resultado.Fallo(ErrorApp.NoEncontrado)

        // 404 semántico: no confirma que exista una evidencia de otra persona en este
        // teléfono compartido y, sobre todo, impide reencolarla con la sesión equivocada.
        if (fila.ownerUid != ownerUid) return@withContext Resultado.Fallo(ErrorApp.NoEncontrado)

        // El contador de intentos se reinicia: el reintento es una decisión humana y no
        // debe heredar el backoff de los fallos automáticos anteriores.
        dao.actualizarEstado(fila.clienteEvidenciaId, EstadoSincronizacion.PENDIENTE.name, 0, null)
        programador.encolarSincronizacion(expedito = true)
        Resultado.Exito(Unit)
    }

    private suspend fun enriquecer(
        visitId: String,
        checklistJson: String,
        clienteEvidenciaId: String,
        hash: String,
    ) {
        try {
            val ubicacion = proveedorUbicacion.ubicacionActual()
            val nonce = calcularNonce(visitId, clienteEvidenciaId, hash)
            val integrityToken = proveedorIntegridad.token(nonce)

            dao.enriquecer(
                id = clienteEvidenciaId,
                lat = ubicacion?.lat,
                lng = ubicacion?.lng,
                precision = ubicacion?.precisionMetros,
                mock = ubicacion?.esSimulada ?: false,
                token = integrityToken,
                checklist = checklistJson,
                estado = EstadoSincronizacion.PENDIENTE.name,
            )
        } catch (error: Exception) {
            // La fila PREPARANDO y el archivo cifrado siguen intactos. WorkManager los
            // promoverá sin ubicación antes que perder la evidencia.
            Log.w(TAG, "No se pudo enriquecer $clienteEvidenciaId; queda recuperable", error)
        } finally {
            // Si el primer worker corrió mientras la fila aún estaba PREPARANDO, esta
            // ejecución encadenada verá ya el estado final (o activará la recuperación).
            try {
                programador.encolarSincronizacion()
            } catch (error: Exception) {
                Log.w(TAG, "No se pudo reprogramar $clienteEvidenciaId", error)
            }
        }
    }

    private fun sha256(datos: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(datos).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "EvidenciaRepository"
        /**
         * Espejo exacto de `calcularNonceEsperado` del backend. Si una de las dos cambia
         * sin la otra, todas las evidencias saldrán marcadas como token reutilizado — de
         * ahí que exista un test que fija el valor esperado para una entrada conocida.
         */
        fun calcularNonce(visitId: String, clienteEvidenciaId: String, hashSHA256: String): String {
            val material = "$visitId:$clienteEvidenciaId:$hashSHA256"
            val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
            // `java.util.Base64` y no `android.util.Base64`: el de Android es un stub en
            // tests de JVM y devolvía null, así que el cálculo sólo se podía verificar en
            // un dispositivo. Éste está disponible desde API 26 y produce exactamente el
            // mismo base64url sin relleno que `digest('base64url')` en Node.
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
