package com.verificlean.app.ui.pantallas.gestion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.mensajeLegible
import com.verificlean.app.domain.model.Sitio
import com.verificlean.app.domain.repository.CatalogoRepository
import com.verificlean.app.domain.repository.SitioNuevo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.text.Normalizer
import javax.inject.Inject

/**
 * Formulario de alta de sede.
 *
 * Las validaciones de aquí son sólo para dar respuesta inmediata; el servidor las repite
 * todas. El caso que más importa es el radio: por debajo de 50 m el error típico del GPS
 * balanceado marcaría como fraude a supervisores que sí fueron a la sede.
 */
data class FormularioSede(
    val claveIdempotencia: String = UUID.randomUUID().toString(),
    val clienteId: String = "",
    val nombreCliente: String = "",
    val nombreSede: String = "",
    val direccion: String = "",
    val coordenadasPegadas: String = "",
    val lat: String = "",
    val lng: String = "",
    val radio: String = "150",
    val clienteNuevo: Boolean = false,
    val erroresServidor: Map<String, String> = emptyMap(),
) {
    val latValida: Double? get() = aDecimal(lat)?.takeIf { it in -90.0..90.0 }
    val lngValida: Double? get() = aDecimal(lng)?.takeIf { it in -180.0..180.0 }
    val radioValido: Double? get() = aDecimal(radio)?.takeIf { it in 50.0..2000.0 }

    val errorClienteId: String?
        get() = erroresServidor["clienteId"] ?: when {
            clienteId.isBlank() -> "Elige un cliente existente o crea uno nuevo."
            clienteId.length > 64 -> "Máximo 64 caracteres."
            else -> null
        }
    val errorNombreCliente: String?
        get() = erroresServidor["nombreCliente"] ?: when {
            nombreCliente.isBlank() -> "Escribe el nombre del cliente."
            nombreCliente.length > 160 -> "Máximo 160 caracteres."
            else -> null
        }
    val errorNombreSede: String?
        get() = erroresServidor["nombreSede"] ?: when {
            nombreSede.isBlank() -> "Escribe el nombre de la sede."
            nombreSede.length > 160 -> "Máximo 160 caracteres."
            else -> null
        }
    val errorDireccion: String?
        get() = erroresServidor["direccion"] ?: when {
            direccion.isBlank() -> "Escribe la dirección."
            direccion.length > 300 -> "Máximo 300 caracteres."
            else -> null
        }
    val errorLat: String?
        get() = erroresServidor["lat"] ?: if (lat.isBlank() || latValida != null) null else "Latitud entre -90 y 90"
    val errorLng: String?
        get() = erroresServidor["lng"] ?: if (lng.isBlank() || lngValida != null) null else "Longitud entre -180 y 180"
    val errorRadio: String?
        get() = erroresServidor["radioPermitidoMetros"] ?: if (radio.isBlank() || radioValido != null) {
            null
        } else {
            "Entre 50 y 2000 m. Por debajo de 50 el error del GPS produce falsas alertas."
        }

    val puedeGuardar: Boolean
        get() = errorClienteId == null && errorNombreCliente == null &&
            errorNombreSede == null && errorDireccion == null &&
            latValida != null &&
            lngValida != null &&
            radioValido != null

    companion object {
        /** Identificador opaco para un cliente nuevo. Nunca se deriva del nombre. */
        fun nuevoClienteId(): String = "cli_${UUID.randomUUID().toString().replace("-", "").take(24)}"
        /**
         * Acepta coma como separador decimal.
         *
         * `toDoubleOrNull` sólo entiende el punto, pero el teclado numérico de un teléfono
         * configurado en español de Colombia ofrece coma. Sin esta normalización, un admin
         * que escriba `4,700543` —que es como se escribe aquí— recibiría "Latitud entre
         * -90 y 90" sobre una coordenada perfectamente válida, sin ninguna pista de por qué.
         */
        fun aDecimal(texto: String): Double? = texto.trim().replace(',', '.').toDoubleOrNull()

        /** Lee pares copiados desde Google Maps, con coma o punto decimal. */
        fun coordenadasDesdeTexto(texto: String): Pair<String, String>? {
            val decodificado = runCatching {
                java.net.URLDecoder.decode(texto.trim(), Charsets.UTF_8.name())
            }.getOrDefault(texto.trim())
            val patronUrl = Regex(
                "(?:@|[?&](?:q|query|ll)=)([-+]?\\d{1,2}(?:\\.\\d+)?)[,\\s]+([-+]?\\d{1,3}(?:\\.\\d+)?)",
                RegexOption.IGNORE_CASE,
            )
            patronUrl.find(decodificado)?.let { coincidencia ->
                val lat = coincidencia.groupValues[1]
                val lng = coincidencia.groupValues[2]
                val latNumero = aDecimal(lat)
                val lngNumero = aDecimal(lng)
                if (latNumero != null && lngNumero != null &&
                    latNumero in -90.0..90.0 && lngNumero in -180.0..180.0
                ) {
                    return lat to lng
                }
            }

            val numeros = Regex("[-+]?\\d+(?:[.,]\\d+)?")
                .findAll(texto)
                .map { it.value.replace(',', '.') }
                .toList()
            if (numeros.size != 2) return null
            return numeros.windowed(2).firstOrNull { par ->
                val lat = aDecimal(par[0])
                val lng = aDecimal(par[1])
                lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0
            }?.let { it[0] to it[1] }
        }
    }
}

data class ClienteExistente(val id: String, val nombre: String)

data class EstadoSedes(
    val sedes: List<Sitio> = emptyList(),
    val cargando: Boolean = true,
    val guardando: Boolean = false,
    val formularioAbierto: Boolean = false,
    val formulario: FormularioSede = FormularioSede(),
    val error: String? = null,
    val mensaje: String? = null,
    val sitioPorCambiar: Sitio? = null,
    val cambiandoActivo: Boolean = false,
) {
    val clientes: List<ClienteExistente>
        get() = sedes
            .groupBy { it.clienteId }
            .map { (id, sitios) -> ClienteExistente(id, sitios.first().nombreCliente) }
            .sortedBy { it.nombre.lowercase() }
}

@HiltViewModel
class SedesViewModel @Inject constructor(
    private val catalogo: CatalogoRepository,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoSedes())
    val estado: StateFlow<EstadoSedes> = _estado.asStateFlow()

    init {
        cargar()
    }

    fun cargar() {
        _estado.update { it.copy(cargando = true, error = null) }
        viewModelScope.launch {
            when (val r = catalogo.listarSitios(soloActivos = false)) {
                is Resultado.Exito -> _estado.update { it.copy(cargando = false, sedes = r.datos) }
                is Resultado.Fallo -> _estado.update {
                    it.copy(cargando = false, error = r.error.mensajeLegible())
                }
            }
        }
    }

    fun abrirFormulario() =
        _estado.update { it.copy(formularioAbierto = true, formulario = FormularioSede()) }

    fun cerrarFormulario() = _estado.update { it.copy(formularioAbierto = false) }

    fun editarFormulario(cambio: (FormularioSede) -> FormularioSede) =
        _estado.update {
            it.copy(
                error = null,
                formulario = cambio(it.formulario).copy(
                    claveIdempotencia = UUID.randomUUID().toString(),
                    erroresServidor = emptyMap(),
                ),
            )
        }

    fun elegirCliente(cliente: ClienteExistente) = _estado.update {
        it.copy(
            formulario = it.formulario.copy(
                clienteId = cliente.id,
                nombreCliente = cliente.nombre,
                clienteNuevo = false,
                claveIdempotencia = UUID.randomUUID().toString(),
                erroresServidor = emptyMap(),
            ),
        )
    }

    fun crearClienteNuevo() = _estado.update {
        it.copy(
            formulario = it.formulario.copy(
                clienteNuevo = true,
                clienteId = FormularioSede.nuevoClienteId(),
                nombreCliente = "",
                claveIdempotencia = UUID.randomUUID().toString(),
            ),
        )
    }

    fun pedirCambioActivo(sitio: Sitio) = _estado.update { it.copy(sitioPorCambiar = sitio) }

    fun cancelarCambioActivo() = _estado.update { it.copy(sitioPorCambiar = null) }

    fun confirmarCambioActivo() {
        if (_estado.value.cambiandoActivo) return
        val sitio = _estado.value.sitioPorCambiar ?: return
        _estado.update { it.copy(cambiandoActivo = true, error = null) }
        viewModelScope.launch {
            when (val resultado = catalogo.actualizarSitioActivo(sitio.id, !sitio.activo)) {
                is Resultado.Exito -> _estado.update { estado ->
                    estado.copy(
                        sitioPorCambiar = null,
                        cambiandoActivo = false,
                        sedes = estado.sedes.map { if (it.id == sitio.id) resultado.datos else it },
                        mensaje = if (resultado.datos.activo) {
                            "${sitio.nombreSede} ya puede recibir visitas nuevas."
                        } else {
                            "${sitio.nombreSede} dejó de recibir visitas nuevas. Las ya asignadas continúan."
                        },
                    )
                }
                is Resultado.Fallo -> _estado.update {
                    it.copy(
                        sitioPorCambiar = null,
                        cambiandoActivo = false,
                        error = resultado.error.mensajeLegible(),
                    )
                }
            }
        }
    }

    fun guardar() {
        val f = _estado.value.formulario
        if (!f.puedeGuardar || _estado.value.guardando) return

        if (f.clienteNuevo) {
            val existente = _estado.value.clientes.firstOrNull {
                normalizarNombre(it.nombre) == normalizarNombre(f.nombreCliente)
            }
            if (existente != null) {
                _estado.update {
                    it.copy(
                        formulario = it.formulario.copy(
                            erroresServidor = mapOf(
                                "nombreCliente" to
                                    "Ese cliente ya existe. Elígelo en la pestaña Existente para mantener unidos sus reportes.",
                            ),
                        ),
                    )
                }
                return
            }
        }

        _estado.update { it.copy(guardando = true, error = null) }
        viewModelScope.launch {
            val r = catalogo.crearSitio(
                nuevo = SitioNuevo(
                    clienteId = f.clienteId,
                    nombreCliente = f.nombreCliente,
                    nombreSede = f.nombreSede,
                    direccion = f.direccion,
                    lat = f.latValida!!,
                    lng = f.lngValida!!,
                    radioPermitidoMetros = f.radioValido!!,
                ),
                claveIdempotencia = f.claveIdempotencia,
            )
            when (r) {
                is Resultado.Exito -> {
                    _estado.update {
                        it.copy(
                            guardando = false,
                            formularioAbierto = false,
                            formulario = FormularioSede(),
                            // Se antepone la nueva para que el admin vea de inmediato que
                            // se creó, sin tener que buscarla en una lista larga.
                            sedes = listOf(r.datos) + it.sedes,
                            mensaje = "Sede \"${r.datos.nombreSede}\" creada.",
                        )
                    }
                }
                is Resultado.Fallo -> _estado.update {
                    val errores = (r.error as? ErrorApp.Validacion)?.porCampo.orEmpty()
                    it.copy(
                        guardando = false,
                        formulario = it.formulario.copy(erroresServidor = errores),
                        error = if (errores.isEmpty()) r.error.mensajeLegible() else null,
                    )
                }
            }
        }
    }

    fun descartarAviso() = _estado.update { it.copy(error = null, mensaje = null) }

    private fun normalizarNombre(valor: String): String = Normalizer.normalize(
        valor.trim().lowercase(),
        Normalizer.Form.NFD,
    ).replace("\\p{M}+".toRegex(), "")
}
