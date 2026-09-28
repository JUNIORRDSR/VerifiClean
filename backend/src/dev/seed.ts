import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import type { ChecklistItem, Sitio, Usuario, Visita } from '../domain/types'
import { logger } from '../lib/logger'
import type { Db } from '../repos/types'
import type { ServicioAuth } from '../services/auth'
import { ServicioAuthFalso } from '../services/auth'

/**
 * Carga los datos demo de `contracts/seed/seed.json`.
 *
 * Sirve para dos cosas: poblar la base en memoria al arrancar `npm run dev`, para que
 * la app Android tenga contra qué hablar desde el primer minuto, y sembrar el emulador
 * de Firestore con `npm run seed`.
 *
 * Nunca se ejecuta contra un proyecto real: `sembrar` exige modo memoria o emulador.
 */

interface SeedUsuario {
  id: string
  nombre: string
  email: string
  password: string
  rol: 'admin' | 'supervisor'
  deviceId: string | null
  activo: boolean
}

interface SeedSitio {
  id: string
  clienteId: string
  nombreCliente: string
  nombreSede: string
  direccion: string
  lat: number
  lng: number
  radioPermitidoMetros: number
  activo: boolean
}

interface SeedVisita {
  id: string
  siteId: string
  supervisorId: string
  estado: 'pendiente' | 'en_progreso' | 'completada'
  diasDesdeHoy: number
}

interface ArchivoSeed {
  users: SeedUsuario[]
  sites: SeedSitio[]
  visits: SeedVisita[]
  checklistPorDefecto: ChecklistItem[]
}

/**
 * El archivo vive fuera de `backend/`, así que su ruta cambia según se ejecute desde
 * `src/` con tsx o desde `dist/` compilado. Se prueban los dos sitios en vez de
 * asumir uno.
 */
function localizarSeed(): string {
  const candidatos = [
    path.resolve(process.cwd(), '../contracts/seed/seed.json'),
    path.resolve(process.cwd(), 'contracts/seed/seed.json'),
    path.resolve(__dirname, '../../../contracts/seed/seed.json'),
    path.resolve(__dirname, '../../../../contracts/seed/seed.json'),
  ]
  const encontrado = candidatos.find(existsSync)
  if (!encontrado) {
    throw new Error(
      `No se encontró contracts/seed/seed.json. Rutas probadas:\n  ${candidatos.join('\n  ')}`,
    )
  }
  return encontrado
}

export function leerSeed(): ArchivoSeed {
  return JSON.parse(readFileSync(localizarSeed(), 'utf8')) as ArchivoSeed
}

export interface ResultadoSeed {
  usuarios: number
  sitios: number
  visitas: number
  credenciales: { email: string; password: string; rol: string }[]
}

export async function sembrar(db: Db, auth: ServicioAuth): Promise<ResultadoSeed> {
  const datos = leerSeed()
  const ahora = new Date()

  for (const u of datos.users) {
    const usuario: Usuario = {
      id: u.id,
      nombre: u.nombre,
      email: u.email.toLowerCase(),
      rol: u.rol,
      deviceId: u.deviceId,
      activo: u.activo,
      creadoEn: ahora,
      actualizadoEn: ahora,
    }
    await db.usuarios.crear(usuario)
    // En modo falso el id del seed es directamente el uid; con Firebase real, el uid
    // lo asigna Auth y este atajo no aplica (por eso `npm run seed` sólo se usa con
    // el emulador, donde el uid se puede fijar).
    if (auth instanceof ServicioAuthFalso) auth.registrar(u.id, usuario.email)
  }

  for (const s of datos.sites) {
    const sitio: Sitio = { ...s, creadoEn: ahora, actualizadoEn: ahora }
    await db.sitios.crear(sitio)
  }

  const porId = new Map(datos.sites.map((s) => [s.id, s]))
  for (const v of datos.visits) {
    const sitio = porId.get(v.siteId)
    const supervisor = datos.users.find((u) => u.id === v.supervisorId)
    if (!sitio || !supervisor) {
      logger.warn('Visita de seed descartada: sitio o supervisor inexistente', { visita: v.id })
      continue
    }

    const fechaAsignada = new Date(ahora.getTime() + v.diasDesdeHoy * 86_400_000)
    const visita: Visita = {
      id: v.id,
      siteId: sitio.id,
      siteClienteId: sitio.clienteId,
      siteNombreCliente: sitio.nombreCliente,
      siteNombreSede: sitio.nombreSede,
      siteDireccion: sitio.direccion,
      siteLat: sitio.lat,
      siteLng: sitio.lng,
      siteRadioPermitidoMetros: sitio.radioPermitidoMetros,
      supervisorId: supervisor.id,
      supervisorNombre: supervisor.nombre,
      estado: v.estado,
      fechaAsignada,
      iniciadaEn: null,
      completadaEn: null,
      observaciones: null,
      checklist: datos.checklistPorDefecto.map((i) => ({ ...i })),
      evidenciaCount: 0,
      tieneSospechosas: false,
      revisionAlerta: {
        estado: 'pendiente',
        nota: null,
        revisadaPorId: null,
        revisadaPorNombre: null,
        revisadaEn: null,
      },
      creadoEn: ahora,
      actualizadoEn: ahora,
    }
    await db.visitas.crear(visita)
  }

  return {
    usuarios: datos.users.length,
    sitios: datos.sites.length,
    visitas: datos.visits.length,
    credenciales: datos.users.map((u) => ({ email: u.email, password: u.password, rol: u.rol })),
  }
}

/** Id de visita nuevo, por si el seed se amplía desde otro script. */
export const nuevoId = randomUUID
