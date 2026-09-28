import { GoogleAuth } from 'google-auth-library'
import type { ResultadoIntegridad } from '../domain/types'
import { logger } from '../lib/logger'
import { tokenEstaFresco } from '../lib/nonce'

/**
 * Play Integrity API.
 *
 * ## Qué contesta Google y cómo se traduce
 *
 * - `deviceIntegrity.deviceRecognitionVerdict`: lista de etiquetas.
 *   `MEETS_DEVICE_INTEGRITY` = dispositivo Android genuino, sin root, con bootloader
 *   bloqueado. **Lista vacía** = falló todas las comprobaciones, el caso típico de un
 *   teléfono rooteado con una app de mock GPS.
 * - `appIntegrity.appRecognitionVerdict`: `PLAY_RECOGNIZED` = APK sin modificar,
 *   instalado desde Play. Cualquier otra cosa significa APK reempaquetado, que es
 *   justo lo que se necesita para inyectar coordenadas falsas sin dejar rastro.
 *
 * Se exige `MEETS_DEVICE_INTEGRITY`, no `MEETS_BASIC_INTEGRITY`: la básica la pasa un
 * emulador, y un emulador es la forma más barata de fabricar visitas.
 *
 * ## Por qué no basta con leer el veredicto
 *
 * Un veredicto describe **el momento en que se pidió el token**, no el momento en que se
 * usa. Sin más comprobaciones, un token obtenido una vez desde un teléfono limpio sirve
 * para siempre: se guarda y se adjunta a cualquier subida posterior hecha desde un
 * cliente modificado. Por eso, además del veredicto, se verifica:
 *
 * 1. **Nonce** — que el token se pidió para *esta* visita, *esta* evidencia y *esta*
 *    foto (ver `lib/nonce.ts`).
 * 2. **Frescura** — que `timestampMillis` cae dentro de la ventana admitida.
 * 3. **Paquete** — que el token pertenece a nuestra app y no a otra instalada en el
 *    mismo dispositivo, a la que también se le puede pedir un token válido.
 */

const ENDPOINT = (paquete: string) =>
  `https://playintegrity.googleapis.com/v1/${encodeURIComponent(paquete)}:decodeIntegrityToken`

/** Resultado enriquecido: además del veredicto, qué falló de la ligadura del token. */
export interface EvaluacionIntegridad {
  resultado: ResultadoIntegridad
  /** El nonce del token no corresponde a esta evidencia. */
  nonceNoCoincide: boolean
  /** El token se emitió fuera de la ventana de validez. */
  caducado: boolean
}

export interface ContextoIntegridad {
  token: string | null | undefined
  /** Nonce que debería traer el token, calculado por el servidor. */
  nonceEsperado: string
}

export interface ServicioIntegridad {
  evaluar(contexto: ContextoIntegridad): Promise<EvaluacionIntegridad>
}

const noEvaluado = (motivo: string): EvaluacionIntegridad => ({
  resultado: {
    veredicto: 'NO_EVALUADO',
    deviceRecognition: [],
    appRecognition: null,
    motivo,
  },
  nonceNoCoincide: false,
  caducado: false,
})

interface RespuestaPlayIntegrity {
  tokenPayloadExternal?: {
    requestDetails?: {
      requestPackageName?: string
      timestampMillis?: string
      nonce?: string
      requestHash?: string
    }
    appIntegrity?: { appRecognitionVerdict?: string; packageName?: string }
    deviceIntegrity?: { deviceRecognitionVerdict?: string[] }
    accountDetails?: { appLicensingVerdict?: string }
  }
}

export class ServicioIntegridadPlay implements ServicioIntegridad {
  private readonly auth: GoogleAuth

  constructor(private readonly paqueteAndroid: string) {
    this.auth = new GoogleAuth({
      scopes: ['https://www.googleapis.com/auth/playintegrity'],
    })
  }

  async evaluar(contexto: ContextoIntegridad): Promise<EvaluacionIntegridad> {
    if (!contexto.token) return noEvaluado('El cliente no envió token de Play Integrity.')

    let payload: NonNullable<RespuestaPlayIntegrity['tokenPayloadExternal']>
    try {
      const cliente = await this.auth.getClient()
      const cabeceras = await cliente.getRequestHeaders()
      const respuesta = await fetch(ENDPOINT(this.paqueteAndroid), {
        method: 'POST',
        headers: {
          ...Object.fromEntries(new Headers(cabeceras)),
          'content-type': 'application/json',
        },
        body: JSON.stringify({ integrityToken: contexto.token }),
      })

      if (!respuesta.ok) {
        const cuerpo = await respuesta.text()
        logger.warn('Play Integrity respondió error', { status: respuesta.status, cuerpo })
        return noEvaluado(`Play Integrity devolvió HTTP ${respuesta.status}.`)
      }

      const datos = (await respuesta.json()) as RespuestaPlayIntegrity
      if (!datos.tokenPayloadExternal) {
        return noEvaluado('Play Integrity devolvió un payload vacío.')
      }
      payload = datos.tokenPayloadExternal
    } catch (e) {
      // Un fallo de red contra Google no debe tumbar la subida: se marca como no evaluada
      // y el admin lo ve. Perder la foto sería mucho peor.
      logger.error('Fallo al consultar Play Integrity', { error: String(e) })
      return noEvaluado('No se pudo contactar a Play Integrity.')
    }

    return interpretarPayload(payload, contexto.nonceEsperado, this.paqueteAndroid)
  }
}

/**
 * Traduce el payload ya descifrado a un veredicto. Separado de la llamada HTTP para poder
 * testear cada combinación de veredicto, nonce y frescura sin red.
 */
export function interpretarPayload(
  payload: NonNullable<RespuestaPlayIntegrity['tokenPayloadExternal']>,
  nonceEsperado: string,
  paqueteAndroid: string,
): EvaluacionIntegridad {
  const deviceRecognition = payload.deviceIntegrity?.deviceRecognitionVerdict ?? []
  const appRecognition = payload.appIntegrity?.appRecognitionVerdict ?? null

  // Un token válido de OTRA app sigue siendo un token válido: sin esta comprobación, un
  // atacante le pide un token a cualquier app instalada y lo reenvía aquí. Se exige que
  // el paquete venga y que sea el nuestro — ausente se trata como fallo, no como permiso.
  const paqueteDelToken =
    payload.requestDetails?.requestPackageName ?? payload.appIntegrity?.packageName
  if (paqueteDelToken !== paqueteAndroid) {
    return {
      resultado: {
        veredicto: 'NO_CONFIABLE',
        deviceRecognition,
        appRecognition,
        motivo: paqueteDelToken
          ? `El token pertenece al paquete ${paqueteDelToken}, no a ${paqueteAndroid}.`
          : 'El token no declara paquete de origen.',
      },
      nonceNoCoincide: false,
      caducado: false,
    }
  }

  // Google acepta el valor en `nonce` o, con la API de clásica/estándar, en `requestHash`.
  const nonceDelToken = payload.requestDetails?.nonce ?? payload.requestDetails?.requestHash ?? null
  const nonceNoCoincide = nonceDelToken !== nonceEsperado

  const timestamp = Number(payload.requestDetails?.timestampMillis ?? 0)
  const caducado = !tokenEstaFresco(timestamp)

  const dispositivoGenuino = deviceRecognition.includes('MEETS_DEVICE_INTEGRITY')
  const appGenuina = appRecognition === 'PLAY_RECOGNIZED'

  if (dispositivoGenuino && appGenuina) {
    return {
      // El veredicto del dispositivo sigue siendo CONFIABLE aunque el nonce no cuadre:
      // son dos hechos distintos y el admin merece verlos por separado. Que el token esté
      // desligado se reporta con su propio motivo de alerta.
      resultado: { veredicto: 'CONFIABLE', deviceRecognition, appRecognition, motivo: null },
      nonceNoCoincide,
      caducado,
    }
  }

  const fallas: string[] = []
  if (!dispositivoGenuino) {
    fallas.push(
      deviceRecognition.length === 0
        ? 'el dispositivo no superó ninguna comprobación de integridad (root, bootloader abierto o emulador)'
        : `el dispositivo sólo reporta ${deviceRecognition.join(', ')}`,
    )
  }
  if (!appGenuina) fallas.push(`la app reporta ${appRecognition ?? 'sin veredicto'}`)

  return {
    resultado: {
      veredicto: 'NO_CONFIABLE',
      deviceRecognition,
      appRecognition,
      motivo: fallas.join('; '),
    },
    nonceNoCoincide,
    caducado,
  }
}

/**
 * Modo desarrollo. El token decide el veredicto para poder probar cada rama sin un
 * dispositivo rooteado a mano:
 *
 *   `fake:confiable`     → CONFIABLE con el nonce correcto
 *   `fake:no_confiable`  → NO_CONFIABLE
 *   `fake:no_evaluado`   → NO_EVALUADO
 *   `fake:nonce_malo`    → CONFIABLE pero con el nonce de otra evidencia
 *   `fake:caducado`      → CONFIABLE pero emitido fuera de la ventana
 *   (sin token)          → NO_EVALUADO
 *   cualquier otra cosa  → CONFIABLE
 */
export class ServicioIntegridadFalso implements ServicioIntegridad {
  async evaluar(contexto: ContextoIntegridad): Promise<EvaluacionIntegridad> {
    if (!contexto.token) return noEvaluado('El cliente no envió token de Play Integrity.')

    if (contexto.token === 'fake:no_confiable') {
      return {
        resultado: {
          veredicto: 'NO_CONFIABLE',
          deviceRecognition: [],
          appRecognition: 'UNRECOGNIZED_VERSION',
          motivo: 'Veredicto simulado: dispositivo comprometido.',
        },
        nonceNoCoincide: false,
        caducado: false,
      }
    }
    if (contexto.token === 'fake:no_evaluado') {
      return noEvaluado('Veredicto simulado: no evaluado.')
    }

    const confiable: ResultadoIntegridad = {
      veredicto: 'CONFIABLE',
      deviceRecognition: ['MEETS_DEVICE_INTEGRITY', 'MEETS_STRONG_INTEGRITY'],
      appRecognition: 'PLAY_RECOGNIZED',
      motivo: null,
    }

    return {
      resultado: confiable,
      nonceNoCoincide: contexto.token === 'fake:nonce_malo',
      caducado: contexto.token === 'fake:caducado',
    }
  }
}
