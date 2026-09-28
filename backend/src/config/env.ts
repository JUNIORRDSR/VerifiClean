import { z } from 'zod'

/**
 * Configuración por variables de entorno. Se valida una vez al arrancar: si falta
 * algo, el proceso muere en el arranque y Cloud Run no rota tráfico a una revisión
 * mal configurada.
 */
const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),

  // Cloud Run inyecta PORT. En local cae a 8080.
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),

  GCP_PROJECT_ID: z.string().min(1).optional(),
  STORAGE_BUCKET: z.string().min(1).optional(),

  /** Paquete Android autorizado; se contrasta contra el payload de Play Integrity. */
  ANDROID_PACKAGE_NAME: z.string().min(1).default('com.verificlean.app'),

  /**
   * `fake` evita depender de Firebase para desarrollar y testear. Con `fake`, el
   * header `Authorization: Bearer dev:<uid>` autentica como ese uid. Se rechaza
   * en producción más abajo — un backend de producción que acepte `dev:` es un
   * bypass total de autenticación.
   */
  AUTH_MODE: z.enum(['firebase', 'fake']).default('fake'),
  INTEGRITY_MODE: z.enum(['playintegrity', 'fake']).default('fake'),
  STORAGE_MODE: z.enum(['gcs', 'memoria']).default('memoria'),
  DB_MODE: z.enum(['firestore', 'memoria']).default('memoria'),

  /** Tamaño máximo de la foto de evidencia. 8 MB cubre JPEG de 12 MP con holgura. */
  MAX_FOTO_BYTES: z.coerce.number().int().min(100_000).default(8 * 1024 * 1024),

  /** Vigencia de la URL firmada que se le entrega al admin. */
  URL_FIRMADA_MINUTOS: z.coerce.number().int().min(1).max(1440).default(15),
})

export type Env = z.infer<typeof envSchema>

export function cargarEnv(fuente: NodeJS.ProcessEnv = process.env): Env {
  const parsed = envSchema.safeParse(fuente)
  if (!parsed.success) {
    const detalle = parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; ')
    throw new Error(`Configuración inválida — ${detalle}`)
  }
  const env = parsed.data

  if (env.NODE_ENV === 'production') {
    const falsos = (
      [
        ['AUTH_MODE', env.AUTH_MODE, 'firebase'],
        ['DB_MODE', env.DB_MODE, 'firestore'],
        ['STORAGE_MODE', env.STORAGE_MODE, 'gcs'],
      ] as const
    ).filter(([, actual, esperado]) => actual !== esperado)

    if (falsos.length > 0) {
      throw new Error(
        `NODE_ENV=production exige adaptadores reales. Corrige: ` +
          falsos.map(([k, a, e]) => `${k}=${a} (debe ser ${e})`).join(', '),
      )
    }
    if (!env.GCP_PROJECT_ID || !env.STORAGE_BUCKET) {
      throw new Error('NODE_ENV=production exige GCP_PROJECT_ID y STORAGE_BUCKET.')
    }
  }

  return env
}
