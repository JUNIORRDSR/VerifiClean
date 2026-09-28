package com.verificlean.app.ui.pantallas.gestion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.mensajeLegible
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.repository.CatalogoRepository
import com.verificlean.app.domain.repository.SesionRepository
import com.verificlean.app.domain.repository.UsuarioNuevo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FormularioUsuario(
    val nombre: String = "",
    val email: String = "",
    val password: String = "",
    val rol: Rol = Rol.SUPERVISOR,
    val erroresServidor: Map<String, String> = emptyMap(),
) {
    // Mismo mínimo que exige el servidor. Comprobarlo aquí evita que el admin escriba
    // todo el formulario para recibir un 400 por la contraseña.
    val passwordValida: Boolean get() = password.length in 8..128
    val emailValido: Boolean get() = email.length <= 254 &&
        Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)

    val errorNombre: String?
        get() = erroresServidor["nombre"] ?: when {
            nombre.isBlank() -> "Escribe el nombre completo."
            nombre.trim().length < 2 -> "Mínimo 2 caracteres."
            nombre.length > 120 -> "Máximo 120 caracteres."
            else -> null
        }

    val errorPassword: String?
        get() = erroresServidor["password"] ?: if (password.isBlank() || passwordValida) null else "Entre 8 y 128 caracteres"
    val errorEmail: String?
        get() = erroresServidor["email"] ?: if (email.isBlank() || emailValido) null else "Escribe un correo válido (máximo 254 caracteres)."

    val puedeGuardar: Boolean
        get() = errorNombre == null && emailValido && passwordValida
}

data class EstadoSupervisores(
    val usuarios: List<Usuario> = emptyList(),
    val cargando: Boolean = true,
    val guardando: Boolean = false,
    val formularioAbierto: Boolean = false,
    val formulario: FormularioUsuario = FormularioUsuario(),
    val error: String? = null,
    val mensaje: String? = null,
    val usuarioActualId: String? = null,
    val usuarioActualResuelto: Boolean = false,
    val usuarioPorCambiar: Usuario? = null,
    val cambiandoActivo: Boolean = false,
)

@HiltViewModel
class SupervisoresViewModel @Inject constructor(
    private val catalogo: CatalogoRepository,
    private val sesionRepository: SesionRepository,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoSupervisores())
    val estado: StateFlow<EstadoSupervisores> = _estado.asStateFlow()

    init {
        viewModelScope.launch {
            _estado.update {
                it.copy(
                    usuarioActualId = sesionRepository.usuarioEnSesion.first()?.id,
                    usuarioActualResuelto = true,
                )
            }
        }
        cargar()
    }

    fun cargar() {
        _estado.update { it.copy(cargando = true, error = null) }
        viewModelScope.launch {
            when (val r = catalogo.listarUsuarios()) {
                is Resultado.Exito -> _estado.update {
                    // Supervisores primero: son los que el admin gestiona a diario.
                    it.copy(
                        cargando = false,
                        usuarios = r.datos.sortedBy { u -> u.rol == Rol.ADMIN },
                    )
                }
                is Resultado.Fallo -> _estado.update {
                    it.copy(cargando = false, error = r.error.mensajeLegible())
                }
            }
        }
    }

    fun abrirFormulario() =
        _estado.update { it.copy(formularioAbierto = true, formulario = FormularioUsuario()) }

    fun cerrarFormulario() = _estado.update { it.copy(formularioAbierto = false) }

    fun editarFormulario(cambio: (FormularioUsuario) -> FormularioUsuario) =
        _estado.update {
            it.copy(
                error = null,
                formulario = cambio(it.formulario).copy(erroresServidor = emptyMap()),
            )
        }

    fun guardar() {
        val f = _estado.value.formulario
        if (!f.puedeGuardar || _estado.value.guardando) return

        _estado.update { it.copy(guardando = true, error = null) }
        viewModelScope.launch {
            val r = catalogo.crearUsuario(
                UsuarioNuevo(nombre = f.nombre, email = f.email, password = f.password, rol = f.rol),
            )
            when (r) {
                is Resultado.Exito -> _estado.update {
                    it.copy(
                        guardando = false,
                        formularioAbierto = false,
                        usuarios = listOf(r.datos) + it.usuarios,
                        mensaje = "${r.datos.nombre} creado. Comparte la contraseña por un canal seguro.",
                    )
                }
                is Resultado.Fallo -> _estado.update {
                    val errores = (r.error as? ErrorApp.Validacion)?.porCampo.orEmpty()
                    it.copy(
                        guardando = false,
                        // El 409 del servidor significa email repetido. El mensaje genérico
                        // de conflicto no le diría al admin qué corregir.
                        formulario = it.formulario.copy(erroresServidor = errores),
                        error = if (errores.isNotEmpty()) null else when (r.error) {
                            is ErrorApp.Conflicto -> "Ya existe un usuario con ese correo."
                            else -> r.error.mensajeLegible()
                        },
                    )
                }
            }
        }
    }

    fun descartarAviso() = _estado.update { it.copy(error = null, mensaje = null) }

    fun pedirCambioActivo(usuario: Usuario) {
        if (!_estado.value.usuarioActualResuelto) {
            _estado.update { it.copy(error = "Espera mientras verificamos tu cuenta.") }
        } else if (usuario.id == _estado.value.usuarioActualId) {
            _estado.update { it.copy(error = "No puedes cambiar el estado de tu propia cuenta.") }
        } else {
            _estado.update { it.copy(usuarioPorCambiar = usuario) }
        }
    }

    fun cancelarCambioActivo() = _estado.update { it.copy(usuarioPorCambiar = null) }

    fun confirmarCambioActivo() {
        if (_estado.value.cambiandoActivo) return
        val usuario = _estado.value.usuarioPorCambiar ?: return
        if (!_estado.value.usuarioActualResuelto || usuario.id == _estado.value.usuarioActualId) {
            _estado.update {
                it.copy(
                    usuarioPorCambiar = null,
                    error = "No puedes cambiar el estado de tu propia cuenta.",
                )
            }
            return
        }
        _estado.update { it.copy(cambiandoActivo = true, error = null) }
        viewModelScope.launch {
            when (val resultado = catalogo.actualizarUsuarioActivo(usuario.id, !usuario.activo)) {
                is Resultado.Exito -> _estado.update { estado ->
                    estado.copy(
                        usuarioPorCambiar = null,
                        cambiandoActivo = false,
                        usuarios = estado.usuarios.map { if (it.id == usuario.id) resultado.datos else it },
                        mensaje = if (resultado.datos.activo) {
                            "${usuario.nombre} ya puede iniciar sesión."
                        } else {
                            "${usuario.nombre} ya no puede iniciar sesión. Su historial se conserva."
                        },
                    )
                }
                is Resultado.Fallo -> _estado.update {
                    it.copy(
                        usuarioPorCambiar = null,
                        cambiandoActivo = false,
                        error = resultado.error.mensajeLegible(),
                    )
                }
            }
        }
    }
}
