package com.verificlean.app.data.repository

import com.verificlean.app.core.DispatcherIo
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.map
import com.verificlean.app.data.remote.VerifiCleanApi
import com.verificlean.app.data.remote.aDominio
import com.verificlean.app.data.remote.aWire
import com.verificlean.app.data.remote.dto.RevisionAlertaRequestDto
import com.verificlean.app.data.remote.llamadaApi
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.EstadoRevisionAlerta
import com.verificlean.app.domain.model.VisitaDetalleAdmin
import com.verificlean.app.domain.repository.AdminRepository
import com.verificlean.app.domain.repository.PaginaVisitas
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Panel de admin. Sin caché local, a diferencia de las visitas del supervisor: el admin
 * trabaja en oficina con conexión, y cachear un dashboard con filtros arbitrarios
 * significaría replicar en Room las consultas del servidor sin ninguna ganancia.
 */
@Singleton
class AdminRepositoryImpl @Inject constructor(
    private val api: VerifiCleanApi,
    @DispatcherIo private val io: CoroutineDispatcher,
) : AdminRepository {

    override suspend fun listarVisitas(
        soloSospechosas: Boolean,
        siteId: String?,
        estado: EstadoVisita?,
        desde: Instant?,
        hasta: Instant?,
        cursor: String?,
    ): Resultado<PaginaVisitas> = withContext(io) {
        llamadaApi {
            api.adminListarVisitas(
                siteId = siteId,
                estado = estado?.aWire(),
                // `null` y no `false`: mandar `sospechosas=false` sería un filtro
                // explícito y el servidor lo trata igual, pero deja la query sucia.
                sospechosas = if (soloSospechosas) true else null,
                desde = desde?.toString(),
                hasta = hasta?.toString(),
                cursor = cursor,
            )
        }.map { dto ->
            PaginaVisitas(
                items = dto.items.map { it.aDominio() },
                siguienteCursor = dto.siguienteCursor,
            )
        }
    }

    override suspend fun obtenerDetalle(visitId: String): Resultado<VisitaDetalleAdmin> =
        withContext(io) {
            llamadaApi { api.adminObtenerVisita(visitId) }.map { it.aDominio() }
        }

    override suspend fun actualizarRevisionAlerta(
        visitId: String,
        estado: EstadoRevisionAlerta,
        nota: String?,
    ): Resultado<VisitaDetalleAdmin> = withContext(io) {
        llamadaApi {
            api.adminActualizarRevisionAlerta(
                id = visitId,
                cuerpo = RevisionAlertaRequestDto(
                    estado = when (estado) {
                        EstadoRevisionAlerta.PENDIENTE -> "pendiente"
                        EstadoRevisionAlerta.REVISADA -> "revisada"
                        EstadoRevisionAlerta.JUSTIFICADA -> "justificada"
                        EstadoRevisionAlerta.ESCALADA -> "escalada"
                    },
                    nota = nota,
                ),
            )
        }.map { it.aDominio() }
    }
}
