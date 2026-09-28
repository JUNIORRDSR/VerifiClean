package com.verificlean.app.ui.pantallas.gestion

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.verificlean.app.domain.model.Sitio
import com.verificlean.app.ui.componentes.CargandoPantalla
import com.verificlean.app.ui.componentes.MensajeVacio
import com.verificlean.app.ui.componentes.MensajeError

/**
 * Alta y listado de sedes de cliente.
 *
 * Sin esta pantalla el producto no se puede operar: una sede sólo existía creándola por
 * curl contra `POST /v1/sites`, y sin sedes no hay nada que geocercar ni visitas que
 * asignar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SedesScreen(
    alVolver: () -> Unit,
    viewModel: SedesViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(estado.mensaje) {
        estado.mensaje?.let { aviso ->
            snackbar.showSnackbar(aviso)
            viewModel.descartarAviso()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Sedes") },
                navigationIcon = {
                    IconButton(onClick = alVolver) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::abrirFormulario,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Nueva sede") },
            )
        },
    ) { relleno ->
        when {
            estado.cargando -> CargandoPantalla(Modifier.padding(relleno))

            estado.error != null && estado.sedes.isEmpty() -> MensajeError(
                texto = estado.error.orEmpty(),
                alReintentar = viewModel::cargar,
                modifier = Modifier.padding(relleno),
            )

            estado.sedes.isEmpty() -> MensajeVacio(
                "Todavía no hay sedes. Crea la primera para poder asignar visitas.",
                Modifier.padding(relleno),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(relleno).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (estado.error != null) {
                    item { MensajeError(texto = estado.error.orEmpty()) }
                }
                items(estado.sedes, key = { it.id }) { TarjetaSede(it, viewModel::pedirCambioActivo) }
                item { Column(Modifier.padding(bottom = 100.dp)) {} }
            }
        }
    }

    estado.sitioPorCambiar?.let { sitio ->
        AlertDialog(
            onDismissRequest = viewModel::cancelarCambioActivo,
            title = { Text(if (sitio.activo) "Desactivar sede" else "Activar sede") },
            text = {
                Text(
                    if (sitio.activo) {
                        "No podrás asignar visitas nuevas a ${sitio.nombreSede}. Las visitas ya asignadas seguirán su curso."
                    } else {
                        "${sitio.nombreSede} volverá a estar disponible al asignar visitas."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmarCambioActivo,
                    enabled = !estado.cambiandoActivo,
                ) { Text(if (estado.cambiandoActivo) "Guardando…" else "Confirmar") }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::cancelarCambioActivo,
                    enabled = !estado.cambiandoActivo,
                ) { Text("Cancelar") }
            },
        )
    }

    if (estado.formularioAbierto) {
        DialogoNuevaSede(
            formulario = estado.formulario,
            clientes = estado.clientes,
            guardando = estado.guardando,
            errorGeneral = estado.error,
            alEditar = viewModel::editarFormulario,
            alElegirCliente = viewModel::elegirCliente,
            alCrearCliente = viewModel::crearClienteNuevo,
            alGuardar = viewModel::guardar,
            alCerrar = viewModel::cerrarFormulario,
        )
    }
}

@Composable
private fun TarjetaSede(sitio: Sitio, alCambiarActivo: (Sitio) -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sitio.nombreSede, style = MaterialTheme.typography.titleMedium)
            Text(
                text = sitio.nombreCliente,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = sitio.direccion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Radio ${sitio.radioPermitidoMetros.toInt()} m",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!sitio.activo) {
                    Text(
                        text = "Inactiva",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { alCambiarActivo(sitio) }) {
                Text(if (sitio.activo) "Desactivar sede" else "Activar sede")
            }
        }
    }
}

@Composable
private fun DialogoNuevaSede(
    formulario: FormularioSede,
    clientes: List<ClienteExistente>,
    guardando: Boolean,
    errorGeneral: String?,
    alEditar: ((FormularioSede) -> FormularioSede) -> Unit,
    alElegirCliente: (ClienteExistente) -> Unit,
    alCrearCliente: () -> Unit,
    alGuardar: () -> Unit,
    alCerrar: () -> Unit,
) {
    var filtroCliente by remember { mutableStateOf("") }
    val contexto = LocalContext.current
    val clienteUbicacion = remember(contexto) {
        LocationServices.getFusedLocationProviderClient(contexto)
    }
    var obteniendoUbicacion by remember { mutableStateOf(false) }
    var mensajeUbicacion by remember { mutableStateOf<String?>(null) }
    val obtenerUbicacion = {
        obteniendoUbicacion = true
        mensajeUbicacion = "Obteniendo ubicación…"
        clienteUbicacion
            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { ubicacion ->
                obteniendoUbicacion = false
                if (ubicacion == null) {
                    mensajeUbicacion = "No pudimos obtener la ubicación. Activa la ubicación del teléfono e intenta de nuevo."
                } else {
                    alEditar {
                        it.copy(
                            lat = ubicacion.latitude.toString(),
                            lng = ubicacion.longitude.toString(),
                        )
                    }
                    mensajeUbicacion = "Ubicación agregada. Revisa la dirección antes de crear la sede."
                }
            }
            .addOnFailureListener {
                obteniendoUbicacion = false
                mensajeUbicacion = "No pudimos obtener la ubicación. Intenta de nuevo."
            }
        Unit
    }
    val solicitarUbicacion = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        if (concedido) {
            obtenerUbicacion()
        } else {
            mensajeUbicacion = "Sin permiso de ubicación. Puedes pegar las coordenadas desde Google Maps."
        }
    }
    AlertDialog(
        onDismissRequest = { if (!guardando) alCerrar() },
        title = { Text("Nueva sede") },
        confirmButton = {
            TextButton(onClick = alGuardar, enabled = formulario.puedeGuardar && !guardando) {
                Text(if (guardando) "Guardando…" else "Crear")
            }
        },
        dismissButton = {
            TextButton(onClick = alCerrar, enabled = !guardando) { Text("Cancelar") }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (errorGeneral != null) {
                    MensajeError(texto = errorGeneral)
                }
                Text("Cliente", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !formulario.clienteNuevo,
                        onClick = { alEditar { it.copy(clienteNuevo = false) } },
                        label = { Text("Existente") },
                    )
                    FilterChip(
                        selected = formulario.clienteNuevo,
                        onClick = alCrearCliente,
                        label = { Text("Crear cliente") },
                    )
                }
                if (formulario.clienteNuevo) {
                    OutlinedTextField(
                        value = formulario.nombreCliente,
                        onValueChange = { v ->
                            alEditar { it.copy(nombreCliente = v) }
                        },
                        label = { Text("Nombre del cliente") },
                        isError = formulario.errorNombreCliente != null,
                        supportingText = {
                            Text(formulario.errorNombreCliente ?: "Lo usaremos para agrupar sus sedes y reportes.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = filtroCliente,
                        onValueChange = { filtroCliente = it },
                        label = { Text("Buscar cliente") },
                        supportingText = { Text(formulario.errorClienteId ?: "Elige un cliente para mantener sus reportes unidos.") },
                        isError = formulario.errorClienteId != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    clientes
                        .filter { it.nombre.contains(filtroCliente, ignoreCase = true) }
                        .take(8)
                        .forEach { cliente ->
                            TextButton(onClick = { alElegirCliente(cliente) }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = if (formulario.clienteId == cliente.id) "✓ ${cliente.nombre}" else cliente.nombre,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    if (clientes.isEmpty()) {
                        Text(
                            "Aún no hay clientes. Crea el primero.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = formulario.nombreSede,
                    onValueChange = { v -> alEditar { it.copy(nombreSede = v) } },
                    label = { Text("Sede") },
                    isError = formulario.errorNombreSede != null,
                    supportingText = formulario.errorNombreSede?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(
                                contexto,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            solicitarUbicacion.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        } else {
                            obtenerUbicacion()
                        }
                    },
                    enabled = !obteniendoUbicacion,
                ) { Text(if (obteniendoUbicacion) "Obteniendo ubicación…" else "Usar mi ubicación actual") }
                mensajeUbicacion?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = formulario.direccion,
                    onValueChange = { v -> alEditar { it.copy(direccion = v) } },
                    label = { Text("Dirección") },
                    isError = formulario.errorDireccion != null,
                    supportingText = formulario.errorDireccion?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = formulario.coordenadasPegadas,
                    onValueChange = { v ->
                        val coordenadas = FormularioSede.coordenadasDesdeTexto(v)
                        alEditar {
                            it.copy(
                                coordenadasPegadas = v,
                                lat = coordenadas?.first ?: it.lat,
                                lng = coordenadas?.second ?: it.lng,
                            )
                        }
                    },
                    label = { Text("Pegar coordenadas (opcional)") },
                    supportingText = { Text("Pega «4.70, -74.06» desde Google Maps. También puedes escribirlas abajo.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = formulario.lat,
                        onValueChange = { v -> alEditar { it.copy(lat = v) } },
                        label = { Text("Latitud") },
                        isError = formulario.errorLat != null,
                        supportingText = formulario.errorLat?.let { { Text(it) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = formulario.lng,
                        onValueChange = { v -> alEditar { it.copy(lng = v) } },
                        label = { Text("Longitud") },
                        isError = formulario.errorLng != null,
                        supportingText = formulario.errorLng?.let { { Text(it) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = formulario.radio,
                    onValueChange = { v -> alEditar { it.copy(radio = v) } },
                    label = { Text("Radio permitido (m)") },
                    isError = formulario.errorRadio != null,
                    supportingText = {
                        Text(
                            formulario.errorRadio
                                ?: "150 m equivale aproximadamente a una manzana. Úsalo para la extensión de esta sede.",
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
