package com.verificlean.app.data.local

import com.verificlean.app.data.remote.dto.ChecklistItemDto
import com.verificlean.app.data.remote.dto.VisitaDetalleSupervisorDto
import com.verificlean.app.data.remote.dto.VisitaResumenSupervisorDto
import com.verificlean.app.domain.model.ChecklistItem
import com.verificlean.app.domain.model.EstadoSincronizacion
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.EvidenciaPendiente
import com.verificlean.app.domain.model.RespuestaChecklist
import com.verificlean.app.domain.model.Visita
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Entidad Room ↔ dominio, y DTO → entidad.
 *
 * El checklist se guarda como JSON en una columna en vez de en una tabla propia: nunca
 * se consulta por sus campos, siempre se lee entero con su visita, y una tabla hija
 * añadiría un `@Relation` y una transacción a cada escritura para nada.
 */

internal val jsonLocal = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun serializarChecklist(items: List<ChecklistItem>): String =
    jsonLocal.encodeToString(items.map { it.aDtoLocal() })

fun deserializarChecklist(json: String): List<ChecklistItem> =
    runCatching { jsonLocal.decodeFromString<List<ChecklistItemDto>>(json).map { it.aDominioLocal() } }
        // Un JSON corrupto en la caché no puede impedir abrir la visita: se devuelve
        // vacío y el siguiente refresco lo repara.
        .getOrDefault(emptyList())

private fun ChecklistItem.aDtoLocal() = ChecklistItemDto(
    id = id,
    pregunta = pregunta,
    respuesta = respuesta?.name?.lowercase(),
    nota = nota,
)

private fun ChecklistItemDto.aDominioLocal() = ChecklistItem(
    id = id,
    pregunta = pregunta,
    respuesta = when (respuesta?.lowercase()) {
        "si" -> RespuestaChecklist.SI
        "no" -> RespuestaChecklist.NO
        "na" -> RespuestaChecklist.NA
        else -> null
    },
    nota = nota,
)

private fun estadoDe(valor: String): EstadoVisita = when (valor.lowercase()) {
    "completada" -> EstadoVisita.COMPLETADA
    "en_progreso" -> EstadoVisita.EN_PROGRESO
    else -> EstadoVisita.PENDIENTE
}

fun VisitaEntity.aDominio() = Visita(
    id = id,
    siteId = siteId,
    siteClienteId = siteClienteId,
    siteNombreCliente = siteNombreCliente,
    siteNombreSede = siteNombreSede,
    supervisorId = supervisorId,
    supervisorNombre = supervisorNombre,
    estado = estadoDe(estado),
    fechaAsignada = Instant.ofEpochMilli(fechaAsignadaMillis),
    completadaEn = completadaEnMillis?.let(Instant::ofEpochMilli),
    evidenciaCount = evidenciaCount,
    tieneSospechosas = tieneSospechosas,
)

private fun instanteDe(iso: String?): Long? =
    iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/**
 * El resumen no trae lat/lng ni radio de la sede. Se conserva lo que ya hubiera en
 * caché para esa visita: sobrescribir con ceros dejaría la pantalla de captura sin
 * saber dónde está el sitio cuando se abre sin conexión.
 */
fun VisitaResumenSupervisorDto.aEntidad(previa: VisitaEntity?) = VisitaEntity(
    id = id,
    siteId = siteId,
    siteClienteId = siteClienteId,
    siteNombreCliente = siteNombreCliente,
    siteNombreSede = siteNombreSede,
    siteDireccion = previa?.siteDireccion.orEmpty(),
    siteLat = previa?.siteLat ?: 0.0,
    siteLng = previa?.siteLng ?: 0.0,
    siteRadioPermitidoMetros = previa?.siteRadioPermitidoMetros ?: 0.0,
    supervisorId = supervisorId,
    supervisorNombre = supervisorNombre,
    estado = estado,
    fechaAsignadaMillis = instanteDe(fechaAsignada) ?: 0L,
    completadaEnMillis = instanteDe(completadaEn),
    evidenciaCount = evidenciaCount,
    tieneSospechosas = false,
    checklistJson = previa?.checklistJson ?: "[]",
    observaciones = previa?.observaciones,
    actualizadoEnMillis = System.currentTimeMillis(),
)

fun VisitaDetalleSupervisorDto.aEntidad() = VisitaEntity(
    id = id,
    siteId = siteId,
    siteClienteId = siteClienteId,
    siteNombreCliente = siteNombreCliente,
    siteNombreSede = siteNombreSede,
    siteDireccion = siteDireccion,
    siteLat = siteLat,
    siteLng = siteLng,
    // El servidor no manda el radio de geocerca en la vista del supervisor y la caché
    // tampoco lo inventa. La columna sobrevive con 0.0 porque quitarla exigiría una
    // migración de Room que recrea la tabla; ninguna pantalla la lee.
    siteRadioPermitidoMetros = 0.0,
    supervisorId = supervisorId,
    supervisorNombre = supervisorNombre,
    estado = estado,
    fechaAsignadaMillis = instanteDe(fechaAsignada) ?: 0L,
    completadaEnMillis = instanteDe(completadaEn),
    evidenciaCount = evidenciaCount,
    tieneSospechosas = false,
    checklistJson = jsonLocal.encodeToString(checklist),
    observaciones = observaciones,
    actualizadoEnMillis = System.currentTimeMillis(),
)

fun EvidenciaPendienteEntity.aDominio() = EvidenciaPendiente(
    clienteEvidenciaId = clienteEvidenciaId,
    visitId = visitId,
    visitaNombreSede = visitaNombreSede,
    capturadoEn = Instant.ofEpochMilli(capturadoEnMillis),
    estado = runCatching { EstadoSincronizacion.valueOf(estado) }
        .getOrDefault(EstadoSincronizacion.PENDIENTE),
    intentos = intentos,
    ultimoError = ultimoError,
)
