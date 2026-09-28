package com.verificlean.app.ui.pantallas.gestion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.ui.componentes.CargandoPantalla
import com.verificlean.app.ui.componentes.MensajeVacio
import com.verificlean.app.ui.componentes.MensajeError

/**
 * Alta y listado de supervisores.
 *
 * Crear un usuario toca dos sistemas: Firebase Authentication y el documento en `users`.
 * El servidor lo hace en un solo paso y revierte la cuenta de Auth si el documento falla,
 * para no dejar cuentas huérfanas que puedan iniciar sesión sin rol.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisoresScreen(
    alVolver: () -> Unit,
    viewModel: SupervisoresViewModel = hiltViewModel(),
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
                title = { Text("Supervisores") },
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
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text("Nuevo usuario") },
            )
        },
    ) { relleno ->
        when {
            estado.cargando -> CargandoPantalla(Modifier.padding(relleno))

            estado.error != null && estado.usuarios.isEmpty() -> MensajeError(
                texto = estado.error.orEmpty(),
                alReintentar = viewModel::cargar,
                modifier = Modifier.padding(relleno),
            )

            estado.usuarios.isEmpty() -> MensajeVacio(
                "No hay usuarios todavía.",
                Modifier.padding(relleno),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(relleno).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (estado.error != null) {
                    item { MensajeError(texto = estado.error.orEmpty()) }
                }
                items(estado.usuarios, key = { it.id }) {
                    TarjetaUsuario(
                        usuario = it,
                        esCuentaPropia = it.id == estado.usuarioActualId,
                        identidadResuelta = estado.usuarioActualResuelto,
                        alCambiarActivo = viewModel::pedirCambioActivo,
                    )
                }
                item { Column(Modifier.padding(bottom = 100.dp)) {} }
            }
        }
    }

    if (estado.formularioAbierto) {
        DialogoNuevoUsuario(
            formulario = estado.formulario,
            guardando = estado.guardando,
            errorGeneral = estado.error,
            alEditar = viewModel::editarFormulario,
            alGuardar = viewModel::guardar,
            alCerrar = viewModel::cerrarFormulario,
        )
    }

    estado.usuarioPorCambiar?.let { usuario ->
        AlertDialog(
            onDismissRequest = viewModel::cancelarCambioActivo,
            title = { Text(if (usuario.activo) "Desactivar usuario" else "Activar usuario") },
            text = {
                Text(
                    if (usuario.activo) {
                        "${usuario.nombre} perderá el acceso de inmediato. Sus visitas y evidencias seguirán en el historial."
                    } else {
                        "${usuario.nombre} volverá a poder iniciar sesión."
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
}

@Composable
private fun TarjetaUsuario(
    usuario: Usuario,
    esCuentaPropia: Boolean,
    identidadResuelta: Boolean,
    alCambiarActivo: (Usuario) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(usuario.nombre, style = MaterialTheme.typography.titleMedium)
            Text(
                text = usuario.email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (usuario.rol == Rol.ADMIN) "Administrador" else "Supervisor",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!usuario.activo) {
                    // Sin rojo: un usuario inactivo es una decisión administrativa normal,
                    // no una alerta de fraude. El rojo está reservado para eso.
                    Text(
                        text = "Inactivo · no puede iniciar sesión",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = { alCambiarActivo(usuario) },
                enabled = identidadResuelta && !esCuentaPropia,
            ) {
                Text(
                    when {
                        !identidadResuelta -> "Verificando tu cuenta…"
                        esCuentaPropia -> "No puedes cambiar tu cuenta"
                        usuario.activo -> "Desactivar usuario"
                        else -> "Activar usuario"
                    },
                )
            }
        }
    }
}

@Composable
private fun DialogoNuevoUsuario(
    formulario: FormularioUsuario,
    guardando: Boolean,
    errorGeneral: String?,
    alEditar: ((FormularioUsuario) -> FormularioUsuario) -> Unit,
    alGuardar: () -> Unit,
    alCerrar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!guardando) alCerrar() },
        title = { Text("Nuevo usuario") },
        confirmButton = {
            TextButton(onClick = alGuardar, enabled = formulario.puedeGuardar && !guardando) {
                Text(if (guardando) "Creando…" else "Crear")
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
                OutlinedTextField(
                    value = formulario.nombre,
                    onValueChange = { v -> alEditar { it.copy(nombre = v) } },
                    label = { Text("Nombre completo") },
                    isError = formulario.errorNombre != null,
                    supportingText = formulario.errorNombre?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = formulario.email,
                    onValueChange = { v -> alEditar { it.copy(email = v) } },
                    label = { Text("Correo") },
                    isError = formulario.errorEmail != null,
                    supportingText = formulario.errorEmail?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = formulario.password,
                    onValueChange = { v -> alEditar { it.copy(password = v) } },
                    label = { Text("Contraseña inicial") },
                    isError = formulario.errorPassword != null,
                    supportingText = {
                        Text(
                            formulario.errorPassword
                                ?: "Compártela por un canal seguro. La app no vuelve a mostrarla.",
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = formulario.rol == Rol.SUPERVISOR,
                        onClick = { alEditar { it.copy(rol = Rol.SUPERVISOR) } },
                        label = { Text("Supervisor") },
                    )
                    FilterChip(
                        selected = formulario.rol == Rol.ADMIN,
                        onClick = { alEditar { it.copy(rol = Rol.ADMIN) } },
                        label = { Text("Administrador") },
                    )
                }
                if (formulario.rol == Rol.ADMIN) {
                    // Un admin ve el veredicto antifraude de todos los supervisores. Que
                    // quien lo crea sepa lo que está concediendo.
                    Text(
                        text = "Un administrador puede ver todas las evidencias y sus alertas de fraude.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
