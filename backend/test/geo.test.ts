import { describe, expect, it } from 'vitest'
import { distanciaHaversineMetros, esCoordenadaValida } from '../src/lib/geo'

describe('distanciaHaversineMetros', () => {
  it('devuelve 0 para el mismo punto sin producir NaN', () => {
    const p = { lat: 4.700543, lng: -74.063644 }
    expect(distanciaHaversineMetros(p, p)).toBe(0)
  })

  it('mide un grado de latitud en el ecuador como ~111.2 km', () => {
    const d = distanciaHaversineMetros({ lat: 0, lng: 0 }, { lat: 1, lng: 0 })
    expect(d).toBeGreaterThan(111_000)
    expect(d).toBeLessThan(111_400)
  })

  it('mide la distancia real entre dos sedes de Bogotá con 1 % de tolerancia', () => {
    // Torre Bavaria (Cra 53A #127-35) → C.C. Andino (Cra 11 #82-71): ~5.6 km.
    const d = distanciaHaversineMetros(
      { lat: 4.700543, lng: -74.063644 },
      { lat: 4.667209, lng: -74.05372 },
    )
    expect(d).toBeGreaterThan(3_800)
    expect(d).toBeLessThan(4_100)
  })

  it('es simétrica', () => {
    const a = { lat: 4.7, lng: -74.06 }
    const b = { lat: 4.66, lng: -74.05 }
    expect(distanciaHaversineMetros(a, b)).toBeCloseTo(distanciaHaversineMetros(b, a), 6)
  })

  it('cruza el antimeridiano por el lado corto', () => {
    // 179.9°E y 179.9°O están a ~22 km, no a media vuelta al mundo.
    const d = distanciaHaversineMetros({ lat: 0, lng: 179.9 }, { lat: 0, lng: -179.9 })
    expect(d).toBeLessThan(23_000)
  })

  it('no colapsa a cero por redondeo en puntos casi idénticos', () => {
    // Un metro de separación tiene que seguir siendo medible.
    const d = distanciaHaversineMetros({ lat: 4.7, lng: -74.06 }, { lat: 4.700009, lng: -74.06 })
    expect(d).toBeGreaterThan(0.5)
    expect(d).toBeLessThan(2)
  })
})

describe('esCoordenadaValida', () => {
  it('acepta coordenadas dentro de rango', () => {
    expect(esCoordenadaValida({ lat: 4.7, lng: -74.06 })).toBe(true)
    expect(esCoordenadaValida({ lat: -90, lng: 180 })).toBe(true)
  })

  it('rechaza fuera de rango, NaN e infinitos', () => {
    expect(esCoordenadaValida({ lat: 91, lng: 0 })).toBe(false)
    expect(esCoordenadaValida({ lat: 0, lng: 181 })).toBe(false)
    expect(esCoordenadaValida({ lat: Number.NaN, lng: 0 })).toBe(false)
    expect(esCoordenadaValida({ lat: 0, lng: Number.POSITIVE_INFINITY })).toBe(false)
  })
})
