package com.verificlean.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Esquema local. Dos tablas y ninguna más:
 *
 * - `visitas`: caché de sólo lectura para que la bandeja abra sin red.
 * - `evidencias_pendientes`: la cola de subida. Es la tabla crítica — mientras una fila
 *   viva aquí, el trabajo del supervisor no se ha perdido.
 *
 * Los sitios y los usuarios no se cachean: la visita ya trae denormalizados los datos de
 * sede que la app necesita, así que una tabla de sitios sería una copia de una copia.
 */

@Entity(
    tableName = "visitas",
    indices = [Index("estado"), Index("fechaAsignadaMillis")],
)
data class VisitaEntity(
    @PrimaryKey val id: String,
    val siteId: String,
    val siteClienteId: String,
    val siteNombreCliente: String,
    val siteNombreSede: String,
    val siteDireccion: String,
    val siteLat: Double,
    val siteLng: Double,
    val siteRadioPermitidoMetros: Double,
    val supervisorId: String,
    val supervisorNombre: String,
    val estado: String,
    val fechaAsignadaMillis: Long,
    val completadaEnMillis: Long?,
    val evidenciaCount: Int,
    val tieneSospechosas: Boolean,
    /** Checklist serializado a JSON: Room no mapea listas de objetos sin un conversor. */
    val checklistJson: String,
    val observaciones: String?,
    /** Cuándo se refrescó desde la API. Permite mostrar "datos de hace X". */
    val actualizadoEnMillis: Long,
)

@Entity(
    tableName = "evidencias_pendientes",
    indices = [Index("visitId"), Index("estado"), Index("ownerUid")],
)
data class EvidenciaPendienteEntity(
    /**
     * UUID generado en el dispositivo. Es también la clave de idempotencia que viaja al
     * servidor: si un reintento sube la misma evidencia dos veces, el backend devuelve la
     * original en vez de duplicarla.
     */
    @PrimaryKey val clienteEvidenciaId: String,

    /**
     * Supervisor que capturó la evidencia.
     *
     * La cola sobrevive al cierre de sesión a propósito —es trabajo real sin subir— pero
     * sin este campo, si otro supervisor entra en el mismo dispositivo, el worker subiría
     * la evidencia ajena con el token nuevo. El servidor respondería 403 y la marcaría
     * `FALLIDA` de forma definitiva, destruyendo evidencia válida de su compañero.
     */
    val ownerUid: String,

    val visitId: String,
    val visitaNombreSede: String,
    /** Ruta del archivo cifrado en el almacenamiento interno de la app. */
    val rutaArchivoCifrado: String,
    /** SHA-256 del JPEG en claro, calculado antes de cifrar. */
    val hashSHA256: String,

    // Nulos mientras la fila está en PREPARANDO: la foto ya está a salvo en disco pero
    // todavía no se ha resuelto la ubicación ni el token de integridad.
    val lat: Double?,
    val lng: Double?,
    val precisionMetros: Double?,
    val mockLocationReportada: Boolean,
    val integrityToken: String?,

    val capturadoEnMillis: Long,
    val checklistJson: String,
    val estado: String,
    val intentos: Int,
    val ultimoError: String?,
)
