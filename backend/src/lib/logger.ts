/**
 * Log estructurado para Cloud Run.
 *
 * Cloud Logging parsea automáticamente cualquier línea de stdout que sea JSON y
 * respeta el campo `severity`. Eso es todo lo que necesita este servicio, así que
 * no entra pino ni winston: son 20 líneas y una dependencia menos que auditar y
 * que cargar en el arranque en frío.
 */

type Severidad = 'DEBUG' | 'INFO' | 'WARNING' | 'ERROR'

// En tests el log de acceso ahogaría la salida de vitest. Los errores sí se emiten:
// un test que falla por una excepción inesperada tiene que poder explicarse.
const silenciado = (severity: Severidad): boolean =>
  process.env.NODE_ENV === 'test' && severity !== 'ERROR'

const emitir = (severity: Severidad, message: string, extra?: Record<string, unknown>) => {
  if (silenciado(severity)) return
  const linea = JSON.stringify({ severity, message, ...extra })
  if (severity === 'ERROR' || severity === 'WARNING') process.stderr.write(linea + '\n')
  else process.stdout.write(linea + '\n')
}

export const logger = {
  debug: (m: string, e?: Record<string, unknown>) => {
    if (process.env.LOG_LEVEL === 'debug') emitir('DEBUG', m, e)
  },
  info: (m: string, e?: Record<string, unknown>) => emitir('INFO', m, e),
  warn: (m: string, e?: Record<string, unknown>) => emitir('WARNING', m, e),
  error: (m: string, e?: Record<string, unknown>) => emitir('ERROR', m, e),
}
