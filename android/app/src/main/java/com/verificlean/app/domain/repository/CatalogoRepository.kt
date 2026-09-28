package com.verificlean.app.domain.repository

import com.verificlean.app.core.Resultado
import com.verificlean.app.domain.model.Reporte
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Sitio
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.model.Visita
import java.time.Instant

/**
 * Operaciones de administración: alta de sedes, alta de supervisores, asignación de
 * visitas y reportes.
 *
 * Va aparte de `AdminRepository` —que es sólo consulta del dashboard— porque son cosas
 * distintas: una responde "¿hubo fraude?" y la otra "¿quién trabaja dónde?". Mezclarlas
 * dejaría una interfaz que ningún ViewModel usa entera.
 *
 * Sin caché local, igual que el resto de lo administrativo: el admin trabaja en oficina y
 * estos datos cambian poco pero deben verse frescos — un supervisor dado de baja que
 * siguiera apareciendo en la lista de asignación sería un error caro.
 */
interface CatalogoRepository {

    suspend fun listarSitios(soloActivos: Boolean = true): Resultado<List<Sitio>>

    suspend fun crearSitio(nuevo: SitioNuevo, claveIdempotencia: String): Resultado<Sitio>

    suspend fun actualizarSitioActivo(id: String, activo: Boolean): Resultado<Sitio>

    suspend fun listarUsuarios(rol: Rol? = null): Resultado<List<Usuario>>

    suspend fun crearUsuario(nuevo: UsuarioNuevo): Resultado<Usuario>

    suspend fun actualizarUsuarioActivo(id: String, activo: Boolean): Resultado<Usuario>

    suspend fun asignarVisita(
        siteId: String,
        supervisorId: String,
        fechaAsignada: Instant,
        claveIdempotencia: String,
    ): Resultado<Visita>

    suspend fun reporte(clienteId: String, desde: Instant, hasta: Instant): Resultado<Reporte>
}

/**
 * Datos para dar de alta una sede.
 *
 * `radioPermitidoMetros` tiene mínimo 50 por decisión de producto, no por capricho: por
 * debajo de eso el error típico de `PRIORITY_BALANCED_POWER_ACCURACY` (~30-60 m en
 * interiores) marcaría como fraude a supervisores que sí estuvieron en la sede. El
 * servidor lo rechaza igualmente; validarlo aquí sólo evita el viaje.
 */
data class SitioNuevo(
    val clienteId: String,
    val nombreCliente: String,
    val nombreSede: String,
    val direccion: String,
    val lat: Double,
    val lng: Double,
    val radioPermitidoMetros: Double = 150.0,
)

data class UsuarioNuevo(
    val nombre: String,
    val email: String,
    val password: String,
    val rol: Rol,
)
