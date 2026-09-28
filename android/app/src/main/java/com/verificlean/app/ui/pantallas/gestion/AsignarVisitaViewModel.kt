package com.verificlean.app.ui.pantallas.gestion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.mensajeLegible
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Sitio
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.repository.CatalogoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

data class EstadoAsignacion(
    val sedes: List<Sitio> = emptyList(),
    val supervisores: List<Usuario> = emptyList(),
    val sedeElegida: Sitio? = null,
    val supervisorElegido: Usuario? = null,
    val fecha: LocalDate = LocalDate.now(),
    val claveIdempotencia: String = UUID.randomUUID().toString(),
    val cargando: Boolean = true,
    val asignando: Boolean = false,
    val error: String? = null,
    val errorSedes: String? = null,
    val errorSupervisores: String? = null,
    val asignada: String? = null,
) {
    val puedeAsignar: Boolean
        get() = sedeElegida != null && supervisorElegido != null && !asignando

    /** Sin sedes o sin supervisores no hay nada que asignar; hay que crearlos primero. */
    val faltaCatalogo: Boolean
        get() = !cargando &&
            errorSedes == null && errorSupervisores == null &&
            (sedes.isEmpty() || supervisores.isEmpty())
}

@HiltViewModel
class AsignarVisitaViewModel @Inject constructor(
    private val catalogo: CatalogoRepository,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoAsignacion())
    val estado: StateFlow<EstadoAsignacion> = _estado.asStateFlow()

    init {
        cargar()
    }

    fun cargar() {
        _estado.update {
            it.copy(
                cargando = true,
                error = null,
                errorSedes = null,
                errorSupervisores = null,
            )
        }
        viewModelScope.launch {
            // En paralelo: son dos consultas independientes y encadenarlas duplicaría la
            // espera del admin sin ganar nada.
            val sedes = async { catalogo.listarSitios(soloActivos = true) }
            val supervisores = async { catalogo.listarUsuarios(rol = Rol.SUPERVISOR) }

            val rSedes = sedes.await()
            val rSupervisores = supervisores.await()

            _estado.update {
                it.copy(
                    cargando = false,
                    sedes = (rSedes as? Resultado.Exito)?.datos.orEmpty(),
                    // Sólo supervisores activos: asignarle una visita a alguien dado de
                    // baja la dejaría muerta, porque no puede iniciar sesión.
                    supervisores = (rSupervisores as? Resultado.Exito)?.datos
                        .orEmpty()
                        .filter { u -> u.activo },
                    errorSedes = (rSedes as? Resultado.Fallo)?.error?.mensajeLegible(),
                    errorSupervisores =
                        (rSupervisores as? Resultado.Fallo)?.error?.mensajeLegible(),
                )
            }
        }
    }

    fun elegirSede(sitio: Sitio) = _estado.update {
        if (it.sedeElegida?.id == sitio.id) it else it.copy(
            sedeElegida = sitio,
            claveIdempotencia = UUID.randomUUID().toString(),
        )
    }

    fun elegirSupervisor(usuario: Usuario) = _estado.update {
        if (it.supervisorElegido?.id == usuario.id) it else it.copy(
            supervisorElegido = usuario,
            claveIdempotencia = UUID.randomUUID().toString(),
        )
    }

    fun elegirFecha(fecha: LocalDate) = _estado.update {
        if (it.fecha == fecha) it else it.copy(
            fecha = fecha,
            claveIdempotencia = UUID.randomUUID().toString(),
        )
    }

    fun asignar() {
        val e = _estado.value
        if (!e.puedeAsignar) return

        _estado.update { it.copy(asignando = true, error = null) }
        viewModelScope.launch {
            // Se asigna a las 8:00 locales: la hora concreta no la usa ninguna regla
            // antifraude —el servidor sella su propio timestamp al recibir la evidencia—
            // pero una hora razonable hace la lista del supervisor legible.
            val instante: Instant = e.fecha
                .atTime(LocalTime.of(8, 0))
                .atZone(ZoneId.systemDefault())
                .toInstant()

            val r = catalogo.asignarVisita(
                siteId = e.sedeElegida!!.id,
                supervisorId = e.supervisorElegido!!.id,
                fechaAsignada = instante,
                claveIdempotencia = e.claveIdempotencia,
            )
            _estado.update { estado ->
                when (r) {
                    is Resultado.Exito -> estado.copy(
                        asignando = false,
                        asignada = "Visita asignada a ${e.supervisorElegido.nombre} en ${e.sedeElegida.nombreSede}.",
                        // Se limpia la selección para poder encadenar varias asignaciones
                        // sin salir de la pantalla, que es como se planifica una jornada.
                        supervisorElegido = null,
                        claveIdempotencia = UUID.randomUUID().toString(),
                    )
                    is Resultado.Fallo -> estado.copy(
                        asignando = false,
                        error = r.error.mensajeLegible(),
                    )
                }
            }
        }
    }

    fun descartarAviso() = _estado.update { it.copy(error = null, asignada = null) }
}
