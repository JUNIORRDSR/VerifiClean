package com.verificlean.app.ui.pantallas.reportes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verificlean.app.domain.model.AlertaAgregada
import com.verificlean.app.domain.model.Reporte
import com.verificlean.app.domain.model.ResumenSede
import com.verificlean.app.ui.componentes.CargandoPantalla
import com.verificlean.app.ui.componentes.MensajeVacio
import com.verificlean.app.ui.componentes.MensajeError
import com.verificlean.app.ui.theme.alertaFraude
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reporte de cumplimiento por cliente.
 *
 * Es lo que la empresa de aseo le enseña a su cliente para justificar la facturación, así
 * que el número que manda es el porcentaje de cumplimiento. Las evidencias sospechosas van
 * debajo y **sin dramatizar**: son casos a revisar internamente, no una acusación que
 * convenga poner delante del cliente final.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportesScreen(
    alVolver: () -> Unit,
    viewModel: ReportesViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val contexto = LocalContext.current
    var busquedaCliente by rememberSaveable { mutableStateOf("") }
    var reporteParaExportar by remember { mutableStateOf<Reporte?>(null) }
    var errorExportacion by remember { mutableStateOf<String?>(null) }
    val exportador = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val reporte = reporteParaExportar
        if (uri != null && reporte != null) {
            errorExportacion = runCatching {
                contexto.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(reporte.aCsv())
                } ?: error("No se pudo abrir el archivo elegido")
            }.exceptionOrNull()?.let { "No se pudo exportar el reporte. Intenta de nuevo." }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reportes") },
                navigationIcon = {
                    IconButton(onClick = alVolver) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { relleno ->
        when {
            estado.cargandoClientes -> CargandoPantalla(Modifier.padding(relleno))

            estado.error != null && estado.clientes.isEmpty() -> MensajeError(
                texto = estado.error.orEmpty(),
                alReintentar = viewModel::cargarClientes,
                modifier = Modifier.padding(relleno),
            )

            estado.clientes.isEmpty() -> MensajeVacio(
                "No hay clientes todavía. Crea sedes para poder generar reportes.",
                Modifier.padding(relleno),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(relleno).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cliente", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = busquedaCliente,
                            onValueChange = { busquedaCliente = it },
                            label = { Text("Buscar cliente") },
                            supportingText = { Text("Escribe parte del nombre y elige una opción.") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val visibles = estado.clientes.filter {
                            busquedaCliente.isBlank() ||
                                it.nombreCliente.contains(busquedaCliente, ignoreCase = true)
                        }.take(8)
                        if (visibles.isEmpty()) {
                            Text(
                                "No encontramos clientes con ese nombre.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                visibles.forEach { cliente ->
                                    FilterChip(
                                        selected = estado.clienteElegido?.clienteId == cliente.clienteId,
                                        onClick = { viewModel.elegirCliente(cliente) },
                                        label = { Text("${cliente.nombreCliente} · ${cliente.sedes} sede(s)") },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RangoReporte.entries.forEach { rango ->
                            FilterChip(
                                selected = estado.rango == rango,
                                onClick = { viewModel.elegirRango(rango) },
                                label = { Text(rango.etiqueta) },
                            )
                        }
                    }
                }

                if (estado.cargandoReporte) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }

                if (estado.error != null && !estado.cargandoReporte) {
                    item {
                        MensajeError(
                            texto = estado.error.orEmpty(),
                            alReintentar = viewModel::generar,
                        )
                    }
                }

                val reporte = estado.reporte
                when {
                    reporte == null && !estado.cargandoReporte -> item {
                        Text(
                            text = "Elige un cliente para ver su reporte.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    reporte != null -> {
                        item { TarjetaCumplimiento(reporte, estado.reporteAnterior) }
                        if (estado.errorComparacion != null) {
                            item {
                                Text(
                                    "No se pudo cargar el período anterior; el reporte actual sigue disponible.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    reporteParaExportar = reporte
                                    errorExportacion = null
                                    exportador.launch("verificlean-${reporte.clienteId}.csv")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Text("Exportar CSV", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        if (errorExportacion != null) {
                            item { MensajeError(errorExportacion.orEmpty()) }
                        }
                        item { TarjetaEvidencias(reporte) }

                        if (reporte.motivosFrecuentes.isNotEmpty()) {
                            item {
                                Text("Motivos más frecuentes", style = MaterialTheme.typography.titleMedium)
                            }
                            items(reporte.motivosFrecuentes, key = { it.motivo.name }) {
                                FilaMotivo(it)
                            }
                        }

                        if (reporte.porSede.isNotEmpty()) {
                            item {
                                Text("Por sede", style = MaterialTheme.typography.titleMedium)
                            }
                            items(reporte.porSede, key = { it.siteId }) { FilaSede(it) }
                        }
                    }
                }

                item { Column(Modifier.padding(bottom = 24.dp)) {} }
            }
        }
    }
}

@Composable
private fun TarjetaCumplimiento(reporte: Reporte, anterior: Reporte?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Cumplimiento",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = if (reporte.visitasAsignadas == 0) {
                    "Sin visitas programadas"
                } else {
                    "${reporte.cumplimientoPorcentaje}%"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "${formatoPeriodo.format(reporte.desde.atZone(ZoneId.systemDefault()))} – " +
                    formatoPeriodo.format(reporte.hasta.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (reporte.visitasAsignadas == 0) {
                Text(
                    "No se programaron visitas en este período; 0 % no representa incumplimiento.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                GraficoCumplimiento(reporte.cumplimientoPorcentaje, anterior?.cumplimientoPorcentaje)
                Text(
                    text = "${reporte.visitasCompletadas} de ${reporte.visitasAsignadas} visitas completadas" +
                        if (reporte.visitasPendientes > 0) " · ${reporte.visitasPendientes} pendientes" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (anterior != null) {
                    val delta = reporte.cumplimientoPorcentaje - anterior.cumplimientoPorcentaje
                    Text(
                        text = when {
                            delta > 0 -> "+${"%.1f".format(Locale.forLanguageTag("es-CO"), delta)} puntos frente al período anterior"
                            delta < 0 -> "${"%.1f".format(Locale.forLanguageTag("es-CO"), delta)} puntos frente al período anterior"
                            else -> "Sin cambio frente al período anterior"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

private val formatoPeriodo: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es-CO"))

/** Comparación Canvas sin incorporar una librería de gráficas. */
@Composable
private fun GraficoCumplimiento(porcentaje: Double, anterior: Double?) {
    val fondo = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f)
    val avance = MaterialTheme.colorScheme.onPrimaryContainer
    val previo = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f)
    val descripcion = if (anterior == null) {
        "Cumplimiento actual: $porcentaje por ciento"
    } else {
        "Cumplimiento actual: $porcentaje por ciento; período anterior: $anterior por ciento"
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (anterior == null) 18.dp else 42.dp)
            .semantics { contentDescription = descripcion },
    ) {
        val altoBarra = size.height / if (anterior == null) 1f else 2.5f
        drawRect(color = fondo, size = androidx.compose.ui.geometry.Size(size.width, altoBarra))
        drawRect(
            color = avance,
            size = androidx.compose.ui.geometry.Size(
                size.width * (porcentaje / 100.0).toFloat().coerceIn(0f, 1f),
                altoBarra,
            ),
        )
        if (anterior != null) {
            val y = size.height - altoBarra
            drawRect(
                color = fondo,
                topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                size = androidx.compose.ui.geometry.Size(size.width, altoBarra),
            )
            drawRect(
                color = previo,
                topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                size = androidx.compose.ui.geometry.Size(
                    size.width * (anterior / 100.0).toFloat().coerceIn(0f, 1f),
                    altoBarra,
                ),
            )
        }
    }
}

@Composable
private fun TarjetaEvidencias(reporte: Reporte) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Evidencias", style = MaterialTheme.typography.titleMedium)
            Dato("Total recibidas", reporte.evidenciasTotales.toString())
            Dato(
                etiqueta = "Marcadas para revisión",
                valor = reporte.evidenciasSospechosas.toString(),
                // Aquí sí se resalta con el color de fraude —no con el de error, que ahora
                // significa "incidencia operativa"— porque esto sí es un veredicto
                // antifraude. Y sólo cuando hay alguna: pintar un cero en rojo sería
                // alarmismo.
                resaltar = reporte.evidenciasSospechosas > 0,
            )
        }
    }
}

@Composable
private fun FilaMotivo(alerta: AlertaAgregada) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = alerta.motivo.tituloLegible(),
                style = MaterialTheme.typography.labelLarge,
            )
            Text("${alerta.conteo}", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            text = alerta.motivo.descripcion,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FilaSede(sede: ResumenSede) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(sede.nombreSede, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${sede.visitasCompletadas}/${sede.visitasAsignadas} visitas" +
                    if (sede.evidenciasSospechosas > 0) " · ${sede.evidenciasSospechosas} para revisar" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Dato(etiqueta: String, valor: String, resaltar: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = valor,
            style = MaterialTheme.typography.bodyMedium,
            color = if (resaltar) {
                MaterialTheme.colorScheme.alertaFraude
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

private fun com.verificlean.app.domain.model.MotivoAlerta.tituloLegible(): String = when (this) {
    com.verificlean.app.domain.model.MotivoAlerta.FUERA_DE_GEOFENCE -> "Fuera de la zona esperada"
    com.verificlean.app.domain.model.MotivoAlerta.IMAGEN_DUPLICADA -> "Imagen repetida"
    com.verificlean.app.domain.model.MotivoAlerta.DISPOSITIVO_NO_CONFIABLE -> "Dispositivo no confiable"
    com.verificlean.app.domain.model.MotivoAlerta.MOCK_LOCATION -> "Ubicación simulada"
    com.verificlean.app.domain.model.MotivoAlerta.PRECISION_GPS_BAJA -> "Ubicación poco precisa"
    com.verificlean.app.domain.model.MotivoAlerta.DISPOSITIVO_NO_VINCULADO -> "Dispositivo distinto al registrado"
    com.verificlean.app.domain.model.MotivoAlerta.HASH_NO_COINCIDE -> "Archivo alterado"
    com.verificlean.app.domain.model.MotivoAlerta.INTEGRIDAD_NO_EVALUADA -> "Dispositivo sin verificar"
    com.verificlean.app.domain.model.MotivoAlerta.TOKEN_INTEGRIDAD_REUTILIZADO -> "Verificación reutilizada"
    com.verificlean.app.domain.model.MotivoAlerta.TOKEN_INTEGRIDAD_CADUCADO -> "Verificación vencida"
    com.verificlean.app.domain.model.MotivoAlerta.CHECKLIST_ALTERADO -> "Revisión de aseo alterada"
    com.verificlean.app.domain.model.MotivoAlerta.SIN_UBICACION -> "Sin ubicación"
    com.verificlean.app.domain.model.MotivoAlerta.DESCONOCIDO -> "Alerta del servidor"
}

private fun Reporte.aCsv(): String = buildString {
    appendLine("VerifiClean - reporte de cumplimiento")
    appendLine("Cliente,${csv(nombreCliente ?: clienteId)}")
    appendLine("Desde,${csv(desde.toString())}")
    appendLine("Hasta,${csv(hasta.toString())}")
    appendLine("Visitas asignadas,$visitasAsignadas")
    appendLine("Visitas completadas,$visitasCompletadas")
    appendLine("Cumplimiento,${cumplimientoPorcentaje}%")
    appendLine("Evidencias para revisar,$evidenciasSospechosas")
    appendLine()
    appendLine("Sede,Visitas asignadas,Visitas completadas,Evidencias para revisar")
    porSede.forEach { sede ->
        appendLine(
            "${csv(sede.nombreSede)},${sede.visitasAsignadas}," +
                "${sede.visitasCompletadas},${sede.evidenciasSospechosas}",
        )
    }
}

private fun csv(valor: String): String = "\"${valor.replace("\"", "\"\"")}\""
