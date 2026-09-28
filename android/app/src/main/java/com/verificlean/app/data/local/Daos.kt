package com.verificlean.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitaDao {

    @Query("SELECT * FROM visitas ORDER BY fechaAsignadaMillis DESC")
    fun observarTodas(): Flow<List<VisitaEntity>>

    @Query("SELECT * FROM visitas WHERE estado = :estado ORDER BY fechaAsignadaMillis DESC")
    fun observarPorEstado(estado: String): Flow<List<VisitaEntity>>

    @Query("SELECT * FROM visitas WHERE id = :id")
    suspend fun obtener(id: String): VisitaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarTodas(visitas: List<VisitaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(visita: VisitaEntity)

    @Query("DELETE FROM visitas WHERE id NOT IN (:idsVigentes)")
    suspend fun borrarLasQueYaNoEstan(idsVigentes: List<String>)

    /**
     * Reemplaza la caché por lo que devolvió el servidor, en una transacción.
     *
     * El borrado va **después** del insert y sólo sobre lo que no vino en la respuesta:
     * así, si el proceso muere a mitad, la caché queda con datos de más pero nunca
     * vacía. Vaciar primero dejaría al supervisor con una bandeja en blanco si el
     * insert falla.
     */
    @Transaction
    suspend fun reemplazar(visitas: List<VisitaEntity>) {
        guardarTodas(visitas)
        if (visitas.isEmpty()) borrarTodas() else borrarLasQueYaNoEstan(visitas.map { it.id })
    }

    @Query("DELETE FROM visitas")
    suspend fun borrarTodas()

    @Query("UPDATE visitas SET evidenciaCount = evidenciaCount + 1 WHERE id = :visitId")
    suspend fun incrementarEvidencias(visitId: String)
}

@Dao
interface EvidenciaPendienteDao {

    @Query("SELECT * FROM evidencias_pendientes ORDER BY capturadoEnMillis ASC")
    fun observarTodas(): Flow<List<EvidenciaPendienteEntity>>

    @Query(
        "SELECT * FROM evidencias_pendientes WHERE ownerUid = :ownerUid " +
            "ORDER BY capturadoEnMillis ASC",
    )
    fun observarPorPropietario(ownerUid: String): Flow<List<EvidenciaPendienteEntity>>

    /**
     * Cola de subida: sólo lo que puede reintentarse, del supervisor indicado, en orden
     * de captura.
     *
     * Se filtra por `ownerUid` porque la cola sobrevive al cierre de sesión: sin el
     * filtro, un supervisor que entrara después en el mismo dispositivo subiría las
     * evidencias del anterior con su propio token, el servidor devolvería 403 y quedarían
     * `FALLIDA` para siempre.
     *
     * Las `FALLIDA` quedan fuera — reintentarlas en bucle gastaría batería y datos
     * sabiendo que el servidor ya dijo que no. Las `PREPARANDO` también: todavía les
     * falta ubicación, y se promueven aparte.
     */
    @Query(
        "SELECT * FROM evidencias_pendientes WHERE ownerUid = :ownerUid " +
            "AND estado IN ('PENDIENTE','ENVIANDO') ORDER BY capturadoEnMillis ASC LIMIT :limite",
    )
    suspend fun siguientesParaSubir(ownerUid: String, limite: Int): List<EvidenciaPendienteEntity>

    /**
     * Filas que se quedaron a medio enriquecer porque el proceso murió entre guardar la
     * foto y resolver la ubicación.
     *
     * Se pide una antigüedad mínima para no pisar una captura que ahora mismo está
     * esperando el GPS de forma legítima.
     */
    @Query(
        "SELECT * FROM evidencias_pendientes WHERE estado = 'PREPARANDO' " +
            "AND capturadoEnMillis < :anteriorA",
    )
    suspend fun preparandoAbandonadas(anteriorA: Long): List<EvidenciaPendienteEntity>

    @Query(
        "SELECT COUNT(*) FROM evidencias_pendientes " +
            "WHERE ownerUid = :ownerUid AND estado = 'PREPARANDO'",
    )
    suspend fun contarPreparando(ownerUid: String): Int

    @Query("UPDATE evidencias_pendientes SET estado = :estado WHERE clienteEvidenciaId = :id")
    suspend fun cambiarEstado(id: String, estado: String)

    /** Completa una fila `PREPARANDO` con los datos que faltaban y la pone en cola. */
    @Query(
        "UPDATE evidencias_pendientes SET lat = :lat, lng = :lng, precisionMetros = :precision, " +
            "mockLocationReportada = :mock, integrityToken = :token, checklistJson = :checklist, " +
            "estado = :estado WHERE clienteEvidenciaId = :id",
    )
    suspend fun enriquecer(
        id: String,
        lat: Double?,
        lng: Double?,
        precision: Double?,
        mock: Boolean,
        token: String?,
        checklist: String,
        estado: String,
    )

    @Query("SELECT COUNT(*) FROM evidencias_pendientes WHERE estado IN ('PREPARANDO','PENDIENTE','ENVIANDO')")
    fun contarPendientes(): Flow<Int>

    /**
     * Rutas de **todas** las filas, incluidas las `FALLIDA`.
     *
     * La usa la limpieza de huérfanos, y por eso no puede reutilizar
     * `siguientesParaSubir`: esa consulta excluye las fallidas, así que la limpieza
     * borraría el archivo cifrado de una evidencia que el supervisor todavía puede
     * reintentar desde la app, dejando la fila apuntando a un archivo inexistente.
     */
    @Query("SELECT rutaArchivoCifrado FROM evidencias_pendientes")
    suspend fun todasLasRutas(): List<String>

    @Query("SELECT * FROM evidencias_pendientes WHERE clienteEvidenciaId = :id")
    suspend fun obtener(id: String): EvidenciaPendienteEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertar(evidencia: EvidenciaPendienteEntity)

    @Query("UPDATE evidencias_pendientes SET estado = :estado, intentos = :intentos, ultimoError = :error WHERE clienteEvidenciaId = :id")
    suspend fun actualizarEstado(id: String, estado: String, intentos: Int, error: String?)

    @Query("DELETE FROM evidencias_pendientes WHERE clienteEvidenciaId = :id")
    suspend fun borrar(id: String)

    @Query("DELETE FROM evidencias_pendientes")
    suspend fun borrarTodas()
}
