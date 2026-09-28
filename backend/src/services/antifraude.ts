import type { MotivoAlerta, ResultadoIntegridad } from '../domain/types'

/**
 * Reglas antifraude. Función pura, sin IO: recibe hechos ya medidos y devuelve los
 * motivos de alerta. Está separada del servicio que hace IO precisamente para poder
 * testear cada regla y cada combinación sin montar Firestore, Storage ni Play Integrity.
 *
 * Principio de diseño: **marcar, nunca rechazar**. Una evidencia sospechosa se guarda
 * igual y queda visible para el admin. Devolverle un 4xx al cliente convertiría la API
 * en un oráculo: el defraudador ajusta un parámetro, reintenta, y repite hasta que
 * pasa. Marcando en silencio, el fraude queda registrado con su evidencia encima.
 */

export interface HechosEvidencia {
  /**
   * Haversine entre la foto y la sede, en metros. `null` si el dispositivo no consiguió
   * ubicación.
   *
   * Es `null` y no `0,0` a propósito. La app mandaba 0,0 cuando el GPS fallaba, y el
   * panel del admin mostraba entonces "8.241 km" —la distancia del golfo de Guinea a
   * Bogotá— presentada como si fuera una medición. Un dato inventado que parece prueba es
   * peor que ningún dato: alguien podría abrir un expediente disciplinario con eso.
   */
  distanciaMetros: number | null
  /** Radio de la geocerca vigente cuando se asignó la visita. */
  radioPermitidoMetros: number
  /** `Location.accuracy` reportado por Android. `null` si no vino. */
  precisionMetros: number | null
  /** `Location.isMock`. */
  mockLocationReportada: boolean
  integridad: ResultadoIntegridad
  /** Ya existe otra evidencia, en otra visita, con el mismo SHA-256. */
  hayDuplicadoDeHash: boolean
  /** Los bytes recibidos coinciden con el hash que declaró el cliente. */
  hashCoincideConDeclarado: boolean
  /**
   * El `X-Device-Id` de la petición coincide con el registrado para el supervisor.
   * `true` también cuando no hay con qué comparar (primer dispositivo, o el cliente
   * no mandó el header): no se alerta sobre lo que no se sabe.
   */
  dispositivoVinculado: boolean

  /**
   * El nonce del token de Play Integrity no corresponde a esta visita, esta evidencia y
   * esta foto: el token se pidió para otra cosa y se está reutilizando aquí.
   */
  tokenIntegridadReutilizado: boolean

  /** El token de Play Integrity se emitió fuera de la ventana de validez. */
  tokenIntegridadCaducado: boolean

  /** El checklist enviado no corresponde al asignado a la visita. */
  checklistAlterado: boolean
}

/**
 * Cuánto margen se le concede al error del GPS antes de declarar "fuera de geocerca".
 *
 * Sin margen, un supervisor parado en la puerta con 60 m de precisión y un radio de
 * 50 m se marca como fraude en cada visita — y una alerta que salta siempre es una
 * alerta que nadie mira. Con margen, sólo se acusa cuando la persona está fuera
 * *incluso asumiendo el error del sensor a su favor*.
 *
 * El margen se topa en el propio radio para que el sensor no se vuelva la palanca:
 * un cliente modificado que reporte `accuracy = 50 km` consigue, como máximo,
 * duplicar el radio — y de paso dispara PRECISION_GPS_BAJA, que el admin sí ve.
 */
export function margenPorPrecision(
  precisionMetros: number | null,
  radioPermitidoMetros: number,
): number {
  if (precisionMetros === null || !Number.isFinite(precisionMetros) || precisionMetros <= 0) return 0
  return Math.min(precisionMetros, radioPermitidoMetros)
}

export function evaluarAlertas(hechos: HechosEvidencia): MotivoAlerta[] {
  const motivos: MotivoAlerta[] = []

  if (hechos.distanciaMetros === null) {
    // Sin ubicación no se puede afirmar ni desmentir la presencia. Se marca para que el
    // admin lo sepa, pero **no** como si estuviera fuera: acusar de estar lejos a quien
    // simplemente no tuvo señal GPS es un falso positivo con consecuencias laborales.
    motivos.push('SIN_UBICACION')
  } else {
    const margen = margenPorPrecision(hechos.precisionMetros, hechos.radioPermitidoMetros)
    if (hechos.distanciaMetros > hechos.radioPermitidoMetros + margen) {
      motivos.push('FUERA_DE_GEOFENCE')
    }

    // Precisión peor que el radio: el punto medido no distingue "dentro" de "fuera".
    // Es información distinta de estar fuera, y se reporta aparte.
    if (
      hechos.precisionMetros !== null &&
      Number.isFinite(hechos.precisionMetros) &&
      hechos.precisionMetros > hechos.radioPermitidoMetros
    ) {
      motivos.push('PRECISION_GPS_BAJA')
    }
  }

  if (hechos.hayDuplicadoDeHash) motivos.push('IMAGEN_DUPLICADA')

  if (hechos.mockLocationReportada) motivos.push('MOCK_LOCATION')

  if (hechos.integridad.veredicto === 'NO_CONFIABLE') {
    motivos.push('DISPOSITIVO_NO_CONFIABLE')
  } else if (hechos.integridad.veredicto === 'NO_EVALUADO') {
    // Se marca a propósito. Si "sin token" pasara limpio, el fraude más barato sería
    // bloquear la llamada a Play Integrity y subir desde un teléfono rooteado.
    motivos.push('INTEGRIDAD_NO_EVALUADA')
  }

  // Un nonce que no cuadra pesa más que el propio veredicto: significa que el token
  // describe un momento distinto al de esta foto. Sólo tiene sentido reportarlo cuando
  // hubo token que evaluar; sin token ya salta INTEGRIDAD_NO_EVALUADA.
  if (hechos.integridad.veredicto !== 'NO_EVALUADO') {
    if (hechos.tokenIntegridadReutilizado) motivos.push('TOKEN_INTEGRIDAD_REUTILIZADO')
    if (hechos.tokenIntegridadCaducado) motivos.push('TOKEN_INTEGRIDAD_CADUCADO')
  }

  if (!hechos.hashCoincideConDeclarado) motivos.push('HASH_NO_COINCIDE')

  if (!hechos.dispositivoVinculado) motivos.push('DISPOSITIVO_NO_VINCULADO')

  if (hechos.checklistAlterado) motivos.push('CHECKLIST_ALTERADO')

  return motivos
}

/**
 * Comprueba que el checklist que llega es el de la visita.
 *
 * Sin esto, un cliente modificado manda el mismo número de ítems pero con otros `id` y
 * otras preguntas, todas respondidas — y la visita se cierra guardando un checklist
 * inventado. Se pierde justo lo que el checklist debía probar: que el supervisor
 * respondió *esas* preguntas.
 *
 * Se compara el conjunto de `id`, no el orden ni el texto: reordenar en la UI es
 * legítimo, y el texto de la pregunta lo reconstruye el servidor desde la visita.
 */
export function checklistCoincide(
  enviado: readonly { id: string }[],
  asignado: readonly { id: string }[],
): boolean {
  if (enviado.length !== asignado.length) return false

  const idsEnviados = new Set(enviado.map((i) => i.id))
  // Un `id` repetido colapsa en el Set y delataría un checklist fabricado que "cuadra"
  // en número pero no en contenido.
  if (idsEnviados.size !== enviado.length) return false

  return asignado.every((i) => idsEnviados.has(i.id))
}

/** Cabecera JPEG (SOI + marcador). Evita almacenar cualquier blob como si fuera foto. */
export function esJpeg(bytes: Buffer): boolean {
  return bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff
}
