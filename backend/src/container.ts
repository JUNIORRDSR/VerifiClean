import { cargarEnv, type Env } from './config/env'
import { DbFirestore, crearFirestore } from './repos/firestore'
import { DbMemoria } from './repos/memory'
import type { Db } from './repos/types'
import { ServicioAuthFalso, ServicioAuthFirebase, type ServicioAuth } from './services/auth'
import { ServicioEvidencia } from './services/evidencia'
import {
  ServicioIntegridadFalso,
  ServicioIntegridadPlay,
  type ServicioIntegridad,
} from './services/integrity'
import {
  ServicioAlmacenamientoGcs,
  ServicioAlmacenamientoMemoria,
  type ServicioAlmacenamiento,
} from './services/storage'

/**
 * Composición de dependencias. Un único punto donde se decide qué implementación
 * entra según el entorno; el resto del código sólo conoce interfaces.
 *
 * No hay contenedor de DI ni decoradores: son seis objetos y un `switch`. Meter un
 * framework aquí añadiría arranque en frío a un servicio que escala desde cero.
 */
export interface Contenedor {
  env: Env
  db: Db
  auth: ServicioAuth
  integridad: ServicioIntegridad
  almacenamiento: ServicioAlmacenamiento
  evidencia: ServicioEvidencia
}

/** Inicializa firebase-admin una sola vez por proceso. */
let firebaseInicializado = false
async function asegurarFirebase(projectId?: string): Promise<void> {
  if (firebaseInicializado) return
  const { getApps, initializeApp, applicationDefault } = await import('firebase-admin/app')
  if (getApps().length === 0) {
    initializeApp({ credential: applicationDefault(), ...(projectId ? { projectId } : {}) })
  }
  firebaseInicializado = true
}

export async function crearContenedor(
  env: Env = cargarEnv(),
  sobrescrituras: Partial<Contenedor> = {},
): Promise<Contenedor> {
  const necesitaFirebase = env.AUTH_MODE === 'firebase' || env.STORAGE_MODE === 'gcs'
  if (necesitaFirebase && !sobrescrituras.auth) await asegurarFirebase(env.GCP_PROJECT_ID)

  const db: Db =
    sobrescrituras.db ??
    (env.DB_MODE === 'firestore' ? new DbFirestore(crearFirestore(env.GCP_PROJECT_ID)) : new DbMemoria())

  const auth: ServicioAuth =
    sobrescrituras.auth ??
    (env.AUTH_MODE === 'firebase' ? new ServicioAuthFirebase() : new ServicioAuthFalso())

  const integridad: ServicioIntegridad =
    sobrescrituras.integridad ??
    (env.INTEGRITY_MODE === 'playintegrity'
      ? new ServicioIntegridadPlay(env.ANDROID_PACKAGE_NAME)
      : new ServicioIntegridadFalso())

  const almacenamiento: ServicioAlmacenamiento =
    sobrescrituras.almacenamiento ??
    (env.STORAGE_MODE === 'gcs'
      ? new ServicioAlmacenamientoGcs(env.STORAGE_BUCKET!)
      : new ServicioAlmacenamientoMemoria())

  return {
    env,
    db,
    auth,
    integridad,
    almacenamiento,
    evidencia: sobrescrituras.evidencia ?? new ServicioEvidencia(db, almacenamiento, integridad),
  }
}
