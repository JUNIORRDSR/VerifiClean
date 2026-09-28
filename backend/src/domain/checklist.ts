import type { ChecklistItem } from './types'

/**
 * Plantilla que se aplica a una visita nueva si el admin no manda una propia.
 * Los `id` son estables: los reportes agregan por `id`, no por texto de pregunta.
 */
export const CHECKLIST_POR_DEFECTO: readonly ChecklistItem[] = Object.freeze([
  { id: 'banos', pregunta: '¿Baños limpios y con dotación completa?', respuesta: null, nota: null },
  { id: 'pisos', pregunta: '¿Pisos barridos y trapeados?', respuesta: null, nota: null },
  { id: 'residuos', pregunta: '¿Puntos ecológicos vaciados y separados?', respuesta: null, nota: null },
  { id: 'insumos', pregunta: '¿Insumos suficientes para el turno?', respuesta: null, nota: null },
  { id: 'uniforme', pregunta: '¿Personal con uniforme y EPP completo?', respuesta: null, nota: null },
])

export const nuevoChecklistPorDefecto = (): ChecklistItem[] =>
  CHECKLIST_POR_DEFECTO.map((i) => ({ ...i }))
