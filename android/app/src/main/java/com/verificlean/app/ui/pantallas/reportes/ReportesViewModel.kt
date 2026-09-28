package com.verificlean.app.ui.pantallas.reportes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.mensajeLegible
import com.verificlean.app.domain.model.Reporte
import com.verificlean.app.domain.model.Sitio
import com.verificlean.app.domain.repository.CatalogoRepository
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
import java.text.Normalizer
import javax.inject.Inject

/** Rangos que un admin pide de verdad, para no obligarle a elegir dos fechas cada vez. */
enum class RangoReporte(val etiqueta: String, val dias: Long) {
    ULTIMA_SEMANA("Última semana", 7),
    ULTIMO_MES("Último mes", 30),
    ULTIMO_TRIMESTRE("Último trimestre", 90),
}

data class ClienteConSedes(val clienteId: String, val nombreCliente: String, val sedes: Int)

data class EstadoReportes(
    val clientes: List<ClienteConSedes> = emptyList(),
    val clienteElegido: ClienteConSedes? = null,
    val rango: RangoReporte = RangoReporte.ULTIMO_MES,
    val reporte: Reporte? = null,
    val reporteAnterior: Reporte? = null,
    val cargandoClientes: Boolean = true,
    val cargandoReporte: Boolean = false,
    val error: String? = null,
    val errorComparacion: String? = null,
)

@HiltViewModel
class ReportesViewModel @Inject constructor(
    private val catalogo: CatalogoRepository,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoReportes())
    val estado: StateFlow<EstadoReportes> = _estado.asStateFlow()

    /** Igual que en el panel: cambiar de cliente cancela la consulta anterior. */
    private var trabajo: Job? = null

    init {
        cargarClientes()
    }

    /**
     * La lista de clientes se deriva de las sedes.
     *
     * No hay endpoint de clientes porque no hay colección de clientes: el cliente vive
     * denormalizado en cada sede (ver `contracts/firestore.schema.md`). Agruparlas aquí
     * evita inventar una entidad en el servidor sólo para llenar un desplegable.
     */
    fun cargarClientes() {
        _estado.update { it.copy(cargandoClientes = true, error = null) }
        viewModelScope.launch {
            when (val r = catalogo.listarSitios(soloActivos = false)) {
                is Resultado.Exito -> {
                    val conflictos = conflictosDeIdentidad(r.datos)
                    if (conflictos != null) {
                        _estado.update {
                            it.copy(
                                cargandoClientes = false,
                                clientes = emptyList(),
                                error = conflictos,
                            )
                        }
                        return@launch
                    }
                    val clientes = r.datos
                        .groupBy { s: Sitio -> s.clienteId }
                        .map { (id, sedes) ->
                            ClienteConSedes(id, sedes.first().nombreCliente, sedes.size)
                        }
                        .sortedBy { it.nombreCliente }

                    _estado.update {
                        it.copy(cargandoClientes = false, clientes = clientes)
                    }
                    // Con un solo cliente, elegirlo por él y cargar: obligarle a tocar un
                    // desplegable de un elemento es fricción sin propósito.
                    clientes.singleOrNull()?.let(::elegirCliente)
                }
                is Resultado.Fallo -> _estado.update {
                    it.copy(cargandoClientes = false, error = r.error.mensajeLegible())
                }
            }
        }
    }

    fun elegirCliente(cliente: ClienteConSedes) {
        _estado.update { it.copy(clienteElegido = cliente) }
        generar()
    }

    fun elegirRango(rango: RangoReporte) {
        _estado.update { it.copy(rango = rango) }
        generar()
    }

    fun generar() {
        val e = _estado.value
        val cliente = e.clienteElegido ?: return

        trabajo?.cancel()
        // Se borra el reporte anterior: si no, al cambiar de cliente o de rango la pantalla
        // enseña el chip nuevo junto a las cifras viejas durante toda la espera —cifras de
        // Alfa bajo el nombre de Beta— y estos números se usan para facturar.
        _estado.update {
            it.copy(
                cargandoReporte = true,
                reporte = null,
                reporteAnterior = null,
                error = null,
                errorComparacion = null,
            )
        }

        trabajo = viewModelScope.launch {
            val zona = ZoneId.systemDefault()
            // `LocalDate.now()` una sola vez: llamarlo dos veces permite que una consulta
            // lanzada justo al cambiar el día calcule `hasta` con ayer y `desde` con hoy.
            val hoy = LocalDate.now()
            val hasta = hoy.atTime(LocalTime.MAX).atZone(zona).toInstant()
            // `dias - 1` porque los dos extremos son inclusivos y hoy cuenta: "última
            // semana" son siete fechas naturales contando hoy, no ocho.
            val desde = hoy.minusDays(e.rango.dias - 1)
                .atStartOfDay(zona).toInstant()
            val hastaAnterior = hoy.minusDays(e.rango.dias)
                .atTime(LocalTime.MAX).atZone(zona).toInstant()
            val desdeAnterior = hoy.minusDays((e.rango.dias * 2) - 1)
                .atStartOfDay(zona).toInstant()

            when (val r = catalogo.reporte(cliente.clienteId, desde, hasta)) {
                is Resultado.Exito -> {
                    val anterior = catalogo.reporte(cliente.clienteId, desdeAnterior, hastaAnterior)
                    _estado.update {
                        it.copy(
                            cargandoReporte = false,
                            reporte = r.datos,
                            reporteAnterior = (anterior as? Resultado.Exito)?.datos,
                            errorComparacion = (anterior as? Resultado.Fallo)?.error?.mensajeLegible(),
                        )
                    }
                }
                is Resultado.Fallo -> _estado.update {
                    it.copy(
                        cargandoReporte = false,
                        reporte = null,
                        // El servidor devuelve 400 cuando el rango excede 5000 visitas, en
                        // vez de truncar en silencio. Ese mensaje ya explica qué hacer.
                        error = when (val err = r.error) {
                            is ErrorApp.Validacion -> err.mensaje
                            else -> err.mensajeLegible()
                        },
                    )
                }
            }
        }
    }

    fun descartarError() = _estado.update { it.copy(error = null) }

    private fun conflictosDeIdentidad(sitios: List<Sitio>): String? {
        val nombrePorIdInconsistente = sitios.groupBy { it.clienteId }.entries.firstOrNull { (_, sedes) ->
            sedes.map { normalizarNombre(it.nombreCliente) }.distinct().size > 1
        }
        if (nombrePorIdInconsistente != null) {
            return "El cliente ${nombrePorIdInconsistente.key} tiene nombres diferentes entre sus sedes. Corrige el catálogo antes de generar un reporte facturable."
        }

        val idsPorNombre = sitios.groupBy { normalizarNombre(it.nombreCliente) }
            .entries.firstOrNull { (_, sedes) -> sedes.map { it.clienteId }.distinct().size > 1 }
        if (idsPorNombre != null) {
            val nombre = idsPorNombre.value.first().nombreCliente
            return "El cliente $nombre aparece con identificadores diferentes. Unifica sus sedes antes de generar el reporte."
        }
        return null
    }

    private fun normalizarNombre(valor: String): String = Normalizer.normalize(
        valor.trim().lowercase(),
        Normalizer.Form.NFD,
    ).replace("\\p{M}+".toRegex(), "")
}
