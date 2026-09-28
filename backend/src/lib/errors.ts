/**
 * Errores de aplicación. Un solo tipo con `status` + `codigo`; el middleware de
 * error lo traduce al envelope de `openapi.yaml`. Cualquier error que no sea
 * `AppError` se reporta como ERROR_INTERNO sin filtrar el mensaje original.
 */

export type CodigoError =
  | 'SOLICITUD_INVALIDA'
  | 'NO_AUTORIZADO'
  | 'PROHIBIDO'
  | 'NO_ENCONTRADO'
  | 'CONFLICTO'
  | 'ARCHIVO_MUY_GRANDE'
  | 'ERROR_INTERNO'

const STATUS_POR_CODIGO: Record<CodigoError, number> = {
  SOLICITUD_INVALIDA: 400,
  NO_AUTORIZADO: 401,
  PROHIBIDO: 403,
  NO_ENCONTRADO: 404,
  CONFLICTO: 409,
  ARCHIVO_MUY_GRANDE: 413,
  ERROR_INTERNO: 500,
}

export class AppError extends Error {
  readonly codigo: CodigoError
  readonly status: number
  readonly detalles: Record<string, unknown> | null

  constructor(
    codigo: CodigoError,
    mensaje: string,
    detalles: Record<string, unknown> | null = null,
  ) {
    super(mensaje)
    this.name = 'AppError'
    this.codigo = codigo
    this.status = STATUS_POR_CODIGO[codigo]
    this.detalles = detalles
  }
}

export const solicitudInvalida = (m: string, d?: Record<string, unknown>) =>
  new AppError('SOLICITUD_INVALIDA', m, d ?? null)
export const noAutorizado = (m = 'Token ausente, inválido o expirado.') =>
  new AppError('NO_AUTORIZADO', m)
export const prohibido = (m = 'No tienes permiso sobre este recurso.') =>
  new AppError('PROHIBIDO', m)
export const noEncontrado = (m = 'Recurso no encontrado.') =>
  new AppError('NO_ENCONTRADO', m)
export const conflicto = (m: string, d?: Record<string, unknown>) =>
  new AppError('CONFLICTO', m, d ?? null)
