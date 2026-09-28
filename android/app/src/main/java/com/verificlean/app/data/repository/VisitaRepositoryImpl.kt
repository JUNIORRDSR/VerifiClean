package com.verificlean.app.data.repository

import com.verificlean.app.core.DispatcherIo
import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.core.map
import com.verificlean.app.data.local.VisitaDao
import com.verificlean.app.data.local.aDominio
import com.verificlean.app.data.local.aEntidad
import com.verificlean.app.data.remote.VerifiCleanApi
import com.verificlean.app.data.remote.aDominio
import com.verificlean.app.data.remote.aDto
import com.verificlean.app.data.remote.aWire
import com.verificlean.app.data.remote.dto.CompletarVisitaRequestDto
import com.verificlean.app.data.remote.llamadaApi
import com.verificlean.app.domain.model.ChecklistItem
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.Visita
import com.verificlean.app.domain.model.VisitaDetalle
import com.verificlean.app.domain.repository.VisitaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Visitas con caché local como fuente de verdad para la UI.
 *
 * El patrón es offline-first, no cache-aside: la pantalla observa Room y **sólo** Room.
 * La red escribe en Room y Room notifica. Así la bandeja se pinta al instante aunque no
 * haya señal, y no hay dos caminos por los que puedan llegar datos a la UI.
 */
@Singleton
class VisitaRepositoryImpl @Inject constructor(
    private val api: VerifiCleanApi,
    private val visitaDao: VisitaDao,
    @DispatcherIo private val io: CoroutineDispatcher,
) : VisitaRepository {

    override fun observarVisitas(estado: EstadoVisita?): Flow<List<Visita>> {
        val flujo = if (estado == null) {
            visitaDao.observarTodas()
        } else {
            visitaDao.observarPorEstado(estado.aWire())
        }
        return flujo.map { entidades -> entidades.map { it.aDominio() } }
    }

    override suspend fun refrescarVisitas(): Resultado<Unit> = withContext(io) {
        when (val respuesta = llamadaApi { api.listarVisitas(limite = LIMITE_PAGINA) }) {
            is Resultado.Fallo -> respuesta
            is Resultado.Exito -> {
                // Se conserva lo que ya había de cada visita porque el resumen no trae
                // lat/lng ni checklist; sin esto, refrescar borraría los datos que
                // necesita la pantalla de captura para funcionar sin conexión.
                val entidades = respuesta.datos.items.map { dto ->
                    dto.aEntidad(previa = visitaDao.obtener(dto.id))
                }
                visitaDao.reemplazar(entidades)
                Resultado.Exito(Unit)
            }
        }
    }

    override suspend fun obtenerDetalle(visitId: String): Resultado<VisitaDetalle> =
        withContext(io) {
            val respuesta = llamadaApi { api.obtenerVisita(visitId) }
            when (respuesta) {
                is Resultado.Exito -> {
                    // El detalle sí trae todo: se guarda para que la captura funcione luego
                    // aunque se pierda la señal.
                    visitaDao.guardar(respuesta.datos.aEntidad())
                    respuesta.map { it.aDominio() }
                }
                is Resultado.Fallo -> {
                    // La bandeja es offline-first, pero antes abrir una de sus tarjetas
                    // volvía a exigir red. Si el detalle ya se consultó alguna vez, Room
                    // tiene dirección y checklist suficientes para continuar la ronda.
                    if (respuesta.error == ErrorApp.Red) {
                        visitaDao.obtener(visitId)?.let { Resultado.Exito(it.aDetalleDominio()) }
                            ?: respuesta
                    } else {
                        respuesta
                    }
                }
            }
        }

    override suspend fun completarVisita(
        visitId: String,
        checklist: List<ChecklistItem>,
        observaciones: String?,
    ): Resultado<VisitaDetalle> = withContext(io) {
        val respuesta = llamadaApi {
            api.completarVisita(
                visitId,
                CompletarVisitaRequestDto(
                    checklist = checklist.map { it.aDto() },
                    observaciones = observaciones,
                ),
            )
        }
        if (respuesta is Resultado.Exito) {
            visitaDao.guardar(respuesta.datos.aEntidad())
        }
        respuesta.map { it.aDominio() }
    }

    /** Recuperado desde la caché, sin red. Lo usa la pantalla de captura. */
    suspend fun detalleCacheado(visitId: String): VisitaDetalle? = withContext(io) {
        visitaDao.obtener(visitId)?.aDetalleDominio()
    }

    private fun com.verificlean.app.data.local.VisitaEntity.aDetalleDominio() = VisitaDetalle(
        visita = aDominio(),
        siteDireccion = siteDireccion,
        siteLat = siteLat,
        siteLng = siteLng,
        // La caché del supervisor tampoco expone el umbral, ni siquiera con datos
        // guardados de antes de que el servidor dejara de mandarlo.
        siteRadioPermitidoMetros = null,
        iniciadaEn = null,
        observaciones = observaciones,
        checklist = com.verificlean.app.data.local.deserializarChecklist(checklistJson),
        evidencias = emptyList(),
    )

    private companion object {
        /** Una ronda diaria de un supervisor no pasa de unas pocas decenas de visitas. */
        const val LIMITE_PAGINA = 100
    }
}
