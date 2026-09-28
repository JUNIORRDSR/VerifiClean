import { createHash } from 'node:crypto'

/**
 * Nonce que liga un token de Play Integrity a **esta** evidencia concreta.
 *
 * Sin esta ligadura, un token es un pase reutilizable: se obtiene una vez desde un
 * teléfono genuino y sin root, y luego se adjunta a cualquier subida posterior desde un
 * cliente modificado. El veredicto seguiría diciendo `MEETS_DEVICE_INTEGRITY` y
 * `PLAY_RECOGNIZED`, porque describe el momento en que se pidió, no el momento en que se
 * usa.
 *
 * El nonce se calcula sobre tres cosas que sólo pueden ser las de esta evidencia:
 *
 * - `visitId` — a qué visita pertenece.
 * - `clienteEvidenciaId` — el UUID irrepetible que además es clave de idempotencia.
 * - `hashSHA256` — el hash de los bytes que el **servidor** recibió, no el que declaró
 *   el cliente. Así el token queda atado a esta foto exacta.
 *
 * Reproducir el nonce exige tener los tres datos antes de pedir el token, que es justo lo
 * que un cliente honesto tiene y un reproductor de tokens no. Y como el
 * `clienteEvidenciaId` sólo puede registrarse una vez (idempotencia), el mismo token no
 * sirve dos veces: no hace falta llevar un registro de nonces usados.
 */
export function calcularNonceEsperado(params: {
  visitId: string
  clienteEvidenciaId: string
  hashSHA256: string
}): string {
  const material = `${params.visitId}:${params.clienteEvidenciaId}:${params.hashSHA256}`
  // base64url sin relleno: es el formato que acepta `IntegrityTokenRequest.setNonce`.
  return createHash('sha256').update(material).digest('base64url')
}

/**
 * Ventana de validez del token, en milisegundos.
 *
 * Play Integrity sella cada token con `requestDetails.timestampMillis`. Diez minutos dan
 * margen de sobra para capturar la foto, cifrarla y subirla — incluso con mala señal —
 * y a la vez acotan cuánto vale un token capturado.
 *
 * Ojo: esto **no** limita cuánto puede tardar una evidencia en sincronizarse. El token se
 * pide en el momento de capturar, y si la subida espera horas en la cola offline, el
 * token llegará caducado y la evidencia se marcará `TOKEN_INTEGRIDAD_CADUCADO` sin
 * perderse. Es información para el admin, no un rechazo.
 */
export const VENTANA_TOKEN_MS = 10 * 60 * 1000

export function tokenEstaFresco(timestampMillis: number, ahora: number = Date.now()): boolean {
  if (!Number.isFinite(timestampMillis) || timestampMillis <= 0) return false
  const diferencia = ahora - timestampMillis
  // Se admite un pequeño adelanto para absorber desfases de reloj entre Google y nosotros,
  // pero no un token del futuro lejano, que sería señal de manipulación.
  return diferencia >= -VENTANA_TOKEN_MS && diferencia <= VENTANA_TOKEN_MS
}
