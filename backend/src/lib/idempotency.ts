import { createHash } from 'node:crypto'

/** JSON canónico para que el orden de las propiedades no cambie la identidad del pedido. */
function jsonCanonico(valor: unknown): string {
  if (valor instanceof Date) return JSON.stringify(valor.toISOString())
  if (Array.isArray(valor)) return `[${valor.map(jsonCanonico).join(',')}]`
  if (valor !== null && typeof valor === 'object') {
    const entradas = Object.entries(valor as Record<string, unknown>)
      .filter(([, contenido]) => contenido !== undefined)
      .sort(([a], [b]) => a.localeCompare(b))
    return `{${entradas.map(([clave, contenido]) => `${JSON.stringify(clave)}:${jsonCanonico(contenido)}`).join(',')}}`
  }
  return JSON.stringify(valor)
}

/** Huella opaca de la carga ya validada y normalizada por Zod. */
export function huellaIdempotencia(carga: unknown): string {
  return createHash('sha256').update(jsonCanonico(carga)).digest('hex')
}
