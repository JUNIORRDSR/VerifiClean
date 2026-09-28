package com.verificlean.app.ui.pantallas.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.mensajeLegible
import com.verificlean.app.domain.repository.SesionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EstadoLogin(
    val email: String = "",
    val password: String = "",
    val cargando: Boolean = false,
    val error: String? = null,
) {
    val puedeEnviar: Boolean get() = email.isNotBlank() && password.isNotBlank() && !cargando
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val sesionRepository: SesionRepository,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoLogin())
    val estado: StateFlow<EstadoLogin> = _estado.asStateFlow()

    fun alCambiarEmail(valor: String) = _estado.update { it.copy(email = valor, error = null) }

    fun alCambiarPassword(valor: String) = _estado.update { it.copy(password = valor, error = null) }

    fun entrar() {
        val actual = _estado.value
        if (!actual.puedeEnviar) return

        _estado.update { it.copy(cargando = true, error = null) }

        viewModelScope.launch {
            val resultado = sesionRepository.iniciarSesion(actual.email.trim(), actual.password)
            _estado.update { estado ->
                when (resultado) {
                    // No se navega desde aquí: al guardarse la sesión, el flujo de
                    // `SesionViewModel` cambia y la navegación reacciona sola. Un
                    // `navigate()` explícito aquí crearía dos fuentes de verdad sobre
                    // si hay sesión.
                    is Resultado.Exito -> estado.copy(cargando = false, error = null)
                    is Resultado.Fallo -> estado.copy(
                        cargando = false,
                        error = when (resultado.error) {
                            // El backend responde 403 a un usuario desactivado; el
                            // mensaje genérico de "sin permiso" no diría nada útil.
                            ErrorApp.Prohibido -> "Tu usuario está inactivo o no está dado de alta."
                            // El mensaje genérico de red promete un reintento automático.
                            // Aquí sería mentira: el login no se encola ni se reintenta
                            // solo, y quien lo lea se quedaría esperando.
                            ErrorApp.Red -> "Sin conexión. Revisa tu red e inténtalo de nuevo."
                            else -> resultado.error.mensajeLegible()
                        },
                    )
                }
            }
        }
    }
}
