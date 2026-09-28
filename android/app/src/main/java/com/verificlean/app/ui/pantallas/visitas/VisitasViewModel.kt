package com.verificlean.app.ui.pantallas.visitas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.mensajeLegible
import com.verificlean.app.domain.model.EstadoSincronizacion
import com.verificlean.app.domain.model.EvidenciaPendiente
import com.verificlean.app.domain.model.Visita
import com.verificlean.app.domain.repository.EvidenciaRepository
import com.verificlean.app.domain.repository.SesionRepository
import com.verificlean.app.domain.repository.VisitaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EstadoVisitas(
    val visitas: List<Visita> = emptyList(),
    val pendientesDeSubir: Int = 0,
    /**
     * Evidencias que el servidor rechazó definitivamente.
     *
     * Se muestran de forma explícita: antes existían en la base pero ninguna pantalla las
     * enseñaba, así que una evidencia rechazada desaparecía en silencio y el supervisor
     * creía haber registrado una visita que en realidad nunca llegó.
     */
    val fallidas: List<EvidenciaPendiente> = emptyList(),
    val refrescando: Boolean = false,
    val inicializando: Boolean = true,
    val error: String? = null,
    val ultimaActualizacionMillis: Long? = null,
)

@HiltViewModel
class VisitasViewModel @Inject constructor(
    private val visitaRepository: VisitaRepository,
    private val evidenciaRepository: EvidenciaRepository,
    private val sesionRepository: SesionRepository,
) : ViewModel() {

    private val efimero = MutableStateFlow(EstadoEfimero())

    /**
     * La lista sale de Room, no de la respuesta HTTP. `refrescarVisitas` sólo escribe en
     * la base; lo que se pinta es siempre lo cacheado. Así la bandeja aparece al
     * instante y sin conexión, que es el caso normal de uso de esta app.
     */
    val estado: StateFlow<EstadoVisitas> = combine(
        visitaRepository.observarVisitas(),
        evidenciaRepository.observarPendientes(),
        efimero,
    ) { visitas, pendientes, transitorio ->
        EstadoVisitas(
            visitas = visitas,
            pendientesDeSubir = pendientes.count { it.estado != EstadoSincronizacion.FALLIDA },
            fallidas = pendientes.filter { it.estado == EstadoSincronizacion.FALLIDA },
            refrescando = transitorio.refrescando,
            inicializando = transitorio.inicializando,
            error = transitorio.error,
            ultimaActualizacionMillis = transitorio.ultimaActualizacionMillis,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EstadoVisitas())

    init {
        refrescar()
    }

    fun refrescar() {
        efimero.update { it.copy(refrescando = true, error = null) }
        viewModelScope.launch {
            val resultado = visitaRepository.refrescarVisitas()
            efimero.update { actual ->
                when (resultado) {
                    is Resultado.Exito -> actual.copy(
                        refrescando = false,
                        inicializando = false,
                        error = null,
                        ultimaActualizacionMillis = System.currentTimeMillis(),
                    )
                    is Resultado.Fallo -> actual.copy(
                        refrescando = false,
                        inicializando = false,
                        // Se conserva hasta un reintento correcto. Un snackbar fugaz
                        // terminaba convirtiendo el fallo en un estado vacío falso.
                        error = resultado.error.mensajeLegible(),
                    )
                }
            }
        }
    }

    fun sincronizarPendientes() = evidenciaRepository.sincronizarAhora()

    /** Reencola una evidencia rechazada. Reinicia su contador de intentos. */
    fun reintentarFallida(clienteEvidenciaId: String) {
        viewModelScope.launch { evidenciaRepository.reintentar(clienteEvidenciaId) }
    }

    fun cerrarSesion() {
        viewModelScope.launch { sesionRepository.cerrarSesion() }
    }

    private data class EstadoEfimero(
        val refrescando: Boolean = false,
        val inicializando: Boolean = true,
        val error: String? = null,
        val ultimaActualizacionMillis: Long? = null,
    )
}
