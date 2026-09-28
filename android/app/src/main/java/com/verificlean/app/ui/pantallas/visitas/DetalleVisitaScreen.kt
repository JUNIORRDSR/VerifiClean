package com.verificlean.app.ui.pantallas.visitas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verificlean.app.R
import com.verificlean.app.domain.model.ChecklistItem
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.RespuestaChecklist
import com.verificlean.app.ui.componentes.CargandoPantalla
import com.verificlean.app.ui.componentes.EtiquetaEstado
import com.verificlean.app.ui.componentes.MensajeError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalleVisitaScreen(
    visitId: String,
    alCapturar: () -> Unit,
    alVolver: () -> Unit,
    recargarPorEvidenciaNueva: Boolean = false,
    alConsumirRecarga: () -> Unit = {},
    viewModel: DetalleVisitaViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(visitId) { viewModel.cargar(visitId) }

    // Al volver de la captura hay una evidencia más. Sin recargar, la pantalla conserva el
    // contador anterior, sigue diciendo "falta evidencia" y deja el botón de completar
    // apagado justo después de haber hecho la foto.
    LaunchedEffect(recargarPorEvidenciaNueva) {
        if (recargarPorEvidenciaNueva) {
            viewModel.recargarConservandoRespuestas()
            alConsumirRecarga()
        }
    }

    LaunchedEffect(estado.error) {
        estado.error?.let {
            snackbar.showSnackbar(it)
            viewModel.descartarError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(estado.detalle?.visita?.siteNombreSede ?: "Visita") },
                navigationIcon = {
                    IconButton(onClick = alVolver) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
        floatingActionButton = {
            // Sin botón de captura en una visita cerrada: el servidor la rechazaría con
            // 409 y el supervisor habría hecho la foto para nada.
            if (estado.detalle?.visita?.estado != EstadoVisita.COMPLETADA) {
                ExtendedFloatingActionButton(
                    onClick = alCapturar,
                    icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                    text = { Text(stringResource(R.string.visita_tomar_foto)) },
                )
            }
        },
    ) { relleno ->
        val detalle = estado.detalle

        when {
            estado.cargando -> CargandoPantalla(Modifier.padding(relleno))

            detalle == null -> MensajeError(
                texto = estado.error ?: stringResource(R.string.error_generico),
                alReintentar = viewModel::recargar,
                modifier = Modifier.padding(relleno),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(relleno).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Cabecera(detalle, estado.evidenciasPendientes) }

                item {
                    ProgresoChecklist(
                        respondidos = estado.checklist.size - estado.itemsSinResponder.size,
                        total = estado.checklist.size,
                    )
                }

                itemsIndexed(estado.checklist, key = { _, item -> item.id }) { indice, item ->
                    FilaChecklist(
                        numero = indice + 1,
                        item = item,
                        habilitado = !estado.completada,
                        alResponder = { viewModel.responder(item.id, it) },
                        alAnotar = { viewModel.anotar(item.id, it) },
                    )
                }

                item {
                    OutlinedTextField(
                        value = estado.observaciones,
                        onValueChange = viewModel::alCambiarObservaciones,
                        label = { Text(stringResource(R.string.visita_observaciones)) },
                        enabled = !estado.completada,
                        minLines = 2,
                        supportingText = { Text("${estado.observaciones.length}/2000") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    if (estado.completada) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Visita completada.",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else {
                        Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Antes de completar",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            // El motivo por el que el botón está apagado se dice
                            // explícitamente: un botón gris sin explicación es la forma
                            // más rápida de generar una llamada al soporte.
                            if (detalle.visita.evidenciaCount == 0 && estado.evidenciasPendientes == 0) {
                                Text(
                                    text = "• Falta al menos una evidencia fotográfica.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (estado.evidenciasPendientes > 0) {
                                Text(
                                    text = if (estado.evidenciasPendientes == 1) {
                                        "• La foto está a salvo en este teléfono y espera envío. Podrás completar cuando llegue al servidor."
                                    } else {
                                        "• ${estado.evidenciasPendientes} fotos están a salvo y esperan envío. Podrás completar cuando lleguen al servidor."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (estado.itemsSinResponder.isNotEmpty()) {
                                Text(
                                    text = "• Faltan ${estado.itemsSinResponder.size} respuesta(s) del checklist.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = viewModel::completar,
                                enabled = estado.puedeCompletar,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (estado.completando) "Completando…" else
                                        stringResource(R.string.visita_completar),
                                )
                            }
                        }
                        }
                    }
                }

                // Espacio para que el FAB no tape el último elemento. Antes eran 80 dp y el
                // botón flotante quedaba encima del campo de Observaciones, que es donde el
                // supervisor escribe lo que encontró.
                item { Spacer(Modifier.height(120.dp)) }
            }
        }
    }
}

@Composable
private fun Cabecera(
    detalle: com.verificlean.app.domain.model.VisitaDetalle,
    pendientesLocales: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Cliente", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detalle.visita.siteNombreCliente, style = MaterialTheme.typography.titleLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(detalle.siteDireccion, style = MaterialTheme.typography.bodyLarge)
            }
            // El supervisor necesita reconocer la sede, no conocer el umbral con el que
            // se evalúa su ubicación. Retirar el radio evita convertir esta cabecera en
            // una pista para ajustar capturas al límite del control antifraude.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EtiquetaEstado(detalle.visita.estado)
                Text(
                    text = stringResource(R.string.visita_evidencias, detalle.visita.evidenciaCount) +
                        if (pendientesLocales > 0) " · $pendientesLocales esperando envío" else "",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ProgresoChecklist(respondidos: Int, total: Int) {
    val progreso = if (total == 0) 0f else respondidos.toFloat() / total
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Revisión de aseo",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Responde cada punto antes de completar la visita",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "$respondidos de $total",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // La barra responde la pregunta operativa de la pantalla —cuánto falta— sin
        // obligar a contar manualmente una lista larga.
        LinearProgressIndicator(
            progress = { progreso },
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
    }
}

@Composable
private fun FilaChecklist(
    numero: Int,
    item: ChecklistItem,
    habilitado: Boolean,
    alResponder: (RespuestaChecklist) -> Unit,
    alAnotar: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "$numero.",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(item.pregunta, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RespuestaChecklist.entries.forEach { opcion ->
                FilterChip(
                    selected = item.respuesta == opcion,
                    onClick = { alResponder(opcion) },
                    enabled = habilitado,
                    label = {
                        Text(
                            when (opcion) {
                                RespuestaChecklist.SI -> "Sí"
                                RespuestaChecklist.NO -> "No"
                                RespuestaChecklist.NA -> "N/A"
                            },
                        )
                    },
                )
            }
        }

        // La nota sólo aparece tras un "No": es donde hace falta explicar, y mostrarla
        // siempre llenaría la pantalla de campos vacíos que nadie rellena.
        if (item.respuesta == RespuestaChecklist.NO) {
            OutlinedTextField(
                value = item.nota.orEmpty(),
                onValueChange = alAnotar,
                label = { Text("¿Qué encontraste?") },
                enabled = habilitado,
                supportingText = { Text("${item.nota.orEmpty().length}/500") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

    }
    }
}
