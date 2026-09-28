import type { Express } from 'express'
import { crearApp } from '../src/app'
import { cargarEnv } from '../src/config/env'
import { crearContenedor, type Contenedor } from '../src/container'
import { nuevoChecklistPorDefecto } from '../src/domain/checklist'
import type { Sitio, Usuario, Visita } from '../src/domain/types'
import { DbMemoria } from '../src/repos/memory'
import { ServicioAuthFalso } from '../src/services/auth'
import { ServicioAlmacenamientoMemoria } from '../src/services/storage'

/**
 * Banco de pruebas: app real, adaptadores en memoria.
 *
 * Se ejercita el mismo `crearApp` que corre en producción — middleware, enrutado,
 * validación y manejo de errores incluidos. Lo único intercambiado son los tres
 * puntos que salen del proceso: base de datos, almacenamiento e identidad.
 */

export interface Banco {
  app: Express
  contenedor: Contenedor
  db: DbMemoria
  auth: ServicioAuthFalso
  almacenamiento: ServicioAlmacenamientoMemoria
}

export async function crearBanco(): Promise<Banco> {
  const env = cargarEnv({
    NODE_ENV: 'test',
    AUTH_MODE: 'fake',
    DB_MODE: 'memoria',
    STORAGE_MODE: 'memoria',
    INTEGRITY_MODE: 'fake',
  } as NodeJS.ProcessEnv)

  const db = new DbMemoria()
  const auth = new ServicioAuthFalso()
  const almacenamiento = new ServicioAlmacenamientoMemoria()

  const contenedor = await crearContenedor(env, { db, auth, almacenamiento })
  return { app: crearApp(contenedor), contenedor, db, auth, almacenamiento }
}

/** En `AUTH_MODE=fake` el token es literalmente `dev:<uid>`. */
export const comoUsuario = (uid: string): string => `Bearer dev:${uid}`

const ahora = () => new Date('2026-08-14T14:00:00.000Z')

export async function sembrarUsuario(
  banco: Banco,
  parcial: Partial<Usuario> & Pick<Usuario, 'id' | 'rol'>,
): Promise<Usuario> {
  const usuario: Usuario = {
    nombre: `Usuario ${parcial.id}`,
    email: `${parcial.id}@verificlean.test`,
    deviceId: null,
    activo: true,
    creadoEn: ahora(),
    actualizadoEn: ahora(),
    ...parcial,
  }
  banco.auth.registrar(usuario.id, usuario.email)
  return banco.db.usuarios.crear(usuario)
}

export async function sembrarSitio(
  banco: Banco,
  parcial: Partial<Sitio> & Pick<Sitio, 'id'>,
): Promise<Sitio> {
  const sitio: Sitio = {
    clienteId: 'cli_demo',
    nombreCliente: 'Cliente Demo',
    nombreSede: 'Sede Demo',
    direccion: 'Cra 1 #1-1',
    // Torre Bavaria, Bogotá — misma coordenada que el seed, para poder razonar sobre
    // distancias reales en los tests de geocerca.
    lat: 4.700543,
    lng: -74.063644,
    radioPermitidoMetros: 150,
    activo: true,
    creadoEn: ahora(),
    actualizadoEn: ahora(),
    ...parcial,
  }
  return banco.db.sitios.crear(sitio)
}

export async function sembrarVisita(
  banco: Banco,
  parcial: Partial<Visita> & Pick<Visita, 'id' | 'siteId' | 'supervisorId'>,
): Promise<Visita> {
  const sitio = await banco.db.sitios.obtener(parcial.siteId)
  if (!sitio) throw new Error(`El test pide una visita sobre el sitio ${parcial.siteId}, que no existe.`)
  const supervisor = await banco.db.usuarios.obtener(parcial.supervisorId)

  const visita: Visita = {
    siteClienteId: sitio.clienteId,
    siteNombreCliente: sitio.nombreCliente,
    siteNombreSede: sitio.nombreSede,
    siteDireccion: sitio.direccion,
    siteLat: sitio.lat,
    siteLng: sitio.lng,
    siteRadioPermitidoMetros: sitio.radioPermitidoMetros,
    supervisorNombre: supervisor?.nombre ?? 'Supervisor',
    estado: 'pendiente',
    fechaAsignada: ahora(),
    iniciadaEn: null,
    completadaEn: null,
    observaciones: null,
    checklist: nuevoChecklistPorDefecto(),
    evidenciaCount: 0,
    tieneSospechosas: false,
    revisionAlerta: {
      estado: 'pendiente',
      nota: null,
      revisadaPorId: null,
      revisadaPorNombre: null,
      revisadaEn: null,
    },
    creadoEn: ahora(),
    actualizadoEn: ahora(),
    ...parcial,
  }
  return banco.db.visitas.crear(visita)
}

/**
 * JPEG mínimo válido: SOI + APP0 + EOI. `contenido` cambia los bytes para producir
 * hashes distintos sin dejar de parecer una foto.
 */
export function jpegDePrueba(contenido = 'foto-1'): Buffer {
  return Buffer.concat([
    Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]),
    Buffer.from('JFIF\0', 'ascii'),
    Buffer.from(contenido, 'utf8'),
    Buffer.from([0xff, 0xd9]),
  ])
}

/** Escenario base: un admin, un supervisor, un sitio y una visita pendiente. */
export async function escenarioBase(banco: Banco) {
  const admin = await sembrarUsuario(banco, { id: 'u_admin', rol: 'admin', nombre: 'Ana Admin' })
  const supervisor = await sembrarUsuario(banco, {
    id: 'u_sup',
    rol: 'supervisor',
    nombre: 'Carlos Supervisor',
  })
  const sitio = await sembrarSitio(banco, { id: 'site_1' })
  const visita = await sembrarVisita(banco, {
    id: 'visit_1',
    siteId: sitio.id,
    supervisorId: supervisor.id,
  })
  return { admin, supervisor, sitio, visita }
}
