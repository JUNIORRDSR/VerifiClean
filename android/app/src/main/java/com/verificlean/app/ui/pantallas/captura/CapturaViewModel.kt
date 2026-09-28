package com.verificlean.app.ui.pantallas.captura

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.mensajeLegible
import com.verificlean.app.data.repository.VisitaRepositoryImpl
import com.verificlean.app.domain.repository.EntradaEvidencia
import com.verificlean.app.domain.repository.EvidenciaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class EstadoCaptura(
    val procesando: Boolean = false,
    val guardada: Boolean = false,
    val error: String? = null,
    val nombreSede: String = "",
)

@HiltViewModel
class CapturaViewModel @Inject constructor(
    private val evidenciaRepository: EvidenciaRepository,
    private val visitaRepository: VisitaRepositoryImpl,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoCaptura())
    val estado: StateFlow<EstadoCaptura> = _estado.asStateFlow()

    /**
     * Cerrojo de un solo uso contra la doble captura.
     *
     * Se comprueba y se toma de forma atómica **antes** de disparar la cámara, no al
     * recibir el resultado: entre pulsar el botón y el callback de CameraX pasan cientos
     * de milisegundos, y dos toques en esa ventana producían dos JPEG que se encolaban
     * como evidencias distintas.
     *
     * Vive aquí y no en el estado de Compose porque el `StateFlow` se actualiza de forma
     * asíncrona y no sirve como exclusión mutua.
     */
    private val capturando = AtomicBoolean(false)

    /** `true` si esta llamada ganó el cerrojo y la pantalla puede disparar. */
    fun intentarTomarElTurno(): Boolean {
        val gano = capturando.compareAndSet(false, true)
        if (gano) _estado.update { it.copy(procesando = true, error = null) }
        return gano
    }

    /** Libera el cerrojo cuando la cámara falla antes de entregar bytes. */
    fun liberarTurno(mensaje: String?) {
        capturando.set(false)
        _estado.update { it.copy(procesando = false, error = mensaje) }
    }

    fun cargarSede(visitId: String) {
        viewModelScope.launch {
            val detalle = visitaRepository.detalleCacheado(visitId)
            _estado.update { it.copy(nombreSede = detalle?.visita?.siteNombreSede.orEmpty()) }
        }
    }

    /**
     * Entrega los bytes al repositorio, que los cifra y encola antes de resolver ubicación
     * e integridad.
     *
     * Se lanza en `viewModelScope` a propósito y no en el scope de la pantalla: si el
     * usuario pulsa atrás justo después de disparar, el ViewModel sobrevive lo suficiente
     * para que la fase durable termine. Y aunque no sobreviviera, el repositorio persiste
     * la foto antes que nada, así que la evidencia sigue siendo recuperable.
     */
    fun procesarFoto(visitId: String, jpeg: ByteArray) {
        viewModelScope.launch {
            val detalle = visitaRepository.detalleCacheado(visitId)
            val entrada = EntradaEvidencia(
                visitId = visitId,
                visitaNombreSede = detalle?.visita?.siteNombreSede.orEmpty(),
                jpeg = jpeg,
                checklist = detalle?.checklist.orEmpty(),
            )

            when (val resultado = evidenciaRepository.capturarEvidencia(entrada)) {
                is Resultado.Exito -> {
                    capturando.set(false)
                    _estado.update { it.copy(procesando = false, guardada = true) }
                }
                is Resultado.Fallo -> {
                    capturando.set(false)
                    _estado.update {
                        it.copy(procesando = false, error = resultado.error.mensajeLegible())
                    }
                }
            }
        }
    }

    fun descartarError() = _estado.update { it.copy(error = null) }
}
