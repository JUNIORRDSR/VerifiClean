package com.verificlean.app.ui.pantallas.visitas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.verificlean.app.domain.model.Visita
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.ui.componentes.BannerPendientes
import com.verificlean.app.ui.componentes.CargandoPantalla
import com.verificlean.app.ui.componentes.EtiquetaEstado
import com.verificlean.app.ui.componentes.MensajeVacio
import com.verificlean.app.ui.componentes.MensajeError
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val formatoFecha: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.forLanguageTag("es-CO"))
private val formatoHoraActualizacion: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("es-CO"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitasScreen(
    alAbrirVisita: (String) -> Unit,
    viewModel: VisitasViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.visitas_titulo)) },
                actions = {
                    IconButton(onClick = viewModel::refrescar, enabled = !estado.refrescando) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(relleno)
                .padding(horizontal = 16.dp),
        ) {
            BannerPendientes(
                cantidad = estado.pendientesDeSubir,
                alSincronizar = viewModel::sincronizarPendientes,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            estado.fallidas.forEach { fallida ->
                FilaEvidenciaFallida(
                    sede = fallida.visitaNombreSede,
                    motivo = fallida.ultimoError,
                    alReintentar = { viewModel.reintentarFallida(fallida.clienteEvidenciaId) },
                )
            }

            EstadoActualizacion(
                refrescando = estado.refrescando,
                ultimaActualizacionEpoch = estado.ultimaActualizacionMillis,
                usandoCopiaLocal = estado.error != null && estado.visitas.isNotEmpty(),
            )

            if (estado.error != null && estado.visitas.isNotEmpty()) {
                AvisoCargaLocal(
                    mensaje = estado.error.orEmpty(),
                    alReintentar = viewModel::refrescar,
                )
            }

            when {
                estado.visitas.isEmpty() && (estado.inicializando || estado.refrescando) ->
                    CargandoPantalla()

                estado.visitas.isEmpty() && estado.error != null -> MensajeError(
                    texto = estado.error.orEmpty(),
                    alReintentar = viewModel::refrescar,
                )

                estado.visitas.isEmpty() -> MensajeVacio(
                    texto = stringResource(R.string.visitas_vacio) + "\n" +
                        stringResource(R.string.visitas_vacio_ayuda),
                )

                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            text = "Abre la primera visita pendiente y sigue los pasos para registrarla.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    val ordenadas = estado.visitas.sortedWith(
                        compareBy<Visita> {
                            when (it.estado) {
                                EstadoVisita.EN_PROGRESO -> 0
                                EstadoVisita.PENDIENTE -> 1
                                EstadoVisita.COMPLETADA -> 2
                            }
                        }.thenBy { it.fechaAsignada },
                    )
                    items(ordenadas, key = { it.id }) { visita ->
                        TarjetaVisita(
                            visita = visita,
                            destacada = visita.id == ordenadas.firstOrNull()?.id &&
                                visita.estado != EstadoVisita.COMPLETADA,
                            alPulsar = { alAbrirVisita(visita.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EstadoActualizacion(
    refrescando: Boolean,
    ultimaActualizacionEpoch: Long?,
    usandoCopiaLocal: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = when {
                    refrescando -> stringResource(R.string.visitas_actualizando)
                    usandoCopiaLocal -> "Sin conexión · visitas guardadas en este teléfono"
                    ultimaActualizacionEpoch != null -> stringResource(
                        R.string.visitas_ultima_actualizacion,
                        formatoHoraActualizacion.format(
                            Instant.ofEpochMilli(ultimaActualizacionEpoch).atZone(ZoneId.systemDefault()),
                        ),
                    )
                    else -> "Preparando tus visitas"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (refrescando) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AvisoCargaLocal(mensaje: String, alReintentar: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$mensaje Tus visitas guardadas siguen disponibles.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = alReintentar) { Text("Reintentar") }
    }
}

/**
 * Evidencia que el servidor rechazó de forma definitiva.
 *
 * Antes estas filas existían en la base pero ninguna pantalla las mostraba: la foto
 * desaparecía en silencio y el supervisor creía haber registrado una visita que nunca
 * llegó. Se enseñan con el motivo y con una acción real de reintento.
 */
@Composable
private fun FilaEvidenciaFallida(sede: String, motivo: String?, alReintentar: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "No se pudo enviar la evidencia de $sede",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (!motivo.isNullOrBlank()) {
                Text(
                    text = motivo,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        TextButton(onClick = alReintentar) { Text(stringResource(R.string.sync_reintentar)) }
    }
}

@Composable
private fun TarjetaVisita(visita: Visita, destacada: Boolean, alPulsar: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = alPulsar),
        colors = CardDefaults.cardColors(
            containerColor = if (destacada) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    visita.siteNombreSede,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = visita.siteNombreCliente,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatoFecha.format(visita.fechaAsignada.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (destacada) {
                    Text(
                        "Empieza aquí",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EtiquetaEstado(visita.estado)
                if (visita.evidenciaCount > 0) {
                    Text(
                        text = stringResource(R.string.visita_evidencias, visita.evidenciaCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Sin distintivo antifraude, a propósito. La lista del supervisor sólo
                // comunica el avance operativo; cualquier señal o veredicto pertenece
                // exclusivamente al panel del administrador para no crear un oráculo.
                TextButton(onClick = alPulsar, modifier = Modifier.weight(1f)) {
                    Text(
                        when (visita.estado) {
                            EstadoVisita.PENDIENTE -> "Iniciar visita"
                            EstadoVisita.EN_PROGRESO -> "Continuar visita"
                            EstadoVisita.COMPLETADA -> "Ver visita"
                        },
                    )
                }
            }
        }
    }
}
