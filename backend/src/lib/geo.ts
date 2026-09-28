/**
 * Distancia geodésica por fórmula de Haversine.
 *
 * Por qué Haversine y no Vincenty ni PostGIS: las geocercas de este producto van de
 * 50 a 2000 m. En ese rango Haversine (esfera de radio medio) se desvía menos de
 * 0.5 % respecto al elipsoide WGS84 — es decir, menos de 10 m en el peor caso de
 * 2 km, muy por debajo del error propio del GPS (~30-60 m con
 * PRIORITY_BALANCED_POWER_ACCURACY). Cualquier precisión extra se la come el sensor.
 */

/** Radio medio de la Tierra (IUGG), en metros. */
const RADIO_TIERRA_M = 6_371_008.8

const aRadianes = (grados: number): number => (grados * Math.PI) / 180

export interface Punto {
  lat: number
  lng: number
}

/** Distancia en metros entre dos puntos. Siempre >= 0. */
export function distanciaHaversineMetros(a: Punto, b: Punto): number {
  const dLat = aRadianes(b.lat - a.lat)
  const dLng = aRadianes(b.lng - a.lng)
  const latA = aRadianes(a.lat)
  const latB = aRadianes(b.lat)

  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(latA) * Math.cos(latB) * Math.sin(dLng / 2) ** 2

  // `Math.min(1, ...)` protege contra que el redondeo en coma flotante empuje el
  // argumento por encima de 1 en puntos casi idénticos, que daría NaN.
  return 2 * RADIO_TIERRA_M * Math.asin(Math.min(1, Math.sqrt(h)))
}

/** Coordenada dentro de rango y no NaN. */
export function esCoordenadaValida(p: Punto): boolean {
  return (
    Number.isFinite(p.lat) &&
    Number.isFinite(p.lng) &&
    p.lat >= -90 &&
    p.lat <= 90 &&
    p.lng >= -180 &&
    p.lng <= 180
  )
}
