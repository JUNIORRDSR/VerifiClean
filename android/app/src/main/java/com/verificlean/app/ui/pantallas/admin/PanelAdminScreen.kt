package com.verificlean.app.ui.pantallas.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verificlean.app.R
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.EstadoRevisionAlerta
import com.verificlean.app.domain.model.VisitaResumenAdmin
import com.verificlean.app.ui.componentes.CargandoPantalla
import com.verificlean.app.ui.componentes.DistintivoSospechosa
import com.verificlean.app.ui.componentes.EtiquetaEstado
import com.verificlean.app.ui.componentes.MensajeVacio
import com.verificlean.app.ui.componentes.MensajeError
import com.verificlean.app.ui.theme.contenedorAlertaFraude
import com.verificlean.app.ui.theme.sobreContenedorAlertaFraude
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val formatoFecha: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.forLanguageTag("es-CO"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelAdminScreen(
    alAbrirVisita: (String) -> Unit,
    viewModel: PanelAdminViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_titulo)) },
                actions = {
                    IconButton(onClick = viewModel::cargar, enabled = !estado.cargando) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refrescar")
                    }
                    IconButton(onClick = viewModel::cerrarSesion) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = stringResource(R.string.cerrar_sesion),
                        )
                    }
                },
            )
        },
    ) { relleno ->
        Column(Modifier.fillMaxSize().padding(relleno).padding(horizontal = 16.dp)) {
            if (estado.visitas.isNotEmpty()) {
                ResumenDeJornada(
                    visitas = estado.visitas.size,
                    periodo = estado.periodo,
                    hayMas = estado.hayMas,
                    paraRevisar = estado.visitas.count {
                        it.visita.tieneSospechosas &&
                            it.revisionAlerta.estado == EstadoRevisionAlerta.PENDIENTE
                    },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Periodo",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PeriodoPanel.entries.forEach { periodo ->
                        FilterChip(
                            selected = estado.periodo == periodo,
                            onClick = { viewModel.filtrarPorPeriodo(periodo) },
                            label = {
                                Text(
                                    when (periodo) {
                                        PeriodoPanel.AYER -> "Ayer"
                                        PeriodoPanel.HOY -> "Hoy"
                                        PeriodoPanel.TODO -> "Todo"
                                    },
                                )
                            },
                        )
                    }
                }
                Text(
                    text = "Revisión antifraude",
                    style = MaterialTheme.typography.labelLarge,
                )
                // Este filtro es acumulable; su bloque propio evita que parezca una
                // quinta opción del grupo excluyente de estados.
                FilterChip(
                    selected = estado.soloSospechosas,
                    onClick = viewModel::alternarSospechosas,
                    label = { Text(stringResource(R.string.admin_filtro_sospechosas)) },
                    leadingIcon = {
                        if (estado.soloSospechosas) {
                            Icon(
                                Icons.Default.WarningAmber,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.contenedorAlertaFraude,
                        selectedLabelColor = MaterialTheme.colorScheme.sobreContenedorAlertaFraude,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.sobreContenedorAlertaFraude,
                    ),
                )

                Text(
                    text = "Estado de la visita",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = estado.filtroEstado == null,
                        onClick = { viewModel.filtrarPorEstado(null) },
                        label = { Text("Todas") },
                    )
                    EstadoVisita.entries.forEach { estadoVisita ->
                        FilterChip(
                            selected = estado.filtroEstado == estadoVisita,
                            onClick = { viewModel.filtrarPorEstado(estadoVisita) },
                            label = {
                                Text(
                                    when (estadoVisita) {
                                        EstadoVisita.PENDIENTE -> "Pendientes"
                                        EstadoVisita.EN_PROGRESO -> "En progreso"
                                        EstadoVisita.COMPLETADA -> "Completadas"
                                    },
                                )
                            },
                        )
                    }
                }
            }

            if (estado.cargando && estado.visitas.isNotEmpty()) {
                // Al cambiar filtros se conserva la lista previa y se indica que el
                // bloque de resultados —no toda la pantalla— se está actualizando.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                estado.cargando && estado.visitas.isEmpty() -> CargandoPantalla()

                estado.error != null && estado.visitas.isEmpty() -> MensajeError(
                    texto = estado.error.orEmpty(),
                    alReintentar = viewModel::cargar,
                )

                estado.visitas.isEmpty() -> MensajeVacio(
                    when (estado.periodo) {
                        PeriodoPanel.AYER -> "No hubo visitas ayer con estos filtros."
                        PeriodoPanel.HOY -> "No hay visitas hoy con estos filtros."
                        PeriodoPanel.TODO -> stringResource(R.string.admin_sin_resultados)
                    },
                )

                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 12.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (estado.error != null) {
                        item {
                            MensajeError(
                                texto = estado.error.orEmpty(),
                                alReintentar = viewModel::cargar,
                            )
                        }
                    }
                    item {
                        Text(
                            text = "${estado.visitas.size} visita(s) en esta página",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(estado.visitas, key = { it.visita.id }) { resumen ->
                        TarjetaVisitaAdmin(resumen) { alAbrirVisita(resumen.visita.id) }
                    }
                    if (estado.hayMas) {
                        item {
                            TextButton(
                                onClick = viewModel::cargarMas,
                                enabled = !estado.cargandoMas,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (estado.cargandoMas) "Cargando…" else "Cargar más")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResumenDeJornada(
    visitas: Int,
    paraRevisar: Int,
    periodo: PeriodoPanel,
    hayMas: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (periodo) {
                    PeriodoPanel.AYER -> "Resumen de ayer"
                    PeriodoPanel.HOY -> "Resumen de hoy"
                    PeriodoPanel.TODO -> "Resumen del historial"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (paraRevisar == 0) {
                    "$visitas visitas cargadas. No hay alertas entre estos resultados." +
                        if (hayMas) " Hay más páginas." else ""
                } else {
                    "$paraRevisar de $visitas resultados requieren revisión. Puede haber más en las páginas siguientes."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TarjetaVisitaAdmin(resumen: VisitaResumenAdmin, alPulsar: () -> Unit) {
    val visita = resumen.visita
    val pendienteDeRevision = visita.tieneSospechosas &&
        resumen.revisionAlerta.estado == EstadoRevisionAlerta.PENDIENTE
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = alPulsar),
        colors = CardDefaults.cardColors(
            // El fondo de alerta se aplica a la tarjeta entera, no sólo a una etiqueta:
            // en una lista larga, lo que hay que poder barrer con la vista es "cuáles
            // revisar", y para eso el bloque de color funciona mejor que un distintivo.
            containerColor = if (pendienteDeRevision) {
                MaterialTheme.colorScheme.contenedorAlertaFraude
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (pendienteDeRevision) {
                MaterialTheme.colorScheme.sobreContenedorAlertaFraude
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(visita.siteNombreSede, style = MaterialTheme.typography.titleLarge)
            Text(
                text = visita.siteNombreCliente,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Supervisor · ${visita.supervisorNombre}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = formatoFecha.format(visita.fechaAsignada.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EtiquetaEstado(visita.estado)
                Text(
                    text = stringResource(R.string.visita_evidencias, visita.evidenciaCount),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (visita.tieneSospechosas) DistintivoSospechosa()
            }
            if (visita.tieneSospechosas) {
                Text(
                    text = when (resumen.revisionAlerta.estado) {
                        EstadoRevisionAlerta.PENDIENTE -> "Pendiente de revisión"
                        EstadoRevisionAlerta.REVISADA -> "Revisada"
                        EstadoRevisionAlerta.JUSTIFICADA -> "Justificada"
                        EstadoRevisionAlerta.ESCALADA -> "Escalada"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (pendienteDeRevision) {
                        MaterialTheme.colorScheme.sobreContenedorAlertaFraude
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
