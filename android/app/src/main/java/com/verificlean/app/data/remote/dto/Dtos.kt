package com.verificlean.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * DTOs de la API. Espejo literal de `contracts/openapi.yaml`.
 *
 * Regla: los nombres de campo se copian tal cual del contrato — nada de renombrar aquí
 * "para que quede más bonito". Si un nombre no gusta, se cambia en el contrato y en los
 * dos lados a la vez. El mapeo a modelos de dominio se hace en `Mapeadores.kt`.
 *
 * Todo campo que el servidor pueda no mandar va con valor por defecto: `kotlinx`
 * lanza si falta un campo sin default, y una versión nueva del backend que añada un
 * campo no debe tumbar las apps ya instaladas.
 */

@Serializable
data class ErrorEnvelopeDto(val error: ErrorDetalleDto)

@Serializable
data class ErrorDetalleDto(
    val codigo: String,
    val mensaje: String,
    /**
     * `JsonElement` y no `String`: el contrato declara `additionalProperties: true`, así
     * que un valor puede ser número, lista u objeto — `sinResponder`, por ejemplo, llega
     * como array de ids. Con `Map<String,String>` esa respuesta perfectamente válida no
     * deserializaba, el envelope se descartaba entero y se perdía incluso el mensaje del
     * servidor, dejando al usuario con un error genérico.
     */
    val detalles: Map<String, JsonElement>? = null,
) {
    /** Los detalles aplanados a texto, para pintarlos junto a cada campo del formulario. */
    fun detallesComoTexto(): Map<String, String> =
        detalles.orEmpty().mapValues { (_, valor) ->
            (valor as? JsonPrimitive)?.contentOrNull ?: valor.toString()
        }
}

@Serializable
data class LoginRequestDto(
    val idToken: String,
    val deviceId: String? = null,
)

@Serializable
data class LoginResponseDto(
    val usuario: UsuarioSesionDto,
)

/** Perfil de sesión: sin huella ni resultado de vínculo del dispositivo. */
@Serializable
data class UsuarioSesionDto(
    val id: String,
    val nombre: String,
    val email: String,
    val rol: String,
    val activo: Boolean = true,
    val creadoEn: String? = null,
)

@Serializable
data class UsuarioDto(
    val id: String,
    val nombre: String,
    val email: String,
    val rol: String,
    val activo: Boolean = true,
    val deviceId: String? = null,
    val creadoEn: String? = null,
)

@Serializable
data class ListaUsuariosDto(val items: List<UsuarioDto> = emptyList())

@Serializable
data class UsuarioNuevoDto(
    val nombre: String,
    val email: String,
    val password: String,
    val rol: String,
)

@Serializable
data class CambioActivoDto(val activo: Boolean)

@Serializable
data class SitioDto(
    val id: String,
    val clienteId: String,
    val nombreCliente: String,
    val nombreSede: String,
    val direccion: String,
    val lat: Double,
    val lng: Double,
    val radioPermitidoMetros: Double,
    val activo: Boolean = true,
    val creadoEn: String? = null,
)

@Serializable
data class ListaSitiosDto(val items: List<SitioDto> = emptyList())

@Serializable
data class SitioNuevoDto(
    val clienteId: String,
    val nombreCliente: String,
    val nombreSede: String,
    val direccion: String,
    val lat: Double,
    val lng: Double,
    val radioPermitidoMetros: Double,
)

@Serializable
data class VisitaNuevaDto(
    val siteId: String,
    val supervisorId: String,
    val fechaAsignada: String,
)

@Serializable
data class ChecklistItemDto(
    val id: String,
    val pregunta: String,
    val respuesta: String? = null,
    val nota: String? = null,
)

/** Resumen que viaja por las rutas del supervisor. No tiene campo para el veredicto. */
@Serializable
data class VisitaResumenSupervisorDto(
    val id: String,
    val siteId: String,
    val siteClienteId: String = "",
    val siteNombreCliente: String,
    val siteNombreSede: String,
    val supervisorId: String,
    val supervisorNombre: String,
    val estado: String,
    val fechaAsignada: String,
    val completadaEn: String? = null,
    val evidenciaCount: Int = 0,
)

/** Resumen administrativo. Sólo las rutas bajo `/v1/admin` usan este tipo. */
@Serializable
data class VisitaResumenAdminDto(
    val id: String,
    val siteId: String,
    val siteClienteId: String = "",
    val siteNombreCliente: String,
    val siteNombreSede: String,
    val supervisorId: String,
    val supervisorNombre: String,
    val estado: String,
    val fechaAsignada: String,
    val completadaEn: String? = null,
    val evidenciaCount: Int = 0,
    val tieneSospechosas: Boolean = false,
    val revisionAlerta: RevisionAlertaDto = RevisionAlertaDto(),
)

@Serializable
data class RevisionAlertaDto(
    val estado: String = "pendiente",
    val nota: String? = null,
    val revisadaPorId: String? = null,
    val revisadaPorNombre: String? = null,
    val revisadaEn: String? = null,
)

@Serializable
data class RevisionAlertaRequestDto(
    val estado: String,
    val nota: String? = null,
)

/**
 * Confirmación de que una evidencia quedó registrada. Es **todo** lo que el supervisor
 * recibe: ni veredicto, ni distancia, ni hash.
 *
 * No es una omisión por ahorrar bytes. Devolverle `esSospechosa` y `motivosAlerta`
 * convertiría la API en un oráculo: probaría una ubicación, leería "FUERA_DE_GEOFENCE",
 * se acercaría y repetiría hasta que la respuesta saliera limpia. Ver
 * `backend/src/routes/mappers.ts` y `docs/ANTIFRAUD.md`.
 */
@Serializable
data class EvidenciaConfirmacionDto(
    val id: String,
    val visitId: String,
    val timestampServidor: String,
)

/** Evidencia en el detalle del admin: sí lleva el veredicto. */
@Serializable
data class EvidenciaResumenDto(
    val id: String,
    val timestampServidor: String,
    val distanciaAlSitioMetros: Double = 0.0,
    val esSospechosa: Boolean = false,
)

@Serializable
data class VisitaDetalleSupervisorDto(
    val id: String,
    val siteId: String,
    val siteClienteId: String = "",
    val siteNombreCliente: String,
    val siteNombreSede: String,
    val siteDireccion: String = "",
    val siteLat: Double,
    val siteLng: Double,
    // Sin `siteRadioPermitidoMetros`: el servidor no manda el umbral de geocerca en la
    // vista del supervisor. Declararlo aquí lo volvería a traer al dispositivo en cuanto
    // alguien lo reañadiera al backend sin darse cuenta.
    val supervisorId: String,
    val supervisorNombre: String,
    val estado: String,
    val fechaAsignada: String,
    val iniciadaEn: String? = null,
    val completadaEn: String? = null,
    val observaciones: String? = null,
    val checklist: List<ChecklistItemDto> = emptyList(),
    val evidenciaCount: Int = 0,
    val evidencias: List<EvidenciaConfirmacionDto> = emptyList(),
)

@Serializable
data class IntegridadDto(
    val veredicto: String,
    val deviceRecognition: List<String> = emptyList(),
    val appRecognition: String? = null,
    val motivo: String? = null,
)

@Serializable
data class EvidenciaDto(
    val id: String,
    val visitId: String,
    val siteId: String = "",
    val supervisorId: String = "",
    val fotoUrl: String? = null,
    val hashSHA256: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val precisionMetros: Double? = null,
    val distanciaAlSitioMetros: Double? = null,
    val timestampServidor: String,
    val timestampCliente: String? = null,
    val mockLocationReportada: Boolean = false,
    val esSospechosa: Boolean = false,
    val motivosAlerta: List<String> = emptyList(),
    val integrity: IntegridadDto? = null,
)

@Serializable
data class AlertaDto(
    val motivo: String,
    val conteo: Int,
    val descripcion: String? = null,
)

@Serializable
data class VisitaDetalleAdminDto(
    // Los campos del detalle normal llegan aplanados en el mismo objeto (`allOf` del
    // OpenAPI), así que se reusa el DTO completo mediante composición manual.
    val id: String,
    val siteId: String,
    val siteClienteId: String = "",
    val siteNombreCliente: String,
    val siteNombreSede: String,
    val siteDireccion: String = "",
    val siteLat: Double,
    val siteLng: Double,
    val siteRadioPermitidoMetros: Double,
    val supervisorId: String,
    val supervisorNombre: String,
    val estado: String,
    val fechaAsignada: String,
    val iniciadaEn: String? = null,
    val completadaEn: String? = null,
    val observaciones: String? = null,
    val checklist: List<ChecklistItemDto> = emptyList(),
    val evidenciaCount: Int = 0,
    val tieneSospechosas: Boolean = false,
    val revisionAlerta: RevisionAlertaDto = RevisionAlertaDto(),
    val evidencias: List<EvidenciaResumenDto> = emptyList(),
    val evidenciasCompletas: List<EvidenciaDto> = emptyList(),
    val alertas: List<AlertaDto> = emptyList(),
)

@Serializable
data class PaginaVisitasSupervisorDto(
    val items: List<VisitaResumenSupervisorDto> = emptyList(),
    val siguienteCursor: String? = null,
)

@Serializable
data class PaginaVisitasAdminDto(
    val items: List<VisitaResumenAdminDto> = emptyList(),
    val siguienteCursor: String? = null,
)

@Serializable
data class CompletarVisitaRequestDto(
    val checklist: List<ChecklistItemDto>,
    val observaciones: String? = null,
)

@Serializable
data class ResumenReporteDto(
    val visitasAsignadas: Int = 0,
    val visitasCompletadas: Int = 0,
    val visitasPendientes: Int = 0,
    val cumplimientoPorcentaje: Double = 0.0,
    val evidenciasTotales: Int = 0,
    val evidenciasSospechosas: Int = 0,
    val motivosFrecuentes: List<MotivoConteoDto> = emptyList(),
)

@Serializable
data class MotivoConteoDto(val motivo: String, val conteo: Int)

@Serializable
data class SedeReporteDto(
    val siteId: String,
    val nombreSede: String,
    val visitasAsignadas: Int = 0,
    val visitasCompletadas: Int = 0,
    val evidenciasSospechosas: Int = 0,
)

@Serializable
data class ReporteDto(
    val clienteId: String,
    val nombreCliente: String? = null,
    val desde: String,
    val hasta: String,
    val generadoEn: String? = null,
    val resumen: ResumenReporteDto,
    @SerialName("porSede") val porSede: List<SedeReporteDto> = emptyList(),
)
