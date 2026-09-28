package com.verificlean.app.domain.model

import java.time.Instant

/**
 * Modelos de dominio. Espejo de `contracts/firestore.schema.md`.
 *
 * Se mantienen separados de los DTO de red (`data/remote/dto`) y de las entidades Room
 * (`data/local`) aunque hoy se parezcan mucho: el DTO cambia cuando cambia la API, la
 * entidad cambia cuando cambia el esquema local, y ninguno de esos cambios debería
 * obligar a tocar la UI. Las conversiones viven en los mapeadores de cada capa.
 */

enum class Rol { ADMIN, SUPERVISOR }

enum class EstadoVisita { PENDIENTE, EN_PROGRESO, COMPLETADA }

enum class EstadoRevisionAlerta { PENDIENTE, REVISADA, JUSTIFICADA, ESCALADA }

enum class RespuestaChecklist { SI, NO, NA }

enum class VeredictoIntegridad { CONFIABLE, NO_CONFIABLE, NO_EVALUADO }

/**
 * Motivos por los que el servidor marca una evidencia. `DESCONOCIDO` absorbe cualquier
 * valor nuevo que el backend empiece a mandar: sin él, desplegar una regla antifraude
 * nueva rompería todas las apps instaladas al deserializar.
 */
enum class MotivoAlerta {
    FUERA_DE_GEOFENCE,
    IMAGEN_DUPLICADA,
    DISPOSITIVO_NO_CONFIABLE,
    MOCK_LOCATION,
    PRECISION_GPS_BAJA,
    DISPOSITIVO_NO_VINCULADO,
    HASH_NO_COINCIDE,
    INTEGRIDAD_NO_EVALUADA,
    TOKEN_INTEGRIDAD_REUTILIZADO,
    TOKEN_INTEGRIDAD_CADUCADO,
    CHECKLIST_ALTERADO,
    SIN_UBICACION,
    DESCONOCIDO;

    val descripcion: String
        get() = when (this) {
            FUERA_DE_GEOFENCE -> "La foto se tomó fuera del radio permitido de la sede."
            IMAGEN_DUPLICADA -> "Esta imagen ya se había subido en otra visita."
            DISPOSITIVO_NO_CONFIABLE -> "Dispositivo comprometido (root, bootloader abierto o app modificada)."
            MOCK_LOCATION -> "Android reportó la ubicación como simulada."
            PRECISION_GPS_BAJA -> "La precisión del GPS no permite confirmar si estuvo dentro."
            DISPOSITIVO_NO_VINCULADO -> "Llegó desde un dispositivo distinto al registrado."
            HASH_NO_COINCIDE -> "Los bytes recibidos no coinciden con el hash declarado."
            INTEGRIDAD_NO_EVALUADA -> "No se pudo verificar el dispositivo."
            TOKEN_INTEGRIDAD_REUTILIZADO ->
                "El token de verificación no corresponde a esta foto ni a esta visita: se obtuvo para otra cosa y se reutilizó aquí."
            TOKEN_INTEGRIDAD_CADUCADO ->
                "El token de verificación se emitió fuera de la ventana de validez."
            CHECKLIST_ALTERADO -> "El checklist enviado no coincide con el asignado a la visita."
            SIN_UBICACION ->
                "El dispositivo no pudo determinar la ubicación al tomar la foto. No prueba presencia, pero tampoco la desmiente."
            DESCONOCIDO -> "Alerta reportada por el servidor."
        }
}

data class Usuario(
    val id: String,
    val nombre: String,
    val email: String,
    val rol: Rol,
    val activo: Boolean,
)

/**
 * Sede física de un cliente. Es lo que se geocerca.
 *
 * `radioPermitidoMetros` es el parámetro más delicado del producto: por debajo de 50 m el
 * error típico del GPS balanceado marca como fraude a supervisores que sí fueron, y por
 * encima de unos cientos deja de probar nada. El servidor lo acota entre 50 y 2000.
 */
data class Sitio(
    val id: String,
    val clienteId: String,
    val nombreCliente: String,
    val nombreSede: String,
    val direccion: String,
    val lat: Double,
    val lng: Double,
    val radioPermitidoMetros: Double,
    val activo: Boolean,
)

/** Resumen de cumplimiento de un cliente en un rango de fechas. */
data class Reporte(
    val clienteId: String,
    val nombreCliente: String?,
    val desde: Instant,
    val hasta: Instant,
    val visitasAsignadas: Int,
    val visitasCompletadas: Int,
    val visitasPendientes: Int,
    val cumplimientoPorcentaje: Double,
    val evidenciasTotales: Int,
    val evidenciasSospechosas: Int,
    val motivosFrecuentes: List<AlertaAgregada>,
    val porSede: List<ResumenSede>,
)

data class ResumenSede(
    val siteId: String,
    val nombreSede: String,
    val visitasAsignadas: Int,
    val visitasCompletadas: Int,
    val evidenciasSospechosas: Int,
)

data class ChecklistItem(
    val id: String,
    val pregunta: String,
    val respuesta: RespuestaChecklist?,
    val nota: String?,
)

data class Visita(
    val id: String,
    val siteId: String,
    val siteClienteId: String,
    val siteNombreCliente: String,
    val siteNombreSede: String,
    val supervisorId: String,
    val supervisorNombre: String,
    val estado: EstadoVisita,
    val fechaAsignada: Instant,
    val completadaEn: Instant?,
    val evidenciaCount: Int,
    val tieneSospechosas: Boolean,
)

/** Proyección exclusiva del panel. La bandeja del supervisor sólo recibe [Visita]. */
data class VisitaResumenAdmin(
    val visita: Visita,
    val revisionAlerta: RevisionAlerta,
)

data class RevisionAlerta(
    val estado: EstadoRevisionAlerta,
    val nota: String?,
    val revisadaPorId: String?,
    val revisadaPorNombre: String?,
    val revisadaEn: Instant?,
)

data class VisitaDetalle(
    val visita: Visita,
    val siteDireccion: String,
    val siteLat: Double,
    val siteLng: Double,
    /**
     * `null` en la vista del supervisor: el servidor no se lo manda.
     *
     * Es el umbral con el que se le juzga. Saber que la geocerca son 150 m le dice cuánto
     * puede alejarse antes de que la evidencia salga marcada — el mismo oráculo que
     * devolverle los motivos de alerta. Sólo el detalle del admin lo trae.
     */
    val siteRadioPermitidoMetros: Double?,
    val iniciadaEn: Instant?,
    val observaciones: String?,
    val checklist: List<ChecklistItem>,
    val evidencias: List<EvidenciaResumen>,
)

data class EvidenciaResumen(
    val id: String,
    val timestampServidor: Instant,
    val distanciaAlSitioMetros: Double,
    val esSospechosa: Boolean,
)

data class Evidencia(
    val id: String,
    val visitId: String,
    val fotoUrl: String?,
    val hashSHA256: String,
    /** `null` si el dispositivo no consiguió ubicación al capturar. */
    val lat: Double?,
    val lng: Double?,
    val precisionMetros: Double?,
    /** `null` cuando no hay ubicación: no se inventa una distancia. */
    val distanciaAlSitioMetros: Double?,
    val timestampServidor: Instant,
    val mockLocationReportada: Boolean,
    val esSospechosa: Boolean,
    val motivosAlerta: List<MotivoAlerta>,
    val veredictoIntegridad: VeredictoIntegridad,
)

data class AlertaAgregada(val motivo: MotivoAlerta, val conteo: Int)

data class VisitaDetalleAdmin(
    val detalle: VisitaDetalle,
    val evidenciasCompletas: List<Evidencia>,
    val alertas: List<AlertaAgregada>,
    val revisionAlerta: RevisionAlerta,
)

/**
 * Estado de una evidencia capturada pero todavía no confirmada por el servidor.
 * Sólo existe en el dispositivo.
 */
enum class EstadoSincronizacion {
    /**
     * La foto ya está cifrada en disco y su fila existe, pero todavía faltan la ubicación
     * y el token de integridad.
     *
     * Este estado existe para que la foto **nunca** viva sólo en memoria. Antes, entre el
     * disparo de la cámara y el guardado había varios segundos pidiendo GPS y Play
     * Integrity; si el proceso moría ahí —o el usuario volvía atrás— el JPEG desaparecía
     * y el supervisor había hecho el trayecto para nada.
     *
     * Una fila que se quede aquí porque el enriquecimiento murió se promueve a
     * `PENDIENTE` y se sube con lo que tenga. Sin ubicación el servidor la marcará, y esa
     * es la respuesta correcta: una evidencia marcada y visible vale infinitamente más
     * que una foto perdida.
     */
    PREPARANDO,

    /** En cola. WorkManager la subirá cuando haya red. */
    PENDIENTE,

    /** Subiendo ahora mismo. */
    ENVIANDO,

    /**
     * El servidor la rechazó con un 4xx que no se arregla reintentando (foto corrupta,
     * visita ya cerrada). Se conserva para que el supervisor vea qué pasó, en vez de
     * desaparecer en silencio.
     */
    FALLIDA,
}

data class EvidenciaPendiente(
    val clienteEvidenciaId: String,
    val visitId: String,
    val visitaNombreSede: String,
    val capturadoEn: Instant,
    val estado: EstadoSincronizacion,
    val intentos: Int,
    val ultimoError: String?,
)
