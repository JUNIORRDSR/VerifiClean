import { randomUUID } from 'node:crypto'
import type { RequestHandler } from 'express'
import { logger } from '../lib/logger'

/**
 * Correlación de peticiones. Reutiliza el `X-Cloud-Trace-Context` que inyecta el
 * balanceador de Cloud Run, para que un log del servicio se pueda cruzar con el
 * registro de acceso de la plataforma sin tener que adivinar por marca de tiempo.
 */
export const requestId: RequestHandler = (req, res, next) => {
  const trace = req.header('x-cloud-trace-context')?.split('/')[0]
  const id = req.header('x-request-id') ?? trace ?? randomUUID()
  res.setHeader('x-request-id', id)

  const inicio = process.hrtime.bigint()
  res.on('finish', () => {
    const ms = Number(process.hrtime.bigint() - inicio) / 1e6
    logger.info('peticion', {
      requestId: id,
      metodo: req.method,
      ruta: req.originalUrl,
      status: res.statusCode,
      duracionMs: Math.round(ms),
    })
  })

  next()
}
