package com.verificlean.app.data.remote

import com.verificlean.app.data.remote.dto.AlertaDto
import com.verificlean.app.data.remote.dto.ChecklistItemDto
import com.verificlean.app.data.remote.dto.EvidenciaConfirmacionDto
import com.verificlean.app.data.remote.dto.EvidenciaDto
import com.verificlean.app.data.remote.dto.EvidenciaResumenDto
import com.verificlean.app.data.remote.dto.ReporteDto
import com.verificlean.app.data.remote.dto.RevisionAlertaDto
import com.verificlean.app.data.remote.dto.SitioDto
import com.verificlean.app.data.remote.dto.UsuarioDto
import com.verificlean.app.data.remote.dto.UsuarioSesionDto
import com.verificlean.app.data.remote.dto.VisitaDetalleAdminDto
import com.verificlean.app.data.remote.dto.VisitaDetalleSupervisorDto
import com.verificlean.app.data.remote.dto.VisitaResumenAdminDto
import com.verificlean.app.data.remote.dto.VisitaResumenSupervisorDto
import com.verificlean.app.domain.model.AlertaAgregada
import com.verificlean.app.domain.model.ChecklistItem
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.EstadoRevisionAlerta
import com.verificlean.app.domain.model.Evidencia
import com.verificlean.app.domain.model.EvidenciaResumen
import com.verificlean.app.domain.model.MotivoAlerta
import com.verificlean.app.domain.model.Reporte
import com.verificlean.app.domain.model.RevisionAlerta
import com.verificlean.app.domain.model.RespuestaChecklist
import com.verificlean.app.domain.model.ResumenSede
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Sitio
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.model.VeredictoIntegridad
import com.verificlean.app.domain.model.Visita
import com.verificlean.app.domain.model.VisitaDetalle
import com.verificlean.app.domain.model.VisitaDetalleAdmin
import com.verificlean.app.domain.model.VisitaResumenAdmin
import java.time.Instant

/**
 * DTO → dominio.
 *
 * Los enum se resuelven con búsqueda tolerante: si el backend estrena un motivo de
 * alerta o un estado, la app cae a un valor conocido en vez de lanzar. Una app que
 * revienta al deserializar convierte cualquier despliegue del backend en una
 * actualización obligatoria del cliente.
 */

private fun instante(iso: String?): Instant? =
    iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun instanteObligatorio(iso: String): Instant = instante(iso) ?: Instant.EPOCH

fun UsuarioDto.aDominio() = Usuario(
    id = id,
    nombre = nombre,
    email = email,
    rol = if (rol.equals("admin", ignoreCase = true)) Rol.ADMIN else Rol.SUPERVISOR,
    activo = activo,
)

fun UsuarioSesionDto.aDominio() = Usuario(
    id = id,
    nombre = nombre,
    email = email,
    rol = if (rol.equals("admin", ignoreCase = true)) Rol.ADMIN else Rol.SUPERVISOR,
    activo = activo,
)

private fun estadoVisita(valor: String): EstadoVisita = when (valor.lowercase()) {
    "completada" -> EstadoVisita.COMPLETADA
    "en_progreso" -> EstadoVisita.EN_PROGRESO
    else -> EstadoVisita.PENDIENTE
}

fun EstadoVisita.aWire(): String = when (this) {
    EstadoVisita.PENDIENTE -> "pendiente"
    EstadoVisita.EN_PROGRESO -> "en_progreso"
    EstadoVisita.COMPLETADA -> "completada"
}

private fun respuesta(valor: String?): RespuestaChecklist? = when (valor?.lowercase()) {
    "si" -> RespuestaChecklist.SI
    "no" -> RespuestaChecklist.NO
    "na" -> RespuestaChecklist.NA
    else -> null
}

fun RespuestaChecklist.aWire(): String = name.lowercase()

private fun aMotivo(valor: String): MotivoAlerta =
    runCatching { MotivoAlerta.valueOf(valor) }.getOrDefault(MotivoAlerta.DESCONOCIDO)

private fun veredicto(valor: String?): VeredictoIntegridad =
    runCatching { VeredictoIntegridad.valueOf(valor ?: "") }
        .getOrDefault(VeredictoIntegridad.NO_EVALUADO)

fun ChecklistItemDto.aDominio() = ChecklistItem(
    id = id,
    pregunta = pregunta,
    respuesta = respuesta(this.respuesta),
    nota = nota,
)

fun ChecklistItem.aDto() = ChecklistItemDto(
    id = id,
    pregunta = pregunta,
    respuesta = respuesta?.aWire(),
    nota = nota,
)

fun VisitaResumenSupervisorDto.aDominio() = Visita(
    id = id,
    siteId = siteId,
    siteClienteId = siteClienteId,
    siteNombreCliente = siteNombreCliente,
    siteNombreSede = siteNombreSede,
    supervisorId = supervisorId,
    supervisorNombre = supervisorNombre,
    estado = estadoVisita(estado),
    fechaAsignada = instanteObligatorio(fechaAsignada),
    completadaEn = instante(completadaEn),
    evidenciaCount = evidenciaCount,
    tieneSospechosas = false,
)

private fun RevisionAlertaDto.aDominio() = RevisionAlerta(
    estado = when (estado.lowercase()) {
        "revisada" -> EstadoRevisionAlerta.REVISADA
        "justificada" -> EstadoRevisionAlerta.JUSTIFICADA
        "escalada" -> EstadoRevisionAlerta.ESCALADA
        else -> EstadoRevisionAlerta.PENDIENTE
    },
    nota = nota,
    revisadaPorId = revisadaPorId,
    revisadaPorNombre = revisadaPorNombre,
    revisadaEn = instante(revisadaEn),
)

fun VisitaResumenAdminDto.aDominio() = VisitaResumenAdmin(
    visita = Visita(
        id = id,
        siteId = siteId,
        siteClienteId = siteClienteId,
        siteNombreCliente = siteNombreCliente,
        siteNombreSede = siteNombreSede,
        supervisorId = supervisorId,
        supervisorNombre = supervisorNombre,
        estado = estadoVisita(estado),
        fechaAsignada = instanteObligatorio(fechaAsignada),
        completadaEn = instante(completadaEn),
        evidenciaCount = evidenciaCount,
        tieneSospechosas = tieneSospechosas,
    ),
    revisionAlerta = revisionAlerta.aDominio(),
)

fun EvidenciaResumenDto.aDominio() = EvidenciaResumen(
    id = id,
    timestampServidor = instanteObligatorio(timestampServidor),
    distanciaAlSitioMetros = distanciaAlSitioMetros,
    esSospechosa = esSospechosa,
)

/**
 * Confirmación del supervisor → dominio.
 *
 * `distanciaAlSitioMetros` y `esSospechosa` quedan en sus valores neutros porque el
 * servidor **no los manda** a este rol, y no por falta de datos: son precisamente lo que
 * convertiría la respuesta en un oráculo de fraude. La app del supervisor sólo necesita
 * saber que la evidencia quedó registrada y a qué hora.
 */
fun EvidenciaConfirmacionDto.aDominio() = EvidenciaResumen(
    id = id,
    timestampServidor = instanteObligatorio(timestampServidor),
    distanciaAlSitioMetros = 0.0,
    esSospechosa = false,
)

fun VisitaDetalleSupervisorDto.aDominio() = VisitaDetalle(
    visita = Visita(
        id = id,
        siteId = siteId,
        siteClienteId = siteClienteId,
        siteNombreCliente = siteNombreCliente,
        siteNombreSede = siteNombreSede,
        supervisorId = supervisorId,
        supervisorNombre = supervisorNombre,
        estado = estadoVisita(estado),
        fechaAsignada = instanteObligatorio(fechaAsignada),
        completadaEn = instante(completadaEn),
        evidenciaCount = evidenciaCount,
        tieneSospechosas = false,
    ),
    siteDireccion = siteDireccion,
    siteLat = siteLat,
    siteLng = siteLng,
    // El supervisor nunca conoce el radio de su propia geocerca.
    siteRadioPermitidoMetros = null,
    iniciadaEn = instante(iniciadaEn),
    observaciones = observaciones,
    checklist = checklist.map { it.aDominio() },
    evidencias = evidencias.map { it.aDominio() },
)

fun EvidenciaDto.aDominio() = Evidencia(
    id = id,
    visitId = visitId,
    fotoUrl = fotoUrl,
    hashSHA256 = hashSHA256,
    lat = lat,
    lng = lng,
    precisionMetros = precisionMetros,
    distanciaAlSitioMetros = distanciaAlSitioMetros,
    timestampServidor = instanteObligatorio(timestampServidor),
    mockLocationReportada = mockLocationReportada,
    esSospechosa = esSospechosa,
    motivosAlerta = motivosAlerta.map(::aMotivo),
    veredictoIntegridad = veredicto(integrity?.veredicto),
)

fun AlertaDto.aDominio() = AlertaAgregada(motivo = aMotivo(motivo), conteo = conteo)

fun SitioDto.aDominio() = Sitio(
    id = id,
    clienteId = clienteId,
    nombreCliente = nombreCliente,
    nombreSede = nombreSede,
    direccion = direccion,
    lat = lat,
    lng = lng,
    radioPermitidoMetros = radioPermitidoMetros,
    activo = activo,
)

fun ReporteDto.aDominio() = Reporte(
    clienteId = clienteId,
    nombreCliente = nombreCliente,
    desde = instanteObligatorio(desde),
    hasta = instanteObligatorio(hasta),
    visitasAsignadas = resumen.visitasAsignadas,
    visitasCompletadas = resumen.visitasCompletadas,
    visitasPendientes = resumen.visitasPendientes,
    cumplimientoPorcentaje = resumen.cumplimientoPorcentaje,
    evidenciasTotales = resumen.evidenciasTotales,
    evidenciasSospechosas = resumen.evidenciasSospechosas,
    motivosFrecuentes = resumen.motivosFrecuentes.map {
        AlertaAgregada(motivo = aMotivo(it.motivo), conteo = it.conteo)
    },
    porSede = porSede.map {
        ResumenSede(
            siteId = it.siteId,
            nombreSede = it.nombreSede,
            visitasAsignadas = it.visitasAsignadas,
            visitasCompletadas = it.visitasCompletadas,
            evidenciasSospechosas = it.evidenciasSospechosas,
        )
    },
)

fun VisitaDetalleAdminDto.aDominio(): VisitaDetalleAdmin {
    val detalle = VisitaDetalle(
        visita = Visita(
            id = id,
            siteId = siteId,
            siteClienteId = siteClienteId,
            siteNombreCliente = siteNombreCliente,
            siteNombreSede = siteNombreSede,
            supervisorId = supervisorId,
            supervisorNombre = supervisorNombre,
            estado = estadoVisita(estado),
            fechaAsignada = instanteObligatorio(fechaAsignada),
            completadaEn = instante(completadaEn),
            evidenciaCount = evidenciaCount,
            tieneSospechosas = tieneSospechosas,
        ),
        siteDireccion = siteDireccion,
        siteLat = siteLat,
        siteLng = siteLng,
        siteRadioPermitidoMetros = siteRadioPermitidoMetros,
        iniciadaEn = instante(iniciadaEn),
        observaciones = observaciones,
        checklist = checklist.map { it.aDominio() },
        evidencias = evidencias.map { it.aDominio() },
    )
    return VisitaDetalleAdmin(
        detalle = detalle,
        evidenciasCompletas = evidenciasCompletas.map { it.aDominio() },
        alertas = alertas.map { it.aDominio() },
        revisionAlerta = revisionAlerta.aDominio(),
    )
}
