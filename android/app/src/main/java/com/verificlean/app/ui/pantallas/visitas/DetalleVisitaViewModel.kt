package com.verificlean.app.ui.pantallas.visitas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.mensajeLegible
import com.verificlean.app.domain.model.ChecklistItem
import com.verificlean.app.domain.model.RespuestaChecklist
import com.verificlean.app.domain.model.VisitaDetalle
import com.verificlean.app.domain.repository.VisitaRepository
import com.verificlean.app.domain.repository.EvidenciaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

data class EstadoDetalle(
    val cargando: Boolean = true,
    val detalle: VisitaDetalle? = null,
    val checklist: List<ChecklistItem> = emptyList(),
    val observaciones: String = "",
    val completando: Boolean = false,
    val error: String? = null,
    val completada: Boolean = false,
    val evidenciasPendientes: Int = 0,
) {
    val itemsSinResponder: List<String>
        get() = checklist.filter { it.respuesta == null }.map { it.pregunta }

    /**
     * Las dos condiciones que el servidor también exige. Se comprueban aquí para dar
     * feedback inmediato, pero la validación que cuenta es la del backend — un cliente
     * modificado se saltaría esta sin esfuerzo.
     */
    val puedeCompletar: Boolean
        get() = detalle != null &&
            !completando &&
            (detalle.visita.evidenciaCount > 0) &&
            evidenciasPendientes == 0 &&
            itemsSinResponder.isEmpty()
}

@HiltViewModel
class DetalleVisitaViewModel @Inject constructor(
    private val visitaRepository: VisitaRepository,
    private val evidenciaRepository: EvidenciaRepository,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoDetalle())
    val estado: StateFlow<EstadoDetalle> = _estado.asStateFlow()

    private var visitIdActual: String? = null
    private var pendientesLocales = emptyList<com.verificlean.app.domain.model.EvidenciaPendiente>()

    init {
        viewModelScope.launch {
            evidenciaRepository.observarPendientes().collectLatest { pendientes ->
                val anteriores = _estado.value.evidenciasPendientes
                pendientesLocales = pendientes
                val actuales = pendientes.count { it.visitId == visitIdActual }
                _estado.update { it.copy(evidenciasPendientes = actuales) }
                if (anteriores > 0 && actuales == 0 && visitIdActual != null) {
                    recargarConservandoRespuestas()
                }
            }
        }
    }

    fun cargar(visitId: String) {
        // La pantalla puede recomponerse varias veces; sin esta guarda cada
        // recomposición dispararía otra petición.
        if (visitIdActual == visitId && _estado.value.detalle != null) return
        visitIdActual = visitId

        _estado.update {
            it.copy(
                cargando = true,
                error = null,
                evidenciasPendientes = pendientesLocales.count { pendiente ->
                    pendiente.visitId == visitId
                },
            )
        }
        viewModelScope.launch {
            when (val resultado = visitaRepository.obtenerDetalle(visitId)) {
                is Resultado.Exito -> _estado.update {
                    it.copy(
                        cargando = false,
                        detalle = resultado.datos,
                        checklist = resultado.datos.checklist,
                        observaciones = resultado.datos.observaciones.orEmpty(),
                        completada = resultado.datos.visita.completadaEn != null,
                    )
                }
                is Resultado.Fallo -> _estado.update {
                    it.copy(cargando = false, error = resultado.error.mensajeLegible())
                }
            }
        }
    }

    fun responder(itemId: String, respuesta: RespuestaChecklist) {
        _estado.update { estado ->
            estado.copy(
                checklist = estado.checklist.map { item ->
                    if (item.id == itemId) item.copy(respuesta = respuesta) else item
                },
            )
        }
    }

    fun anotar(itemId: String, nota: String) {
        val limitada = nota.take(500)
        _estado.update { estado ->
            estado.copy(
                checklist = estado.checklist.map { item ->
                    if (item.id == itemId) item.copy(nota = limitada.ifBlank { null }) else item
                },
            )
        }
    }

    fun alCambiarObservaciones(valor: String) =
        _estado.update { it.copy(observaciones = valor.take(2000)) }

    fun completar() {
        val actual = _estado.value
        val visitId = visitIdActual ?: return
        if (!actual.puedeCompletar) return

        _estado.update { it.copy(completando = true, error = null) }
        viewModelScope.launch {
            val resultado = visitaRepository.completarVisita(
                visitId = visitId,
                checklist = actual.checklist,
                observaciones = actual.observaciones.ifBlank { null },
            )
            _estado.update { estado ->
                when (resultado) {
                    is Resultado.Exito -> estado.copy(
                        completando = false,
                        detalle = resultado.datos,
                        completada = true,
                    )
                    is Resultado.Fallo -> estado.copy(
                        completando = false,
                        error = resultado.error.mensajeLegible(),
                    )
                }
            }
        }
    }

    /** Recarga desde cero, descartando lo que el usuario llevara escrito. */
    fun recargar() {
        val visitId = visitIdActual ?: return
        visitIdActual = null
        cargar(visitId)
    }

    /**
     * Recarga tras volver de la captura, **conservando las respuestas del checklist** que
     * el supervisor ya hubiera marcado.
     *
     * Lo único que necesita actualizarse es el contador de evidencias. Recargar del todo
     * borraría respuestas escritas antes de ir a la cámara, y obligar a rellenarlas otra
     * vez después de cada foto haría el checklist insufrible.
     */
    fun recargarConservandoRespuestas() {
        val visitId = visitIdActual ?: return
        val respuestasEnCurso = _estado.value.checklist.associateBy { it.id }
        val observacionesEnCurso = _estado.value.observaciones

        viewModelScope.launch {
            when (val resultado = visitaRepository.obtenerDetalle(visitId)) {
                is Resultado.Exito -> _estado.update { estado ->
                    estado.copy(
                        cargando = false,
                        detalle = resultado.datos,
                        checklist = resultado.datos.checklist.map { item ->
                            respuestasEnCurso[item.id]?.let {
                                item.copy(respuesta = it.respuesta, nota = it.nota)
                            } ?: item
                        },
                        observaciones = observacionesEnCurso,
                        completada = resultado.datos.visita.completadaEn != null,
                    )
                }
                // Un fallo de red no debe borrar lo que el usuario tenía en pantalla: se
                // conserva el estado y sólo se anota el error.
                is Resultado.Fallo -> _estado.update {
                    it.copy(error = resultado.error.mensajeLegible())
                }
            }
        }
    }

    fun descartarError() = _estado.update { it.copy(error = null) }
}
