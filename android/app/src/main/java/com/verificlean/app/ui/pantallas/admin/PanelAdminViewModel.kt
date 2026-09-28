package com.verificlean.app.ui.pantallas.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.mensajeLegible
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.VisitaResumenAdmin
import com.verificlean.app.domain.repository.AdminRepository
import com.verificlean.app.domain.repository.SesionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

enum class PeriodoPanel { AYER, HOY, TODO }

data class EstadoPanel(
    val visitas: List<VisitaResumenAdmin> = emptyList(),
    val cargando: Boolean = false,
    val cargandoMas: Boolean = false,
    val periodo: PeriodoPanel = PeriodoPanel.AYER,
    val soloSospechosas: Boolean = false,
    val filtroEstado: EstadoVisita? = null,
    val siguienteCursor: String? = null,
    val error: String? = null,
) {
    val hayMas: Boolean get() = siguienteCursor != null
}

@HiltViewModel
class PanelAdminViewModel @Inject constructor(
    private val adminRepository: AdminRepository,
    private val sesionRepository: SesionRepository,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoPanel())
    val estado: StateFlow<EstadoPanel> = _estado.asStateFlow()

    /**
     * Trabajo de carga en curso. Cambiar de filtro lo cancela.
     *
     * Sin esto, tocar dos filtros seguidos deja dos peticiones en vuelo y **gana la que
     * responda última**, no la que el usuario pidió última: el panel acaba mostrando el
     * resultado del filtro anterior. En una pantalla cuyo propósito es decidir si se abre
     * una investigación laboral, mostrar la lista equivocada no es un detalle estético.
     */
    private var trabajoDeCarga: Job? = null

    init {
        cargar()
    }

    fun alternarSospechosas() {
        _estado.update { it.copy(soloSospechosas = !it.soloSospechosas) }
        cargar()
    }

    fun filtrarPorEstado(estado: EstadoVisita?) {
        _estado.update { it.copy(filtroEstado = estado) }
        cargar()
    }

    fun filtrarPorPeriodo(periodo: PeriodoPanel) {
        if (_estado.value.periodo == periodo) return
        _estado.update { it.copy(periodo = periodo) }
        cargar()
    }

    /** Primera página. Reemplaza la lista, no la acumula. */
    fun cargar() {
        val filtros = _estado.value
        _estado.update { it.copy(cargando = true, error = null) }

        // Cancela la carga anterior y también cualquier paginación en curso: una página
        // del filtro viejo anexada a la lista nueva mezclaría resultados de dos consultas.
        trabajoDeCarga?.cancel()
        trabajoDeCarga = viewModelScope.launch {
            val (desde, hasta) = rango(filtros.periodo)
            val resultado = adminRepository.listarVisitas(
                soloSospechosas = filtros.soloSospechosas,
                estado = filtros.filtroEstado,
                desde = desde,
                hasta = hasta,
                cursor = null,
            )
            _estado.update { actual ->
                when (resultado) {
                    is Resultado.Exito -> actual.copy(
                        cargando = false,
                        visitas = resultado.datos.items,
                        siguienteCursor = resultado.datos.siguienteCursor,
                    )
                    is Resultado.Fallo -> actual.copy(
                        cargando = false,
                        error = resultado.error.mensajeLegible(),
                    )
                }
            }
        }
    }

    /** Página siguiente. Se ignora si ya hay una en vuelo o no queda cursor. */
    fun cargarMas() {
        val actual = _estado.value
        val cursor = actual.siguienteCursor ?: return
        if (actual.cargandoMas || actual.cargando) return

        _estado.update { it.copy(cargandoMas = true) }
        trabajoDeCarga = viewModelScope.launch {
            val (desde, hasta) = rango(actual.periodo)
            val resultado = adminRepository.listarVisitas(
                soloSospechosas = actual.soloSospechosas,
                estado = actual.filtroEstado,
                desde = desde,
                hasta = hasta,
                cursor = cursor,
            )
            _estado.update { estado ->
                when (resultado) {
                    is Resultado.Exito -> estado.copy(
                        cargandoMas = false,
                        visitas = estado.visitas + resultado.datos.items,
                        siguienteCursor = resultado.datos.siguienteCursor,
                    )
                    is Resultado.Fallo -> estado.copy(
                        cargandoMas = false,
                        error = resultado.error.mensajeLegible(),
                    )
                }
            }
        }
    }

    fun cerrarSesion() {
        viewModelScope.launch { sesionRepository.cerrarSesion() }
    }

    fun descartarError() = _estado.update { it.copy(error = null) }

    private fun rango(periodo: PeriodoPanel): Pair<java.time.Instant?, java.time.Instant?> {
        if (periodo == PeriodoPanel.TODO) return null to null
        val zona = ZoneId.systemDefault()
        val fecha = when (periodo) {
            PeriodoPanel.AYER -> LocalDate.now(zona).minusDays(1)
            PeriodoPanel.HOY -> LocalDate.now(zona)
            PeriodoPanel.TODO -> error("El periodo completo no necesita fechas")
        }
        return fecha.atStartOfDay(zona).toInstant() to
            fecha.atTime(LocalTime.MAX).atZone(zona).toInstant()
    }
}
