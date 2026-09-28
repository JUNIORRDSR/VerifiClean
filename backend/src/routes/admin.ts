import { Router } from 'express'
import type { Contenedor } from '../container'
import {
  adminVisitasQuerySchema,
  reporteQuerySchema,
  revisionAlertaInputSchema,
} from '../domain/schemas'
import type { Evidencia, MotivoAlerta, Visita } from '../domain/types'
import { noEncontrado, solicitudInvalida } from '../lib/errors'
import { paramTexto } from '../lib/http'
import { actorDe } from '../middleware/auth'
import { visitaDetalleAdminDto, visitaResumenDto } from './mappers'

/** Rutas del dashboard. Todo el router va detrás de `exigirAdmin`. */
export function rutasAdmin(c: Contenedor): Router {
  const router = Router()

  router.get('/visits', async (req, res) => {
    const q = adminVisitasQuerySchema.parse(req.query)
    const pagina = await c.db.visitas.listar({
      ...(q.supervisorId ? { supervisorId: q.supervisorId } : {}),
      ...(q.siteId ? { siteId: q.siteId } : {}),
      ...(q.estado ? { estado: q.estado } : {}),
      ...(q.sospechosas === true ? { soloSospechosas: true } : {}),
      ...(q.desde ? { desde: q.desde } : {}),
      ...(q.hasta ? { hasta: q.hasta } : {}),
      ...(q.cursor ? { cursor: q.cursor } : {}),
      limite: q.limite,
    })
    res.json({
      items: pagina.items.map(visitaResumenDto),
      siguienteCursor: pagina.siguienteCursor,
    })
  })

  router.get('/visits/:id', async (req, res) => {
    const id = paramTexto(req, 'id')

    const visita = await c.db.visitas.obtener(id)
    if (!visita) throw noEncontrado('Visita no encontrada.')

    const evidencias = await c.db.evidencias.porVisita(visita.id)
    // Las URLs se firman en paralelo: son N llamadas independientes a Storage y en
    // serie convertirían un detalle de 10 fotos en varios segundos de espera.
    const urls = new Map<string, string | null>(
      await Promise.all(
        evidencias.map(
          async (e) =>
            [
              e.id,
              await c.almacenamiento.urlFirmada(e.storagePath, c.env.URL_FIRMADA_MINUTOS),
            ] as const,
        ),
      ),
    )

    res.json(visitaDetalleAdminDto(visita, evidencias, urls))
  })

  router.patch('/visits/:id/alert-review', async (req, res) => {
    const id = paramTexto(req, 'id')
    const entrada = revisionAlertaInputSchema.parse(req.body)
    const actor = actorDe(req)

    const visita = await c.db.visitas.obtener(id)
    if (!visita) throw noEncontrado('Visita no encontrada.')
    if (!visita.tieneSospechosas) {
      throw solicitudInvalida('La visita no tiene alertas que revisar.')
    }

    const actualizada = await c.db.visitas.actualizar(id, {
      revisionAlerta:
        entrada.estado === 'pendiente'
          ? {
              estado: 'pendiente',
              nota: null,
              revisadaPorId: null,
              revisadaPorNombre: null,
              revisadaEn: null,
            }
          : {
              estado: entrada.estado,
              nota: entrada.nota ?? null,
              revisadaPorId: actor.uid,
              revisadaPorNombre: actor.usuario.nombre,
              revisadaEn: new Date(),
            },
    })

    const evidencias = await c.db.evidencias.porVisita(actualizada.id)
    const urls = new Map<string, string | null>(
      await Promise.all(
        evidencias.map(
          async (e) =>
            [
              e.id,
              await c.almacenamiento.urlFirmada(e.storagePath, c.env.URL_FIRMADA_MINUTOS),
            ] as const,
        ),
      ),
    )
    res.json(visitaDetalleAdminDto(actualizada, evidencias, urls))
  })

  /**
   * Reporte para entregarle al cliente final: cumplimiento y alertas de un cliente en
   * un rango de fechas.
   *
   * Se pagina internamente hasta agotar el rango en vez de traer todo de un golpe;
   * Firestore no tiene `GROUP BY` y el agregado se hace en memoria, así que el límite
   * real es cuántas visitas caben en la instancia. Con lotes de 200 y un rango mensual
   * típico esto se queda muy por debajo.
   */
  router.get('/reports', async (req, res) => {
    const q = reporteQuerySchema.parse(req.query)

    const recorrido = await recorrerVisitas(c, {
      siteClienteId: q.clienteId,
      desde: q.desde,
      hasta: q.hasta,
    })

    // Un reporte truncado en silencio es peor que ninguno: los totales y el porcentaje de
    // cumplimiento saldrían plausibles pero falsos, y alguien tomaría decisiones sobre un
    // contrato con ellos. Se rechaza y se pide acotar el rango.
    if (recorrido.truncado) {
      throw solicitudInvalida(
        `El rango cubre más de ${MAX_VISITAS_REPORTE} visitas y el reporte saldría incompleto. ` +
          'Acota el rango de fechas.',
        { visitasEncontradas: recorrido.visitas.length, maximo: MAX_VISITAS_REPORTE },
      )
    }

    const visitas = recorrido.visitas
    const evidencias =
      visitas.length > 0 ? await c.db.evidencias.porVisitas(visitas.map((v) => v.id)) : []

    res.json(construirReporte(q.clienteId, q.desde, q.hasta, visitas, evidencias))
  })

  return router
}

/**
 * Techo del reporte. Firestore no tiene `GROUP BY`, así que el agregado se hace en
 * memoria y el límite real es cuántas visitas caben en la instancia. Con lotes de 200 y
 * un rango mensual típico esto queda muy por debajo.
 */
const TAM_PAGINA_REPORTE = 200
const MAX_PAGINAS_REPORTE = 25
export const MAX_VISITAS_REPORTE = TAM_PAGINA_REPORTE * MAX_PAGINAS_REPORTE

async function recorrerVisitas(
  c: Contenedor,
  filtro: { siteClienteId: string; desde: Date; hasta: Date },
): Promise<{ visitas: Visita[]; truncado: boolean }> {
  const acumulado: Visita[] = []
  let cursor: string | undefined

  for (let pagina = 0; pagina < MAX_PAGINAS_REPORTE; pagina++) {
    const resultado = await c.db.visitas.listar({
      siteClienteId: filtro.siteClienteId,
      desde: filtro.desde,
      hasta: filtro.hasta,
      limite: TAM_PAGINA_REPORTE,
      ...(cursor ? { cursor } : {}),
    })
    acumulado.push(...resultado.items)
    if (!resultado.siguienteCursor) return { visitas: acumulado, truncado: false }
    cursor = resultado.siguienteCursor
  }

  // Se agotaron las páginas y todavía quedaba cursor: el recorrido está incompleto.
  return { visitas: acumulado, truncado: true }
}

export function construirReporte(
  clienteId: string,
  desde: Date,
  hasta: Date,
  visitas: Visita[],
  evidencias: Evidencia[],
) {
  const completadas = visitas.filter((v) => v.estado === 'completada').length
  const sospechosas = evidencias.filter((e) => e.esSospechosa)

  const conteoMotivos = new Map<MotivoAlerta, number>()
  for (const e of sospechosas) {
    for (const m of e.motivosAlerta) conteoMotivos.set(m, (conteoMotivos.get(m) ?? 0) + 1)
  }

  interface FilaSede {
    siteId: string
    nombreSede: string
    visitasAsignadas: number
    visitasCompletadas: number
    evidenciasSospechosas: number
  }

  const porSede = new Map<string, FilaSede>()
  for (const v of visitas) {
    const fila = porSede.get(v.siteId) ?? {
      siteId: v.siteId,
      nombreSede: v.siteNombreSede,
      visitasAsignadas: 0,
      visitasCompletadas: 0,
      evidenciasSospechosas: 0,
    }
    fila.visitasAsignadas += 1
    if (v.estado === 'completada') fila.visitasCompletadas += 1
    porSede.set(v.siteId, fila)
  }
  for (const e of sospechosas) {
    const fila = porSede.get(e.siteId)
    if (fila) fila.evidenciasSospechosas += 1
  }

  return {
    clienteId,
    nombreCliente: visitas[0]?.siteNombreCliente ?? null,
    desde: desde.toISOString(),
    hasta: hasta.toISOString(),
    generadoEn: new Date().toISOString(),
    resumen: {
      visitasAsignadas: visitas.length,
      visitasCompletadas: completadas,
      visitasPendientes: visitas.length - completadas,
      // Sin visitas asignadas el cumplimiento es 0, no NaN ni 100: un cliente sin
      // visitas programadas no está cumpliendo al 100 %.
      cumplimientoPorcentaje:
        visitas.length === 0 ? 0 : Math.round((completadas / visitas.length) * 1000) / 10,
      evidenciasTotales: evidencias.length,
      evidenciasSospechosas: sospechosas.length,
      motivosFrecuentes: [...conteoMotivos.entries()]
        .sort((a, b) => b[1] - a[1])
        .map(([motivo, conteo]) => ({ motivo, conteo })),
    },
    porSede: [...porSede.values()].sort((a, b) => a.nombreSede.localeCompare(b.nombreSede, 'es')),
  }
}
