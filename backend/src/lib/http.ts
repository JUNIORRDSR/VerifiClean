import type { Request } from 'express'
import { solicitudInvalida } from './errors'

/**
 * Lee un parámetro de ruta como texto.
 *
 * Los tipos de Express 5 declaran `req.params[k]` como `string | string[] | undefined`
 * porque un patrón con comodines puede producir varios segmentos. Ninguna ruta de esta
 * API los usa, pero el tipo obliga a decidir qué pasa si llega un array — y la
 * respuesta correcta es 400, no un `as string` que reventaría más abajo.
 */
export function paramTexto(req: Request, nombre: string): string {
  const valor = req.params[nombre]
  if (typeof valor !== 'string' || valor.length === 0) {
    throw solicitudInvalida(`Falta el parámetro de ruta \`${nombre}\`.`)
  }
  return valor
}
