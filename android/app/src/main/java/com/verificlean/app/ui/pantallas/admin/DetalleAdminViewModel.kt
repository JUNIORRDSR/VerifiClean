package com.verificlean.app.ui.pantallas.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.mensajeLegible
import com.verificlean.app.domain.model.VisitaDetalleAdmin
import com.verificlean.app.domain.model.EstadoRevisionAlerta
import com.verificlean.app.domain.repository.AdminRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EstadoDetalleAdmin(
    val cargando: Boolean = true,
    val detalle: VisitaDetalleAdmin? = null,
    val error: String? = null,
    val guardandoRevision: Boolean = false,
    val mensajeRevision: String? = null,
    val errorRevision: String? = null,
)

@HiltViewModel
class DetalleAdminViewModel @Inject constructor(
    private val adminRepository: AdminRepository,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoDetalleAdmin())
    val estado: StateFlow<EstadoDetalleAdmin> = _estado.asStateFlow()

    private var visitIdActual: String? = null

    fun cargar(visitId: String) {
        // Las URLs de foto vienen firmadas con 15 minutos de vigencia, así que
        // recargar no es opcional pasado ese tiempo. Aun así no se recarga en cada
        // recomposición: sólo si cambia la visita o se pide explícitamente.
        if (visitIdActual == visitId && _estado.value.detalle != null) return
        visitIdActual = visitId

        _estado.update { it.copy(cargando = true, error = null) }
        viewModelScope.launch {
            when (val resultado = adminRepository.obtenerDetalle(visitId)) {
                is Resultado.Exito -> _estado.update {
                    it.copy(cargando = false, detalle = resultado.datos)
                }
                is Resultado.Fallo -> _estado.update {
                    it.copy(cargando = false, error = resultado.error.mensajeLegible())
                }
            }
        }
    }

    fun recargar() {
        val visitId = visitIdActual ?: return
        visitIdActual = null
        cargar(visitId)
    }

    fun guardarRevision(estado: EstadoRevisionAlerta, notaCruda: String) {
        val visitId = visitIdActual ?: return
        if (_estado.value.guardandoRevision) return

        val nota = notaCruda.trim().ifEmpty { null }
        val exigeNota = estado == EstadoRevisionAlerta.JUSTIFICADA ||
            estado == EstadoRevisionAlerta.ESCALADA
        when {
            exigeNota && nota == null -> {
                _estado.update { it.copy(errorRevision = "Escribe una nota para este estado.") }
                return
            }
            (nota?.length ?: 0) > 1000 -> {
                _estado.update { it.copy(errorRevision = "La nota admite máximo 1000 caracteres.") }
                return
            }
        }

        _estado.update {
            it.copy(guardandoRevision = true, mensajeRevision = null, errorRevision = null)
        }
        viewModelScope.launch {
            when (
                val resultado = adminRepository.actualizarRevisionAlerta(
                    visitId = visitId,
                    estado = estado,
                    nota = if (estado == EstadoRevisionAlerta.PENDIENTE) null else nota,
                )
            ) {
                is Resultado.Exito -> _estado.update {
                    it.copy(
                        guardandoRevision = false,
                        detalle = resultado.datos,
                        mensajeRevision = if (estado == EstadoRevisionAlerta.PENDIENTE) {
                            "Caso reabierto y pendiente de revisión."
                        } else {
                            "Revisión guardada."
                        },
                    )
                }
                is Resultado.Fallo -> _estado.update {
                    it.copy(
                        guardandoRevision = false,
                        errorRevision = resultado.error.mensajeLegible(),
                    )
                }
            }
        }
    }
}
