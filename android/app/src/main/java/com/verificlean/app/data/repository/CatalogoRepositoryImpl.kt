package com.verificlean.app.data.repository

import com.verificlean.app.core.DispatcherIo
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.map
import com.verificlean.app.data.remote.VerifiCleanApi
import com.verificlean.app.data.remote.aDominio
import com.verificlean.app.data.remote.dto.SitioNuevoDto
import com.verificlean.app.data.remote.dto.CambioActivoDto
import com.verificlean.app.data.remote.dto.UsuarioNuevoDto
import com.verificlean.app.data.remote.dto.VisitaNuevaDto
import com.verificlean.app.data.remote.llamadaApi
import com.verificlean.app.domain.model.Reporte
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Sitio
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.model.Visita
import com.verificlean.app.domain.repository.CatalogoRepository
import com.verificlean.app.domain.repository.SitioNuevo
import com.verificlean.app.domain.repository.UsuarioNuevo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogoRepositoryImpl @Inject constructor(
    private val api: VerifiCleanApi,
    @DispatcherIo private val io: CoroutineDispatcher,
) : CatalogoRepository {

    override suspend fun listarSitios(soloActivos: Boolean): Resultado<List<Sitio>> =
        withContext(io) {
            llamadaApi { api.listarSitios(activo = if (soloActivos) true else null) }
                .map { dto -> dto.items.map { it.aDominio() } }
        }

    override suspend fun crearSitio(
        nuevo: SitioNuevo,
        claveIdempotencia: String,
    ): Resultado<Sitio> = withContext(io) {
        llamadaApi {
            api.crearSitio(
                claveIdempotencia = claveIdempotencia,
                cuerpo = SitioNuevoDto(
                    clienteId = nuevo.clienteId.trim(),
                    nombreCliente = nuevo.nombreCliente.trim(),
                    nombreSede = nuevo.nombreSede.trim(),
                    direccion = nuevo.direccion.trim(),
                    lat = nuevo.lat,
                    lng = nuevo.lng,
                    radioPermitidoMetros = nuevo.radioPermitidoMetros,
                ),
            )
        }.map { it.aDominio() }
    }

    override suspend fun actualizarSitioActivo(id: String, activo: Boolean): Resultado<Sitio> = withContext(io) {
        llamadaApi { api.actualizarSitioActivo(id, CambioActivoDto(activo)) }.map { it.aDominio() }
    }

    override suspend fun listarUsuarios(rol: Rol?): Resultado<List<Usuario>> = withContext(io) {
        val filtro = when (rol) {
            Rol.ADMIN -> "admin"
            Rol.SUPERVISOR -> "supervisor"
            null -> null
        }
        llamadaApi { api.listarUsuarios(rol = filtro) }
            .map { dto -> dto.items.map { it.aDominio() } }
    }

    override suspend fun crearUsuario(nuevo: UsuarioNuevo): Resultado<Usuario> = withContext(io) {
        llamadaApi {
            api.crearUsuario(
                UsuarioNuevoDto(
                    nombre = nuevo.nombre.trim(),
                    // El servidor normaliza a minúsculas de todas formas; recortarlo aquí
                    // evita que un espacio pegado al copiar produzca un 400.
                    email = nuevo.email.trim(),
                    password = nuevo.password,
                    rol = if (nuevo.rol == Rol.ADMIN) "admin" else "supervisor",
                ),
            )
        }.map { it.aDominio() }
    }

    override suspend fun actualizarUsuarioActivo(id: String, activo: Boolean): Resultado<Usuario> = withContext(io) {
        llamadaApi { api.actualizarUsuarioActivo(id, CambioActivoDto(activo)) }.map { it.aDominio() }
    }

    override suspend fun asignarVisita(
        siteId: String,
        supervisorId: String,
        fechaAsignada: Instant,
        claveIdempotencia: String,
    ): Resultado<Visita> = withContext(io) {
        llamadaApi {
            api.asignarVisita(
                claveIdempotencia = claveIdempotencia,
                cuerpo = VisitaNuevaDto(
                    siteId = siteId,
                    supervisorId = supervisorId,
                    fechaAsignada = fechaAsignada.toString(),
                ),
            )
        }.map { it.aDominio().visita }
    }

    override suspend fun reporte(
        clienteId: String,
        desde: Instant,
        hasta: Instant,
    ): Resultado<Reporte> = withContext(io) {
        llamadaApi {
            api.adminReporte(
                clienteId = clienteId,
                desde = desde.toString(),
                hasta = hasta.toString(),
            )
        }.map { it.aDominio() }
    }
}
