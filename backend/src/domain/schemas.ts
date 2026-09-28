import { z } from 'zod'
import { MOTIVOS_ALERTA } from './types'

/**
 * Validación de entrada. Espejo de los `components.schemas` de `contracts/openapi.yaml`.
 *
 * Regla: nada entra al dominio sin pasar por aquí. Los handlers reciben tipos ya
 * validados y no vuelven a comprobar nada.
 */

export const rolSchema = z.enum(['admin', 'supervisor'])
export const estadoVisitaSchema = z.enum(['pendiente', 'en_progreso', 'completada'])
export const estadoRevisionAlertaSchema = z.enum([
  'pendiente',
  'revisada',
  'justificada',
  'escalada',
])
export const respuestaChecklistSchema = z.enum(['si', 'no', 'na'])
export const motivoAlertaSchema = z.enum(MOTIVOS_ALERTA)

export const checklistItemSchema = z.object({
  id: z.string().min(1).max(64),
  pregunta: z.string().min(1).max(300),
  respuesta: respuestaChecklistSchema.nullable().default(null),
  nota: z.string().max(500).nullable().default(null),
})

export const checklistSchema = z.array(checklistItemSchema).max(50)

/** Acepta ISO-8601; rechaza fechas imposibles que `new Date()` aceptaría como NaN. */
export const fechaIsoSchema = z
  .string()
  .refine((v) => !Number.isNaN(Date.parse(v)), { message: 'Fecha ISO-8601 inválida' })
  .transform((v) => new Date(v))

export const latSchema = z.coerce.number().min(-90).max(90)
export const lngSchema = z.coerce.number().min(-180).max(180)

// ---------------------------------------------------------------- auth

export const loginSchema = z.object({
  idToken: z.string().min(1, 'idToken requerido'),
  deviceId: z.string().min(4).max(128).nullish(),
})

// ---------------------------------------------------------------- catálogo

export const sitioNuevoSchema = z.object({
  clienteId: z.string().min(1).max(64),
  nombreCliente: z.string().min(1).max(160),
  nombreSede: z.string().min(1).max(160),
  direccion: z.string().min(1).max(300),
  lat: latSchema,
  lng: lngSchema,
  // Mínimo 50 m: por debajo, el error típico del GPS balanceado genera falsos
  // positivos de "fuera de geocerca" sobre supervisores que sí fueron.
  radioPermitidoMetros: z.coerce.number().min(50).max(2000).default(150),
})

export const usuarioNuevoSchema = z.object({
  nombre: z.string().min(2).max(120),
  email: z.string().email().max(254),
  password: z.string().min(8).max(128),
  rol: rolSchema,
})

/**
 * Alta y baja lógica. Nunca hay borrado: una sede o un supervisor dados de baja siguen
 * apareciendo en las visitas y evidencias que ya produjeron, que es el rastro de auditoría
 * que justifica el producto entero.
 */
export const cambioActivoSchema = z.object({
  activo: z.boolean(),
})

/** La identidad y la hora no se aceptan del cliente: las fija el servidor autenticado. */
export const revisionAlertaInputSchema = z
  .object({
    estado: estadoRevisionAlertaSchema,
    nota: z.string().trim().min(1).max(1000).nullable().optional(),
  })
  .strict()
  .superRefine((valor, ctx) => {
    if ((valor.estado === 'justificada' || valor.estado === 'escalada') && !valor.nota) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['nota'],
        message: `La nota es obligatoria cuando la alerta queda ${valor.estado}.`,
      })
    }
  })

/**
 * Clave que identifica un intento de alta administrativa.
 *
 * Se valida como cabecera independiente del cuerpo: repetir la misma clave con la misma
 * carga devuelve el recurso original; reutilizarla para otra carga es un conflicto.
 */
export const idempotencyKeyHeadersSchema = z.object({
  idempotencyKey: z.string().uuid('Idempotency-Key debe ser un UUID'),
})

export const visitaNuevaSchema = z.object({
  siteId: z.string().min(1),
  supervisorId: z.string().min(1),
  fechaAsignada: fechaIsoSchema,
  checklist: checklistSchema.optional(),
})

// ---------------------------------------------------------------- consultas

const limiteSchema = z.coerce.number().int().min(1).max(200).default(50)

/** `?sospechosas=true` llega como string. `'false'` debe ser `false`, no truthy. */
const boolQuerySchema = z
  .union([z.boolean(), z.enum(['true', 'false', '1', '0'])])
  .transform((v) => v === true || v === 'true' || v === '1')

export const listarVisitasQuerySchema = z.object({
  supervisorId: z.string().min(1).optional(),
  estado: estadoVisitaSchema.optional(),
  desde: fechaIsoSchema.optional(),
  hasta: fechaIsoSchema.optional(),
  limite: limiteSchema,
  cursor: z.string().min(1).optional(),
})

export const adminVisitasQuerySchema = listarVisitasQuerySchema.extend({
  siteId: z.string().min(1).optional(),
  sospechosas: boolQuerySchema.optional(),
})

export const reporteQuerySchema = z
  .object({
    clienteId: z.string().min(1),
    desde: fechaIsoSchema,
    hasta: fechaIsoSchema,
  })
  .refine((q) => q.desde <= q.hasta, {
    message: '`desde` debe ser anterior o igual a `hasta`',
    path: ['desde'],
  })

export const listarSitiosQuerySchema = z.object({
  clienteId: z.string().min(1).optional(),
  activo: boolQuerySchema.optional(),
})

export const listarUsuariosQuerySchema = z.object({
  rol: rolSchema.optional(),
})

// ---------------------------------------------------------------- evidencia

/**
 * Campos de texto del `multipart/form-data`. Todo llega como string, de ahí los
 * `coerce`. `checklist` viaja serializado porque multipart no anida estructuras.
 */
export const evidenciaFormSchema = z.object({
  clienteEvidenciaId: z.string().uuid('clienteEvidenciaId debe ser un UUID'),
  // Opcionales: el dispositivo puede no conseguir ubicación. Antes la app mandaba 0,0 en
  // ese caso y el servidor calculaba una distancia de 8.000 km que el panel del admin
  // mostraba como si fuera una medición real.
  lat: latSchema.nullish(),
  lng: lngSchema.nullish(),
  precisionMetros: z.coerce.number().min(0).max(100_000).nullish(),
  mockLocationReportada: boolQuerySchema.default(false),
  capturadoEn: fechaIsoSchema.nullish(),
  hashClienteReportado: z
    .string()
    .regex(/^[a-fA-F0-9]{64}$/, 'hashClienteReportado debe ser SHA-256 en hex')
    .nullish(),
  integrityToken: z.string().min(1).max(8192).nullish(),
  checklist: z
    .string()
    .optional()
    .transform((raw, ctx) => {
      if (!raw) return undefined
      try {
        return checklistSchema.parse(JSON.parse(raw))
      } catch {
        ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'checklist no es JSON válido' })
        return z.NEVER
      }
    }),
})

export const completarVisitaSchema = z.object({
  checklist: checklistSchema,
  observaciones: z.string().max(2000).nullish(),
})

export type LoginInput = z.infer<typeof loginSchema>
export type SitioNuevoInput = z.infer<typeof sitioNuevoSchema>
export type UsuarioNuevoInput = z.infer<typeof usuarioNuevoSchema>
export type CambioActivoInput = z.infer<typeof cambioActivoSchema>
export type RevisionAlertaInput = z.infer<typeof revisionAlertaInputSchema>
export type IdempotencyKeyHeadersInput = z.infer<typeof idempotencyKeyHeadersSchema>
export type VisitaNuevaInput = z.infer<typeof visitaNuevaSchema>
export type EvidenciaFormInput = z.infer<typeof evidenciaFormSchema>
export type CompletarVisitaInput = z.infer<typeof completarVisitaSchema>
export type ListarVisitasQuery = z.infer<typeof listarVisitasQuerySchema>
export type AdminVisitasQuery = z.infer<typeof adminVisitasQuerySchema>
export type ReporteQuery = z.infer<typeof reporteQuerySchema>
