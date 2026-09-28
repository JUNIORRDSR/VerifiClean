package com.verificlean.app.dobles

import com.verificlean.app.core.Resultado
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.Reporte
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Sitio
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.model.Visita
import com.verificlean.app.domain.repository.CatalogoRepository
import com.verificlean.app.domain.repository.SitioNuevo
import com.verificlean.app.domain.repository.UsuarioNuevo
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant

/**
 * Doble del catálogo administrativo, compartido por los tests de los ViewModels de gestión
 * y de reportes.
 *
 * Hace tres cosas que un mock genérico no da barato:
 *
 * 1. **Cuenta llamadas simultáneas.** `maximoEnVuelo` es la única forma de distinguir dos
 *    consultas en paralelo de dos encadenadas; ambas terminan igual y el estado final no
 *    delata la diferencia.
 * 2. **Retiene las respuestas** con `compuerta`, para observar el estado intermedio
 *    (cargando, guardando) y para provocar cancelaciones reales.
 * 3. **Registra los parámetros**, que es donde vive el riesgo: pedir usuarios sin filtrar
 *    por rol traería administradores a una lista de asignación.
 */
class CatalogoFalso : CatalogoRepository {

    var respuestaSitios: Resultado<List<Sitio>> = Resultado.Exito(emptyList())
    var respuestaUsuarios: Resultado<List<Usuario>> = Resultado.Exito(emptyList())
    var respuestaCrearSitio: (SitioNuevo) -> Resultado<Sitio> = { Resultado.Exito(sitioDesde(it)) }
    var respuestaCrearUsuario: (UsuarioNuevo) -> Resultado<Usuario> =
        { Resultado.Exito(usuarioDesde(it)) }
    var respuestaAsignar: (Asignacion) -> Resultado<Visita> = { Resultado.Exito(visitaDesde(it)) }
    var respuestaReporte: (String) -> Resultado<Reporte> = { Resultado.Exito(reporteDe(it)) }

    /** Mientras no sea nula, toda operación se queda esperando aquí antes de responder. */
    var compuerta: CompletableDeferred<Unit>? = null

    private var enVuelo = 0
    var maximoEnVuelo = 0
        private set

    var rolPedido: Rol? = null
        private set
    var soloActivosPedido: Boolean? = null
        private set

    val sitiosCreados = mutableListOf<SitioNuevo>()
    val clavesCrearSitio = mutableListOf<String>()
    val usuariosCreados = mutableListOf<UsuarioNuevo>()
    val asignaciones = mutableListOf<Asignacion>()
    val reportesPedidos = mutableListOf<PeticionReporte>()

    override suspend fun listarSitios(soloActivos: Boolean): Resultado<List<Sitio>> {
        soloActivosPedido = soloActivos
        return responder { respuestaSitios }
    }

    override suspend fun crearSitio(nuevo: SitioNuevo, claveIdempotencia: String): Resultado<Sitio> {
        sitiosCreados += nuevo
        clavesCrearSitio += claveIdempotencia
        return responder { respuestaCrearSitio(nuevo) }
    }

    override suspend fun actualizarSitioActivo(id: String, activo: Boolean): Resultado<Sitio> =
        responder { Resultado.Exito(sitio(id = id, activo = activo)) }

    override suspend fun listarUsuarios(rol: Rol?): Resultado<List<Usuario>> {
        rolPedido = rol
        return responder { respuestaUsuarios }
    }

    override suspend fun crearUsuario(nuevo: UsuarioNuevo): Resultado<Usuario> {
        usuariosCreados += nuevo
        return responder { respuestaCrearUsuario(nuevo) }
    }

    override suspend fun actualizarUsuarioActivo(id: String, activo: Boolean): Resultado<Usuario> =
        responder { Resultado.Exito(usuario(id = id, activo = activo)) }

    override suspend fun asignarVisita(
        siteId: String,
        supervisorId: String,
        fechaAsignada: Instant,
        claveIdempotencia: String,
    ): Resultado<Visita> {
        val asignacion = Asignacion(siteId, supervisorId, fechaAsignada, claveIdempotencia)
        asignaciones += asignacion
        return responder { respuestaAsignar(asignacion) }
    }

    override suspend fun reporte(
        clienteId: String,
        desde: Instant,
        hasta: Instant,
    ): Resultado<Reporte> {
        reportesPedidos += PeticionReporte(clienteId, desde, hasta)
        return responder { respuestaReporte(clienteId) }
    }

    /**
     * El contador sube antes de esperar y baja al salir, incluso por cancelación: si no
     * bajara en el `finally`, un test que cancele una consulta dejaría el máximo inflado y
     * el siguiente vería un paralelismo que no existió.
     */
    private suspend fun <T> responder(valor: () -> T): T {
        enVuelo++
        maximoEnVuelo = maxOf(maximoEnVuelo, enVuelo)
        try {
            compuerta?.await()
            return valor()
        } finally {
            enVuelo--
        }
    }

    data class Asignacion(
        val siteId: String,
        val supervisorId: String,
        val fechaAsignada: Instant,
        val claveIdempotencia: String,
    )

    data class PeticionReporte(val clienteId: String, val desde: Instant, val hasta: Instant)

    companion object {

        fun sitio(
            id: String = "site_1",
            clienteId: String = "cli_1",
            nombreCliente: String = "Cliente Uno",
            nombreSede: String = "Sede Uno",
            activo: Boolean = true,
        ) = Sitio(
            id = id,
            clienteId = clienteId,
            nombreCliente = nombreCliente,
            nombreSede = nombreSede,
            direccion = "Cra 1 #2-3",
            lat = 4.7,
            lng = -74.06,
            radioPermitidoMetros = 150.0,
            activo = activo,
        )

        fun usuario(
            id: String = "usr_1",
            nombre: String = "Carlos Ruiz",
            email: String = "carlos@verificlean.test",
            rol: Rol = Rol.SUPERVISOR,
            activo: Boolean = true,
        ) = Usuario(id = id, nombre = nombre, email = email, rol = rol, activo = activo)

        fun reporteDe(clienteId: String) = Reporte(
            clienteId = clienteId,
            nombreCliente = clienteId,
            desde = Instant.EPOCH,
            hasta = Instant.EPOCH,
            visitasAsignadas = 3,
            visitasCompletadas = 1,
            visitasPendientes = 2,
            cumplimientoPorcentaje = 33.3,
            evidenciasTotales = 1,
            evidenciasSospechosas = 0,
            motivosFrecuentes = emptyList(),
            porSede = emptyList(),
        )

        fun sitioDesde(nuevo: SitioNuevo) = Sitio(
            id = "site_nuevo",
            clienteId = nuevo.clienteId,
            nombreCliente = nuevo.nombreCliente,
            nombreSede = nuevo.nombreSede,
            direccion = nuevo.direccion,
            lat = nuevo.lat,
            lng = nuevo.lng,
            radioPermitidoMetros = nuevo.radioPermitidoMetros,
            activo = true,
        )

        private fun usuarioDesde(nuevo: UsuarioNuevo) = Usuario(
            id = "usr_nuevo",
            nombre = nuevo.nombre,
            email = nuevo.email,
            rol = nuevo.rol,
            activo = true,
        )

        fun visitaDesde(a: Asignacion) = Visita(
            id = "vis_nueva",
            siteId = a.siteId,
            siteClienteId = "cli_1",
            siteNombreCliente = "Cliente Uno",
            siteNombreSede = "Sede Uno",
            supervisorId = a.supervisorId,
            supervisorNombre = "Carlos Ruiz",
            estado = EstadoVisita.PENDIENTE,
            fechaAsignada = a.fechaAsignada,
            completadaEn = null,
            evidenciaCount = 0,
            tieneSospechosas = false,
        )
    }
}
