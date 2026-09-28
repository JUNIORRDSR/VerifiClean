package com.verificlean.app.ui.pantallas.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verificlean.app.BuildConfig
import com.verificlean.app.R

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
    Column(
        // Un ancho controlado mantiene juntas las etiquetas y sus campos en tabletas;
        // en teléfono sigue ocupando todo el espacio disponible.
        modifier = Modifier
            .widthIn(max = 440.dp)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.VerifiedUser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.login_titulo), style = MaterialTheme.typography.headlineLarge)
        Text(
            text = stringResource(R.string.login_subtitulo),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Tu administrador crea la cuenta y te asigna visitas. Supervisores y administradores ven tareas distintas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(28.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        // El título de acción diferencia la tarea de autenticarse de la marca que la
        // encabeza y mejora el barrido visual para quien vuelve todos los días.
        Text("Ingresa a tu cuenta", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = estado.email,
            onValueChange = viewModel::alCambiarEmail,
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            enabled = !estado.cargando,
            isError = estado.error != null,
            leadingIcon = {
                Icon(Icons.Default.Email, contentDescription = null)
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = estado.password,
            onValueChange = viewModel::alCambiarPassword,
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            enabled = !estado.cargando,
            isError = estado.error != null,
            leadingIcon = {
                Icon(Icons.Default.Lock, contentDescription = null)
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.entrar() }),
            modifier = Modifier.fillMaxWidth(),
        )

        if (estado.error != null) {
            // Los errores de acceso usan el rol ámbar del tema: el rojo queda reservado
            // para fraude incluso cuando Material marca el campo como inválido.
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = estado.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Button(
            onClick = viewModel::entrar,
            enabled = estado.puedeEnviar,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            if (estado.cargando) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.login_entrar))
            }
        }
        }
        }

        // Ayuda para desarrollo. Sólo en builds con el autenticador falso: en release
        // este bloque desaparece y R8 lo elimina junto con la constante.
        if (BuildConfig.USA_AUTH_FALSO) {
            Spacer(Modifier.height(28.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Prueba la app", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "En desarrollo puedes entrar como administradora o supervisor. Usa cualquiera de estos correos y cualquier contraseña.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Administradora: admin@verificlean.test", style = MaterialTheme.typography.labelMedium)
                    Text("Supervisores: carlos@verificlean.test o diana@verificlean.test", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    }
}
