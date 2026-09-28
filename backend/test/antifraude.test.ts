import { describe, expect, it } from 'vitest'
import type { ResultadoIntegridad } from '../src/domain/types'
import { esJpeg, evaluarAlertas, margenPorPrecision, type HechosEvidencia } from '../src/services/antifraude'
import { hashCoincide, sha256Hex } from '../src/lib/hash'

const INTEGRIDAD_OK: ResultadoIntegridad = {
  veredicto: 'CONFIABLE',
  deviceRecognition: ['MEETS_DEVICE_INTEGRITY'],
  appRecognition: 'PLAY_RECOGNIZED',
  motivo: null,
}

/** Caso limpio: dentro del radio, dispositivo genuino, imagen nueva. */
const hechosLimpios = (extra: Partial<HechosEvidencia> = {}): HechosEvidencia => ({
  distanciaMetros: 20,
  radioPermitidoMetros: 150,
  precisionMetros: 15,
  mockLocationReportada: false,
  integridad: INTEGRIDAD_OK,
  hayDuplicadoDeHash: false,
  hashCoincideConDeclarado: true,
  dispositivoVinculado: true,
  ...extra,
})

describe('evaluarAlertas — caso limpio', () => {
  it('no levanta ninguna alerta', () => {
    expect(evaluarAlertas(hechosLimpios())).toEqual([])
  })
})

describe('evaluarAlertas — sin ubicación', () => {
  it('marca SIN_UBICACION, nunca FUERA_DE_GEOFENCE', () => {
    // La diferencia importa: acusar de estar lejos a quien simplemente no tuvo señal es un
    // falso positivo con consecuencias laborales. Antes la app mandaba 0,0 y el servidor
    // calculaba 8.241 km, que el panel mostraba como si fuera una medición real.
    const motivos = evaluarAlertas(hechosLimpios({ distanciaMetros: null, precisionMetros: null }))

    expect(motivos).toContain('SIN_UBICACION')
    expect(motivos).not.toContain('FUERA_DE_GEOFENCE')
  })

  it('sin ubicación no se reporta precisión baja: no hay medición que calificar', () => {
    const motivos = evaluarAlertas(
      hechosLimpios({ distanciaMetros: null, precisionMetros: 5_000 }),
    )
    expect(motivos).not.toContain('PRECISION_GPS_BAJA')
  })

  it('sin ubicación siguen aplicando el resto de reglas', () => {
    const motivos = evaluarAlertas(
      hechosLimpios({ distanciaMetros: null, hayDuplicadoDeHash: true, mockLocationReportada: true }),
    )
    expect(motivos).toEqual(
      expect.arrayContaining(['SIN_UBICACION', 'IMAGEN_DUPLICADA', 'MOCK_LOCATION']),
    )
  })
})

describe('evaluarAlertas — geocerca', () => {
  it('no alerta justo en el borde del radio', () => {
    const motivos = evaluarAlertas(
      hechosLimpios({ distanciaMetros: 150, radioPermitidoMetros: 150, precisionMetros: null }),
    )
    expect(motivos).toEqual([])
  })

  it('alerta cuando la distancia supera el radio sin margen de precisión', () => {
    const motivos = evaluarAlertas(
      hechosLimpios({ distanciaMetros: 151, radioPermitidoMetros: 150, precisionMetros: null }),
    )
    expect(motivos).toEqual(['FUERA_DE_GEOFENCE'])
  })

  it('perdona el error del GPS: 180 m medidos con 50 m de precisión y radio 150 no es fraude', () => {
    const motivos = evaluarAlertas(
      hechosLimpios({ distanciaMetros: 180, radioPermitidoMetros: 150, precisionMetros: 50 }),
    )
    expect(motivos).toEqual([])
  })

  it('sigue alertando cuando ni el margen de precisión alcanza', () => {
    const motivos = evaluarAlertas(
      hechosLimpios({ distanciaMetros: 260, radioPermitidoMetros: 150, precisionMetros: 50 }),
    )
    expect(motivos).toContain('FUERA_DE_GEOFENCE')
  })

  it('no deja que una precisión absurda se use como palanca para ampliar el radio', () => {
    // Un cliente modificado declara 50 km de precisión para "justificar" estar a 5 km.
    // El margen se topa en el radio, así que la alerta salta igual.
    const motivos = evaluarAlertas(
      hechosLimpios({ distanciaMetros: 5_000, radioPermitidoMetros: 150, precisionMetros: 50_000 }),
    )
    expect(motivos).toContain('FUERA_DE_GEOFENCE')
    expect(motivos).toContain('PRECISION_GPS_BAJA')
  })

  it('marca precisión baja como señal aparte de estar fuera', () => {
    // Dentro del radio pero con el sensor incapaz de afirmarlo.
    const motivos = evaluarAlertas(
      hechosLimpios({ distanciaMetros: 10, radioPermitidoMetros: 80, precisionMetros: 200 }),
    )
    expect(motivos).toEqual(['PRECISION_GPS_BAJA'])
  })
})

describe('margenPorPrecision', () => {
  it('no concede margen sin dato de precisión', () => {
    expect(margenPorPrecision(null, 150)).toBe(0)
  })

  it('ignora valores no finitos o negativos que llegarían de un cliente modificado', () => {
    expect(margenPorPrecision(Number.NaN, 150)).toBe(0)
    expect(margenPorPrecision(-5, 150)).toBe(0)
  })

  it('topa el margen en el radio permitido', () => {
    expect(margenPorPrecision(10_000, 150)).toBe(150)
    expect(margenPorPrecision(40, 150)).toBe(40)
  })
})

describe('evaluarAlertas — integridad del dispositivo', () => {
  it('marca dispositivo no confiable', () => {
    const motivos = evaluarAlertas(
      hechosLimpios({
        integridad: {
          veredicto: 'NO_CONFIABLE',
          deviceRecognition: [],
          appRecognition: 'UNRECOGNIZED_VERSION',
          motivo: 'root',
        },
      }),
    )
    expect(motivos).toEqual(['DISPOSITIVO_NO_CONFIABLE'])
  })

  it('marca también cuando no hubo veredicto — bloquear Play Integrity no puede salir gratis', () => {
    const motivos = evaluarAlertas(
      hechosLimpios({
        integridad: {
          veredicto: 'NO_EVALUADO',
          deviceRecognition: [],
          appRecognition: null,
          motivo: 'sin token',
        },
      }),
    )
    expect(motivos).toEqual(['INTEGRIDAD_NO_EVALUADA'])
  })
})

describe('evaluarAlertas — resto de reglas', () => {
  it('marca imagen duplicada', () => {
    expect(evaluarAlertas(hechosLimpios({ hayDuplicadoDeHash: true }))).toEqual(['IMAGEN_DUPLICADA'])
  })

  it('marca ubicación simulada', () => {
    expect(evaluarAlertas(hechosLimpios({ mockLocationReportada: true }))).toEqual(['MOCK_LOCATION'])
  })

  it('marca hash que no coincide con el declarado', () => {
    expect(evaluarAlertas(hechosLimpios({ hashCoincideConDeclarado: false }))).toEqual([
      'HASH_NO_COINCIDE',
    ])
  })

  it('marca dispositivo distinto al vinculado', () => {
    expect(evaluarAlertas(hechosLimpios({ dispositivoVinculado: false }))).toEqual([
      'DISPOSITIVO_NO_VINCULADO',
    ])
  })

  it('acumula todos los motivos que apliquen, no sólo el primero', () => {
    const motivos = evaluarAlertas(
      hechosLimpios({
        distanciaMetros: 5_000,
        precisionMetros: null,
        mockLocationReportada: true,
        hayDuplicadoDeHash: true,
        integridad: {
          veredicto: 'NO_CONFIABLE',
          deviceRecognition: [],
          appRecognition: null,
          motivo: 'root',
        },
      }),
    )
    expect(motivos).toEqual(
      expect.arrayContaining([
        'FUERA_DE_GEOFENCE',
        'IMAGEN_DUPLICADA',
        'MOCK_LOCATION',
        'DISPOSITIVO_NO_CONFIABLE',
      ]),
    )
    expect(motivos).toHaveLength(4)
  })
})

describe('hash', () => {
  it('produce SHA-256 en hex de 64 caracteres', () => {
    const h = sha256Hex(Buffer.from('foto'))
    expect(h).toMatch(/^[a-f0-9]{64}$/)
  })

  it('cambia por completo con un solo byte distinto', () => {
    expect(sha256Hex(Buffer.from('foto-a'))).not.toBe(sha256Hex(Buffer.from('foto-b')))
  })

  it('acepta cuando el cliente no declara hash — no hay nada que contradecir', () => {
    expect(hashCoincide(sha256Hex(Buffer.from('x')), null)).toBe(true)
    expect(hashCoincide(sha256Hex(Buffer.from('x')), undefined)).toBe(true)
  })

  it('compara sin distinguir mayúsculas', () => {
    const h = sha256Hex(Buffer.from('x'))
    expect(hashCoincide(h, h.toUpperCase())).toBe(true)
  })

  it('devuelve false ante basura en vez de lanzar', () => {
    const h = sha256Hex(Buffer.from('x'))
    expect(hashCoincide(h, 'no-es-un-hash')).toBe(false)
    expect(hashCoincide(h, 'zz'.repeat(32))).toBe(false)
  })
})

describe('esJpeg', () => {
  it('acepta una cabecera JPEG real', () => {
    expect(esJpeg(Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00]))).toBe(true)
  })

  it('rechaza PNG, texto y buffers vacíos', () => {
    expect(esJpeg(Buffer.from([0x89, 0x50, 0x4e, 0x47]))).toBe(false)
    expect(esJpeg(Buffer.from('no soy una foto'))).toBe(false)
    expect(esJpeg(Buffer.alloc(0))).toBe(false)
  })
})
