import { createHash, timingSafeEqual } from 'node:crypto'

/** SHA-256 en hex minúscula de un buffer. */
export function sha256Hex(datos: Buffer): string {
  return createHash('sha256').update(datos).digest('hex')
}

/**
 * Compara el hash real con el que declaró el cliente.
 *
 * Se usa `timingSafeEqual` no porque un hash de imagen sea un secreto, sino porque
 * es la comparación correcta para digests y no cuesta nada. Devuelve `false` ante
 * cualquier entrada mal formada en vez de lanzar: un cliente modificado puede mandar
 * basura en ese campo y eso es un motivo de alerta, no un 500.
 */
export function hashCoincide(real: string, reportado: string | null | undefined): boolean {
  if (!reportado) return true // no lo declaró: no hay nada que contradecir
  if (reportado.length !== real.length) return false
  try {
    return timingSafeEqual(Buffer.from(real, 'hex'), Buffer.from(reportado.toLowerCase(), 'hex'))
  } catch {
    return false
  }
}
