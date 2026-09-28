package com.verificlean.app.ui.pantallas.captura

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.verificlean.app.R
import kotlinx.coroutines.delay

/**
 * Captura de evidencia.
 *
 * **La foto sólo puede venir de esta cámara.** No hay selector de galería, ni
 * `ACTION_PICK`, ni intent a la cámara del sistema. Un intent a la app de cámara
 * devolvería un `Uri` a un archivo del almacenamiento compartido, y ese archivo pudo
 * haberse puesto ahí de cualquier forma — que es exactamente el fraude que este
 * producto existe para impedir. Con CameraX, los bytes salen del sensor y llegan al
 * repositorio sin pasar por ningún sitio donde puedan sustituirse.
 */
@Composable
fun CapturaScreen(
    visitId: String,
    alTerminar: () -> Unit,
    viewModel: CapturaViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val contexto = LocalContext.current
    val duenoCicloVida = LocalLifecycleOwner.current

    var camaraConcedida by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(contexto, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val solicitarPermisos = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        camaraConcedida = ContextCompat.checkSelfPermission(
            contexto,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }
    DisposableEffect(duenoCicloVida, contexto) {
        val observador = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_RESUME) {
                camaraConcedida = ContextCompat.checkSelfPermission(
                    contexto,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        duenoCicloVida.lifecycle.addObserver(observador)
        onDispose { duenoCicloVida.lifecycle.removeObserver(observador) }
    }

    val capturador = remember { ImageCapture.Builder().build() }

    LaunchedEffect(visitId) { viewModel.cargarSede(visitId) }

    LaunchedEffect(estado.guardada) {
        if (estado.guardada) {
            // Esta pausa sólo hace legible la confirmación y es siempre idéntica. Ya no
            // depende de GPS, integridad ni de la respuesta del servidor.
            delay(1_000)
            alTerminar()
        }
    }

    if (!camaraConcedida) {
        SolicitudPermisos(
            mensaje = "La cámara toma la evidencia. La ubicación se consulta una sola vez después de guardar la foto; si no está disponible, la foto no se pierde.",
            alSolicitar = {
                solicitarPermisos.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            alAbrirAjustes = {
                contexto.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${contexto.packageName}"),
                    ),
                )
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { vista ->
                    val futuro = ProcessCameraProvider.getInstance(ctx)
                    futuro.addListener({
                        val proveedor = futuro.get()
                        val preview = Preview.Builder().build().apply {
                            surfaceProvider = vista.surfaceProvider
                        }
                        // Se desvincula todo antes de volver a vincular: si la pantalla
                        // se recrea (rotación, vuelta desde ajustes), CameraX lanza si
                        // el caso de uso ya estaba enlazado.
                        proveedor.unbindAll()
                        proveedor.bindToLifecycle(
                            duenoCicloVida,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capturador,
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Cabecera sobre el visor: sin ella la pantalla no dice de qué visita es ni ofrece
        // salida visible, y el supervisor sólo puede volver con el gesto del sistema. En
        // una app que se usa con una mano y prisa, eso no basta.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = alTerminar, enabled = !estado.procesando && !estado.guardada) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Cancelar captura",
                    tint = Color.White,
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.captura_titulo),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (estado.nombreSede.isNotBlank()) {
                    Text(
                        text = estado.nombreSede,
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                estado.guardada -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(56.dp),
                    )
                    Text(
                        text = stringResource(R.string.captura_guardada),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
                estado.procesando -> {
                    Text(
                        text = "Guardando la evidencia en este dispositivo…",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> {
                    Text(
                        text = "Encuadra el área visitada y mantén el teléfono estable",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }

            if (!estado.guardada && estado.error != null) {
                Text(
                    text = estado.error.orEmpty(),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(12.dp),
                )
            }

            if (!estado.guardada) {
                FloatingActionButton(
                    onClick = {
                        // El turno se toma de forma atómica ANTES de disparar. Comprobar
                        // `estado.procesando` no bastaba: ese flag sólo se ponía al llegar
                        // el callback y dos toques rápidos disparaban dos veces.
                        if (!viewModel.intentarTomarElTurno()) return@FloatingActionButton

                        capturador.takePicture(
                            ContextCompat.getMainExecutor(contexto),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(imagen: ImageProxy) {
                                    val jpeg = try {
                                        imagen.aByteArray()
                                    } finally {
                                        // Sin cerrar, CameraX agota su búfer y las capturas
                                        // siguientes se quedan colgadas sin error.
                                        imagen.close()
                                    }
                                    viewModel.procesarFoto(visitId, jpeg)
                                }

                                override fun onError(excepcion: ImageCaptureException) {
                                    viewModel.liberarTurno(
                                        "No se pudo tomar la foto: ${excepcion.message}",
                                    )
                                }
                            },
                        )
                    },
                    shape = CircleShape,
                    containerColor = if (estado.procesando) Color.Gray else Color.White,
                    modifier = Modifier.size(72.dp),
                ) {
                    if (estado.procesando) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = stringResource(R.string.captura_disparar),
                            tint = Color.Black,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * `ImageProxy` en formato JPEG trae un único plano con el archivo completo, ya
 * comprimido por el ISP del dispositivo y **con su EXIF intacto**. Se copia tal cual:
 * decodificar a `Bitmap` y recomprimir borraría el EXIF y cambiaría el hash respecto a
 * lo que salió del sensor.
 */
private fun ImageProxy.aByteArray(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

@Composable
private fun SolicitudPermisos(
    mensaje: String,
    alSolicitar: () -> Unit,
    alAbrirAjustes: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "Permisos para registrar evidencia",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            mensaje,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = alSolicitar, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.captura_conceder))
        }
        TextButton(onClick = alAbrirAjustes, modifier = Modifier.padding(top = 4.dp)) {
            Text("Abrir ajustes de la app")
        }
    }
}
