package com.verificlean.app.ui.pantallas.gestion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verificlean.app.domain.model.Sitio
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.ui.componentes.CargandoPantalla
import com.verificlean.app.ui.componentes.MensajeVacio
import com.verificlean.app.ui.componentes.MensajeError
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val formatoDia: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es"))

/**
 * Asignación de una visita: sede + supervisor + día.
 *
 * Es la operación que hace existir el trabajo. Sin ella el supervisor abre la app y no
 * tiene nada que hacer, porque sólo consume visitas ya creadas.
 *
 * La pantalla está pensada para encadenar asignaciones: al confirmar, se limpia el
 * supervisor pero se conservan sede y fecha, que es como se planifica una jornada — la
 * misma sede recorrida por varias personas, o el mismo día repartido entre sedes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsignarVisitaScreen(
    alVolver: () -> Unit,
    viewModel: AsignarVisitaViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var buscarSede by remember { mutableStateOf("") }
    var buscarSupervisor by remember { mutableStateOf("") }

    LaunchedEffect(estado.asignada) {
        estado.asignada?.let { aviso ->
            snackbar.showSnackbar(aviso)
            viewModel.descartarAviso()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Asignar visita") },
                navigationIcon = {
                    IconButton(onClick = alVolver) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = viewModel::asignar,
                enabled = estado.puedeAsignar,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(if (estado.asignando) "Asignando…" else "Asignar visita")
            }
        },
    ) { relleno ->
        when {
            estado.cargando -> CargandoPantalla(Modifier.padding(relleno))

            estado.errorSedes != null -> MensajeError(
                texto = "No se pudieron cargar las sedes. ${estado.errorSedes}",
                alReintentar = viewModel::cargar,
                modifier = Modifier.padding(relleno),
            )

            estado.errorSupervisores != null -> MensajeError(
                texto = "No se pudo cargar el equipo. ${estado.errorSupervisores}",
                alReintentar = viewModel::cargar,
                modifier = Modifier.padding(relleno),
            )

            estado.faltaCatalogo -> MensajeVacio(
                if (estado.sedes.isEmpty()) {
                    "No hay sedes activas. Crea una sede antes de asignar visitas."
                } else {
                    "No hay supervisores activos. Da de alta al menos uno."
                },
                Modifier.padding(relleno),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(relleno).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (estado.error != null) {
                    item {
                        MensajeError(
                            texto = estado.error.orEmpty(),
                            alReintentar = viewModel::asignar,
                        )
                    }
                }
                item { Encabezado("1. Fecha") }
                item {
                    // Sólo día: la hora exacta no la usa ninguna regla antifraude, porque
                    // el servidor sella su propio timestamp al recibir la evidencia.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0L, 1L, 2L).forEach { dias ->
                            val fecha = LocalDate.now().plusDays(dias)
                            FilterChip(
                                selected = estado.fecha == fecha,
                                onClick = { viewModel.elegirFecha(fecha) },
                                label = {
                                    Text(
                                        when (dias) {
                                            0L -> "Hoy"
                                            1L -> "Mañana"
                                            else -> "Pasado"
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                item {
                    Text(
                        text = formatoDia.format(estado.fecha).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                item { Encabezado("2. Sede") }
                item {
                    OutlinedTextField(
                        value = buscarSede,
                        onValueChange = { buscarSede = it },
                        label = { Text("Buscar sede o cliente") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(
                    estado.sedes.filter {
                        it.nombreSede.contains(buscarSede, true) || it.nombreCliente.contains(buscarSede, true)
                    },
                    key = { it.id },
                ) { sede ->
                    FilaSeleccion(
                        titulo = sede.nombreSede,
                        subtitulo = "${sede.nombreCliente} · radio ${sede.radioPermitidoMetros.toInt()} m",
                        elegido = estado.sedeElegida?.id == sede.id,
                        alPulsar = { viewModel.elegirSede(sede) },
                    )
                }

                item { Encabezado("3. Supervisor") }
                item {
                    OutlinedTextField(
                        value = buscarSupervisor,
                        onValueChange = { buscarSupervisor = it },
                        label = { Text("Buscar supervisor") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(
                    estado.supervisores.filter {
                        it.nombre.contains(buscarSupervisor, true) || it.email.contains(buscarSupervisor, true)
                    },
                    key = { it.id },
                ) { supervisor ->
                    FilaSeleccion(
                        titulo = supervisor.nombre,
                        subtitulo = supervisor.email,
                        elegido = estado.supervisorElegido?.id == supervisor.id,
                        alPulsar = { viewModel.elegirSupervisor(supervisor) },
                    )
                }

                item { Column(Modifier.padding(bottom = 16.dp)) {} }
            }
        }
    }
}

@Composable
private fun Encabezado(texto: String) {
    Text(
        text = texto,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun FilaSeleccion(
    titulo: String,
    subtitulo: String,
    elegido: Boolean,
    alPulsar: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = alPulsar),
        colors = CardDefaults.cardColors(
            containerColor = if (elegido) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (elegido) 0.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(titulo, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitulo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (elegido) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Seleccionado",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
