package com.verificlean.app.ui.componentes

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.MotivoAlerta
import com.verificlean.app.ui.theme.alertaFraude
import com.verificlean.app.ui.theme.contenedorAlertaFraude
import com.verificlean.app.ui.theme.sobreContenedorAlertaFraude

/**
 * Piezas compartidas entre pantallas. Sólo entran aquí las que ya se usan en dos sitios
 * — un componente "por si acaso" es código que nadie mantiene.
 */

@Composable
fun CargandoPantalla(modifier: Modifier = Modifier) {
    // El esqueleto conserva la estructura de una lista y reduce el salto visual al
    // llegar los datos; el texto hace que la carga no dependa sólo de la animación.
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
                text = "Cargando contenido…",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        repeat(3) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), RoundedCornerShape(6.dp)),
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.88f)
                        .height(12.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.14f), RoundedCornerShape(6.dp)),
                )
                Box(
                    Modifier
                        .width(96.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}

@Composable
fun MensajeVacio(texto: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = "Nada por aquí",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        Text(
            text = texto,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        }
    }
}

@Composable
fun MensajeError(texto: String, alReintentar: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = texto,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (alReintentar != null) {
            Button(onClick = alReintentar) { Text("Reintentar") }
        }
    }
}

/**
 * Distintivo de estado de una visita. El color viene del esquema, no de constantes
 * sueltas, para que siga funcionando en tema oscuro.
 */
@Composable
fun EtiquetaEstado(estado: EstadoVisita, modifier: Modifier = Modifier) {
    val (texto, fondo, tinta) = when (estado) {
        EstadoVisita.PENDIENTE -> Triple(
            "Pendiente",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EstadoVisita.EN_PROGRESO -> Triple(
            "En progreso",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        EstadoVisita.COMPLETADA -> Triple(
            "Completada",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
        )
    }

    Box(
        modifier
            .background(fondo, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(texto, style = MaterialTheme.typography.labelMedium, color = tinta)
    }
}

/**
 * Marca de evidencia sospechosa.
 *
 * Es el único elemento de la app que usa el color de error, y va siempre con texto —
 * nunca sólo con el icono. Un punto rojo sin etiqueta obliga a aprenderse un código de
 * colores, y quien mira este panel lo hace para decidir si abre una investigación
 * laboral contra una persona: la razón tiene que estar escrita.
 */
@Composable
fun DistintivoSospechosa(cantidadMotivos: Int = 0, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.contenedorAlertaFraude, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.sobreContenedorAlertaFraude,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (cantidadMotivos > 1) "Sospechosa · $cantidadMotivos alertas" else "Sospechosa",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.sobreContenedorAlertaFraude,
        )
    }
}

@Composable
fun FilaAlerta(motivo: MotivoAlerta, conteo: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.contenedorAlertaFraude, RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.alertaFraude,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = motivo.name.replace('_', ' ') + if (conteo > 1) " ×$conteo" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.sobreContenedorAlertaFraude,
            )
            Text(
                text = motivo.descripcion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.sobreContenedorAlertaFraude,
            )
        }
    }
}

/** Aviso de evidencias en cola. Se muestra sólo si hay alguna. */
@Composable
fun BannerPendientes(cantidad: Int, alSincronizar: () -> Unit, modifier: Modifier = Modifier) {
    if (cantidad <= 0) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.CloudUpload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = if (cantidad == 1) {
                "1 evidencia está guardada y espera conexión"
            } else {
                "$cantidad evidencias están guardadas y esperan conexión"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = alSincronizar) { Text("Enviar ahora") }
    }
}
