import type { ErrorRequestHandler, RequestHandler } from 'express'
import { MulterError } from 'multer'
import { ZodError } from 'zod'
import { AppError } from '../lib/errors'
import { logger } from '../lib/logger'

/** Ruta no montada. Va al final del router, antes del manejador de errores. */
export const noEncontradoHandler: RequestHandler = (_req, res) => {
  res.status(404).json({
    error: { codigo: 'NO_ENCONTRADO', mensaje: 'Ruta no encontrada.', detalles: null },
  })
}

/**
 * Traductor único de errores al envelope de `openapi.yaml`.
 *
 * Regla: sólo `AppError`, `ZodError` y `MulterError` producen mensajes propios.
 * Cualquier otra cosa sale como ERROR_INTERNO con un mensaje fijo — un stack trace o
 * un mensaje de driver en la respuesta le regala al atacante el mapa de la casa. El
 * detalle real se registra en el log del servidor con el `requestId` para poder
 * correlacionarlo.
 */
export const manejadorErrores: ErrorRequestHandler = (err, req, res, _next) => {
  const requestId = res.getHeader('x-request-id')

  if (err instanceof AppError) {
    if (err.status >= 500) {
      logger.error(err.message, { requestId, codigo: err.codigo, ruta: req.originalUrl })
    }
    res.status(err.status).json({
      error: { codigo: err.codigo, mensaje: err.message, detalles: err.detalles },
    })
    return
  }

  if (err instanceof ZodError) {
    const detalles: Record<string, string> = {}
    for (const issue of err.issues) {
      detalles[issue.path.join('.') || '_'] = issue.message
    }
    res.status(400).json({
      error: {
        codigo: 'SOLICITUD_INVALIDA',
        mensaje: 'Hay campos inválidos en la petición.',
        detalles,
      },
    })
    return
  }

  if (err instanceof MulterError) {
    const esTamano = err.code === 'LIMIT_FILE_SIZE'
    res.status(esTamano ? 413 : 400).json({
      error: {
        codigo: esTamano ? 'ARCHIVO_MUY_GRANDE' : 'SOLICITUD_INVALIDA',
        mensaje: esTamano
          ? 'La foto supera el tamaño máximo permitido.'
          : `Error al procesar el archivo: ${err.code}`,
        detalles: null,
      },
    })
    return
  }

  logger.error('Error no controlado', {
    requestId,
    ruta: req.originalUrl,
    metodo: req.method,
    error: err instanceof Error ? err.message : String(err),
    stack: err instanceof Error ? err.stack : undefined,
  })
  res.status(500).json({
    error: { codigo: 'ERROR_INTERNO', mensaje: 'Error interno del servidor.', detalles: null },
  })
}
