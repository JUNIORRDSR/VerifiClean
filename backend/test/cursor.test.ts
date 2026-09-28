import { describe, expect, it } from 'vitest'
import { codificarCursor, decodificarCursor } from '../src/repos/memory'

/**
 * El cursor viaja como texto opaco entre cliente y servidor. Se testea aparte porque
 * un bug aquí no rompe nada visible: la lista sigue respondiendo 200, sólo que siempre
 * con la primera página. Un cliente que recorra páginas se queda en bucle.
 */
describe('cursor de paginación', () => {
  it('ida y vuelta con un id simple', () => {
    const fecha = new Date('2026-08-14T14:00:00.000Z')
    expect(decodificarCursor(codificarCursor(fecha, 'abc123'))).toEqual({
      millis: fecha.getTime(),
      id: 'abc123',
    })
  })

  it('ida y vuelta con un id que contiene guiones bajos', () => {
    const fecha = new Date('2026-08-14T14:00:00.000Z')
    expect(decodificarCursor(codificarCursor(fecha, 'visit_demo_1'))).toEqual({
      millis: fecha.getTime(),
      id: 'visit_demo_1',
    })
  })

  it('ida y vuelta con un UUID, que es lo que genera el servidor', () => {
    const fecha = new Date()
    const id = '0b1f4e6a-3c2d-4a5b-9e8f-1a2b3c4d5e6f'
    expect(decodificarCursor(codificarCursor(fecha, id))?.id).toBe(id)
  })

  it('rechaza cursores mal formados en vez de devolver millis NaN', () => {
    expect(decodificarCursor('')).toBeNull()
    expect(decodificarCursor('sin-separador')).toBeNull()
    expect(decodificarCursor('_solo-id')).toBeNull()
    expect(decodificarCursor('1234_')).toBeNull()
    expect(decodificarCursor('noEsNumero_abc')).toBeNull()
  })
})
