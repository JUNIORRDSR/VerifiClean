package com.verificlean.app.ui.pantallas.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.verificlean.app.R
import com.verificlean.app.domain.model.Evidencia
import com.verificlean.app.domain.model.EstadoRevisionAlerta
import com.verificlean.app.domain.model.MotivoAlerta
import com.verificlean.app.domain.model.VeredictoIntegridad
import com.verificlean.app.domain.model.VisitaDetalleAdmin
import com.verificlean.app.ui.componentes.CargandoPantalla
import com.verificlean.app.ui.componentes.DistintivoSospechosa
import com.verificlean.app.ui.componentes.EtiquetaEstado
import com.verificlean.app.ui.componentes.FilaAlerta
import com.verificlean.app.ui.componentes.MensajeError
import com.verificlean.app.ui.theme.alertaFraude
import com.verificlean.app.ui.theme.contenedorAlertaFraude
import com.verificlean.app.ui.theme.sobreContenedorAlertaFraude
import androidx.compose.ui.res.stringResource
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val formatoHora: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm:ss", Locale.forLanguageTag("es-CO"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalleAdminScreen(
    visitId: String,
    alVolver: () -> Unit,
    viewModel: DetalleAdminViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()

    LaunchedEffect(visitId) { viewModel.cargar(visitId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(estado.detalle?.detalle?.visita?.siteNombreSede ?: "Visita") },
                navigationIcon = {
                    IconButton(onClick = alVolver) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
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
                item { CabeceraAdmin(detalle) }

                // Las alertas van arriba del todo, antes que las fotos: quien abre esta
                // pantalla desde el filtro de sospechosas viene a leer exactamente esto.
                if (detalle.alertas.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.admin_alertas),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    items(detalle.alertas, key = { it.motivo.name }) { alerta ->
                        FilaAlerta(motivo = alerta.motivo, conteo = alerta.conteo)
                    }
                } else {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "Sin señales automáticas",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                // Ausencia de alertas no equivale a inocencia: el texto
                                // conserva la responsabilidad de revisión humana.
                                Text(
                                    "Revisa las evidencias antes de cerrar el caso.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }

                if (detalle.detalle.visita.tieneSospechosas) {
                    item {
                        RevisionAlertaCard(
                            detalle = detalle,
                            guardando = estado.guardandoRevision,
                            mensaje = estado.mensajeRevision,
                            error = estado.errorRevision,
                            alGuardar = viewModel::guardarRevision,
                        )
                    }
                }

                item {
                    Text(
                        stringResource(R.string.admin_evidencias),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                if (detalle.evidenciasCompletas.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Esta visita todavía no tiene evidencias.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                }

                itemsIndexed(
                    detalle.evidenciasCompletas,
                    key = { _, evidencia -> evidencia.id },
                ) { indice, evidencia ->
                    TarjetaEvidencia(
                        evidencia = evidencia,
                        radioPermitido = detalle.detalle.siteRadioPermitidoMetros,
                        numero = indice + 1,
                        alRecargar = viewModel::recargar,
                    )
                }

                item { Column(Modifier.height(24.dp)) {} }
            }
        }
    }
}

@Composable
private fun RevisionAlertaCard(
    detalle: VisitaDetalleAdmin,
    guardando: Boolean,
    mensaje: String?,
    error: String?,
    alGuardar: (EstadoRevisionAlerta, String) -> Unit,
) {
    val revision = detalle.revisionAlerta
    var estadoSeleccionado by remember(revision.estado, revision.revisadaEn) {
        mutableStateOf(revision.estado)
    }
    var nota by remember(revision.nota, revision.revisadaEn) {
        mutableStateOf(revision.nota.orEmpty())
    }
    val notaObligatoria = estadoSeleccionado == EstadoRevisionAlerta.JUSTIFICADA ||
        estadoSeleccionado == EstadoRevisionAlerta.ESCALADA

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Seguimiento de la alerta", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = when (revision.estado) {
                        EstadoRevisionAlerta.PENDIENTE -> "Este caso todavía requiere una decisión."
                        EstadoRevisionAlerta.REVISADA -> "Caso revisado."
                        EstadoRevisionAlerta.JUSTIFICADA -> "Caso cerrado con justificación."
                        EstadoRevisionAlerta.ESCALADA -> "Caso escalado para seguimiento."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (revision.revisadaPorNombre != null && revision.revisadaEn != null) {
                    Text(
                        text = "${revision.revisadaPorNombre} · ${formatoHora.format(revision.revisadaEn.atZone(ZoneId.systemDefault()))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            EstadoRevisionAlerta.entries.chunked(2).forEach { fila ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fila.forEach { opcion ->
                        FilterChip(
                            selected = estadoSeleccionado == opcion,
                            onClick = { estadoSeleccionado = opcion },
                            enabled = !guardando,
                            label = {
                                Text(
                                    when (opcion) {
                                        EstadoRevisionAlerta.PENDIENTE -> "Pendiente"
                                        EstadoRevisionAlerta.REVISADA -> "Revisada"
                                        EstadoRevisionAlerta.JUSTIFICADA -> "Justificada"
                                        EstadoRevisionAlerta.ESCALADA -> "Escalada"
                                    },
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (estadoSeleccionado != EstadoRevisionAlerta.PENDIENTE) {
                OutlinedTextField(
                    value = nota,
                    onValueChange = { if (it.length <= 1000) nota = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !guardando,
                    label = { Text(if (notaObligatoria) "Nota obligatoria" else "Nota opcional") },
                    supportingText = { Text("${nota.length}/1000") },
                    minLines = 3,
                    maxLines = 6,
                )
            } else if (revision.estado != EstadoRevisionAlerta.PENDIENTE) {
                Text(
                    "Reabrir borra la nota, el autor y la hora del cierre anterior.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            mensaje?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = { alGuardar(estadoSeleccionado, nota) },
                enabled = !guardando,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (guardando) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (estadoSeleccionado == EstadoRevisionAlerta.PENDIENTE &&
                        revision.estado != EstadoRevisionAlerta.PENDIENTE
                    ) {
                        "Reabrir caso"
                    } else {
                        "Guardar revisión"
                    },
                )
            }
        }
    }
}

@Composable
private fun CabeceraAdmin(detalle: VisitaDetalleAdmin) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Contexto de la visita", style = MaterialTheme.typography.labelLarge)
            Text(detalle.detalle.visita.siteNombreCliente, style = MaterialTheme.typography.titleLarge)
            Text(
                "Supervisor · ${detalle.detalle.visita.supervisorNombre}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(detalle.detalle.siteDireccion, style = MaterialTheme.typography.bodyLarge)
            detalle.detalle.siteRadioPermitidoMetros?.let { radio ->
                Text(
                    text = "Radio permitido: ${metros(radio)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EtiquetaEstado(detalle.detalle.visita.estado, Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * Metros con un decimal sólo si lo tiene.
 *
 * Antes se truncaba con `toInt()`: una sede configurada a 150,9 m se juzgaba con 150,9 en
 * el servidor y se mostraba como "150 m", así que el admin no veía el umbral que había
 * puesto. Lo mismo con distancias por debajo del metro.
 */
private fun metros(valor: Double): String =
    if (valor % 1.0 == 0.0) "${valor.toLong()} m"
    else String.format(Locale.forLanguageTag("es-CO"), "%.1f m", valor)

@Composable
private fun TarjetaEvidencia(
    evidencia: Evidencia,
    radioPermitido: Double?,
    numero: Int,
    alRecargar: () -> Unit,
) {
    val colorBorde = if (evidencia.esSospechosa) {
        MaterialTheme.colorScheme.alertaFraude
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, colorBorde, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Evidencia $numero", style = MaterialTheme.typography.titleLarge)
                if (evidencia.esSospechosa) {
                    DistintivoSospechosa(evidencia.motivosAlerta.size)
                }
            }

            // La foto es el dato central de esta pantalla: si no carga hay que decirlo, no
            // dejar un hueco de 220 dp en blanco que parece un fallo de la app. Pasa en
            // desarrollo (las URLs `memoria://` no son resolubles) y pasará en producción
            // cuando una URL firmada caduque a los 15 minutos.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                var fallo by remember(evidencia.id) { mutableStateOf(evidencia.fotoUrl == null) }
                var cargandoFoto by remember(evidencia.id) { mutableStateOf(evidencia.fotoUrl != null) }

                if (!fallo) {
                    AsyncImage(
                        model = evidencia.fotoUrl,
                        contentDescription = "Evidencia fotográfica de la visita",
                        contentScale = ContentScale.Crop,
                        onLoading = { cargandoFoto = true },
                        onSuccess = { cargandoFoto = false },
                        onError = {
                            cargandoFoto = false
                            fallo = true
                        },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                    if (cargandoFoto) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("Cargando foto…", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ImageNotSupported,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "No se pudo cargar la foto",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "El enlace caduca a los 15 minutos. Recarga la visita.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = alRecargar) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text("Recargar foto", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }

            // El timestamp del servidor se etiqueta como tal a propósito: es el dato que
            // sostendría una decisión disciplinaria, y quien la tome debe saber que no
            // sale del reloj del teléfono del supervisor.
            GrupoPrueba(titulo = "Registro temporal", icono = Icons.Default.Schedule) {
                DatoEvidencia(
                    "Hora (servidor)",
                    formatoHora.format(evidencia.timestampServidor.atZone(ZoneId.systemDefault())),
                )
            }

            if (evidencia.motivosAlerta.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.contenedorAlertaFraude,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Señales detectadas",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.sobreContenedorAlertaFraude,
                    )
                    evidencia.motivosAlerta.forEach { motivo ->
                        Text(
                            text = "• ${motivo.descripcion}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.sobreContenedorAlertaFraude,
                        )
                    }
                }
            }

            // Cada bloque responde una pregunta probatoria distinta. La agrupación evita
            // que el admin trate un hash, una precisión GPS y un veredicto del dispositivo
            // como pares equivalentes dentro de una lista plana.
            GrupoPrueba(titulo = "Ubicación", icono = Icons.Default.LocationOn) {
                // Sin ubicación se dice "sin ubicación", no una distancia. Antes la app
                // mandaba 0,0 y presentaba una medición inventada como si fuera evidencia.
                if (evidencia.distanciaAlSitioMetros == null) {
                    DatoEvidencia(
                        etiqueta = "Distancia a la sede",
                        valor = "Sin ubicación",
                        resaltar = MotivoAlerta.SIN_UBICACION in evidencia.motivosAlerta,
                    )
                } else {
                    DatoEvidencia(
                        etiqueta = "Distancia a la sede",
                        valor = "${metros(evidencia.distanciaAlSitioMetros)}" +
                            if (radioPermitido != null) " (permitido ${metros(radioPermitido)})" else "",
                        // El servidor concede margen según la precisión GPS. La UI no
                        // reimplementa una regla incompleta: sólo resalta si ese fue el
                        // motivo que realmente emitió el motor antifraude.
                        resaltar = MotivoAlerta.FUERA_DE_GEOFENCE in evidencia.motivosAlerta,
                    )
                }
                evidencia.precisionMetros?.let {
                    DatoEvidencia("Precisión GPS", "±${it.toInt()} m")
                }
                if (evidencia.mockLocationReportada) {
                    DatoEvidencia("Ubicación simulada", "Sí", resaltar = true)
                }
            }

            GrupoPrueba(titulo = "Dispositivo", icono = Icons.Default.PhoneAndroid) {
                DatoEvidencia(
                    "Integridad del dispositivo",
                    when (evidencia.veredictoIntegridad) {
                        VeredictoIntegridad.CONFIABLE -> "Confiable"
                        VeredictoIntegridad.NO_CONFIABLE -> "No confiable"
                        VeredictoIntegridad.NO_EVALUADO -> "No evaluado"
                    },
                )
            }

            GrupoPrueba(titulo = "Integridad de la imagen", icono = Icons.Default.Fingerprint) {
                DatoEvidencia("Hash SHA-256", evidencia.hashSHA256.take(24) + "…")
            }
        }
    }
}

@Composable
private fun GrupoPrueba(
    titulo: String,
    icono: ImageVector,
    contenido: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icono,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(titulo, style = MaterialTheme.typography.titleSmall)
        }
        contenido()
    }
}

@Composable
private fun DatoEvidencia(etiqueta: String, valor: String, resaltar: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = valor,
            style = MaterialTheme.typography.bodyLarge,
            color = if (resaltar) {
                MaterialTheme.colorScheme.alertaFraude
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
