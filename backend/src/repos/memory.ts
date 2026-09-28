import type { Evidencia, Rol, Sitio, Usuario, Visita } from '../domain/types'
import { conflicto, noEncontrado } from '../lib/errors'
import type {
  Db,
  EvidenciaRepo,
  FiltroVisitas,
  PaginaVisitas,
  SitioRepo,
  UsuarioRepo,
  VisitaRepo,
} from './types'

/**
 * Implementación en memoria. Se usa en toda la suite de tests y en `DB_MODE=memoria`
 * para levantar el backend sin credenciales de GCP.
 *
 * Replica deliberadamente el comportamiento de Firestore en lo que afecta al código
 * que la consume: mismo orden (`fechaAsignada` DESC, desempate por id), mismo formato
 * de cursor, y copia defensiva en cada lectura/escritura para que nadie mute un
 * documento "guardado" por tener la referencia — cosa que con Firestore es imposible
 * y que en memoria produciría tests que pasan por accidente.
 */

const copia = <T>(v: T): T => structuredClone(v)

export const codificarCursor = (fecha: Date, id: string): string => `${fecha.getTime()}_${id}`

/**
 * Se parte por el **primer** `_`: los millis son sólo dígitos, pero el id sí puede
 * llevar guiones bajos (`visit_p4`). Partir por el último dejaba los millis en `NaN`,
 * el cursor se descartaba en silencio y la paginación devolvía siempre la primera
 * página — un bucle infinito para cualquier cliente que recorriera las páginas.
 */
export function decodificarCursor(cursor: string): { millis: number; id: string } | null {
  const sep = cursor.indexOf('_')
  if (sep <= 0) return null
  const crudoMillis = cursor.slice(0, sep)
  const id = cursor.slice(sep + 1)
  if (!/^\d+$/.test(crudoMillis) || id.length === 0) return null
  return { millis: Number(crudoMillis), id }
}

/** Orden canónico de visitas: fechaAsignada DESC, id DESC como desempate estable. */
const compararVisitas = (a: Visita, b: Visita): number => {
  const d = b.fechaAsignada.getTime() - a.fechaAsignada.getTime()
  return d !== 0 ? d : (a.id < b.id ? 1 : a.id > b.id ? -1 : 0)
}

class UsuarioRepoMemoria implements UsuarioRepo {
  constructor(private readonly datos: Map<string, Usuario>) {}

  async obtener(id: string) {
    const u = this.datos.get(id)
    return u ? copia(u) : null
  }

  async porEmail(email: string) {
    const buscado = email.toLowerCase()
    for (const u of this.datos.values()) {
      if (u.email.toLowerCase() === buscado) return copia(u)
    }
    return null
  }

  async listar(filtro: { rol?: Rol }) {
    return [...this.datos.values()]
      .filter((u) => !filtro.rol || u.rol === filtro.rol)
      .sort((a, b) => a.nombre.localeCompare(b.nombre, 'es'))
      .map(copia)
  }

  async crear(usuario: Usuario) {
    this.datos.set(usuario.id, copia(usuario))
    return copia(usuario)
  }

  async actualizar(id: string, cambios: Partial<Usuario>) {
    const actual = this.datos.get(id)
    if (!actual) throw noEncontrado(`Usuario ${id} no existe.`)
    const nuevo: Usuario = { ...actual, ...copia(cambios), id, actualizadoEn: new Date() }
    this.datos.set(id, nuevo)
    return copia(nuevo)
  }
}

class SitioRepoMemoria implements SitioRepo {
  private readonly huellasIdempotencia = new Map<string, string>()

  constructor(private readonly datos: Map<string, Sitio>) {}

  async obtener(id: string) {
    const s = this.datos.get(id)
    return s ? copia(s) : null
  }

  async listar(filtro: { clienteId?: string; activo?: boolean }) {
    return [...this.datos.values()]
      .filter((s) => !filtro.clienteId || s.clienteId === filtro.clienteId)
      .filter((s) => filtro.activo === undefined || s.activo === filtro.activo)
      .sort((a, b) => a.nombreSede.localeCompare(b.nombreSede, 'es'))
      .map(copia)
  }

  async crear(sitio: Sitio) {
    this.datos.set(sitio.id, copia(sitio))
    return copia(sitio)
  }

  async obtenerIdempotente(id: string, huella: string) {
    const actual = this.datos.get(id)
    if (!actual) return null
    if (this.huellasIdempotencia.get(id) !== huella) {
      throw conflicto('La clave de idempotencia ya se usó para crear otra sede.')
    }
    return copia(actual)
  }

  async crearIdempotente(sitio: Sitio, huella: string) {
    const actual = this.datos.get(sitio.id)
    if (actual) {
      if (this.huellasIdempotencia.get(sitio.id) !== huella) {
        throw conflicto('La clave de idempotencia ya se usó para crear otra sede.')
      }
      return { recurso: copia(actual), yaExistia: true }
    }

    this.datos.set(sitio.id, copia(sitio))
    this.huellasIdempotencia.set(sitio.id, huella)
    return { recurso: copia(sitio), yaExistia: false }
  }

  async actualizar(id: string, cambios: Partial<Sitio>) {
    const actual = this.datos.get(id)
    if (!actual) throw noEncontrado(`Sitio ${id} no existe.`)
    const nuevo: Sitio = { ...actual, ...copia(cambios), id, actualizadoEn: new Date() }
    this.datos.set(id, nuevo)
    return copia(nuevo)
  }
}

class VisitaRepoMemoria implements VisitaRepo {
  private readonly huellasIdempotencia = new Map<string, string>()

  constructor(private readonly datos: Map<string, Visita>) {}

  async obtener(id: string) {
    const v = this.datos.get(id)
    return v ? copia(v) : null
  }

  async listar(filtro: FiltroVisitas): Promise<PaginaVisitas> {
    let items = [...this.datos.values()]
      .filter((v) => !filtro.supervisorId || v.supervisorId === filtro.supervisorId)
      .filter((v) => !filtro.siteId || v.siteId === filtro.siteId)
      .filter((v) => !filtro.siteClienteId || v.siteClienteId === filtro.siteClienteId)
      .filter((v) => !filtro.estado || v.estado === filtro.estado)
      .filter((v) => filtro.soloSospechosas !== true || v.tieneSospechosas)
      .filter((v) => !filtro.desde || v.fechaAsignada >= filtro.desde)
      .filter((v) => !filtro.hasta || v.fechaAsignada <= filtro.hasta)
      .sort(compararVisitas)

    if (filtro.cursor) {
      const dec = decodificarCursor(filtro.cursor)
      if (dec) {
        const desde = items.findIndex((v) => v.id === dec.id && v.fechaAsignada.getTime() === dec.millis)
        items = desde >= 0 ? items.slice(desde + 1) : items
      }
    }

    const pagina = items.slice(0, filtro.limite)
    const ultimo = pagina.at(-1)
    const hayMas = items.length > filtro.limite
    return {
      items: pagina.map(copia),
      siguienteCursor: hayMas && ultimo ? codificarCursor(ultimo.fechaAsignada, ultimo.id) : null,
    }
  }

  async crear(visita: Visita) {
    this.datos.set(visita.id, copia(visita))
    return copia(visita)
  }

  async obtenerIdempotente(id: string, huella: string) {
    const actual = this.datos.get(id)
    if (!actual) return null
    if (this.huellasIdempotencia.get(id) !== huella) {
      throw conflicto('La clave de idempotencia ya se usó para asignar otra visita.')
    }
    return copia(actual)
  }

  async crearIdempotente(visita: Visita, huella: string) {
    const actual = this.datos.get(visita.id)
    if (actual) {
      if (this.huellasIdempotencia.get(visita.id) !== huella) {
        throw conflicto('La clave de idempotencia ya se usó para asignar otra visita.')
      }
      return { recurso: copia(actual), yaExistia: true }
    }

    this.datos.set(visita.id, copia(visita))
    this.huellasIdempotencia.set(visita.id, huella)
    return { recurso: copia(visita), yaExistia: false }
  }

  async actualizar(id: string, cambios: Partial<Visita>) {
    const actual = this.datos.get(id)
    if (!actual) throw noEncontrado(`Visita ${id} no existe.`)
    const nueva: Visita = { ...actual, ...copia(cambios), id, actualizadoEn: new Date() }
    this.datos.set(id, nueva)
    return copia(nueva)
  }
}

class EvidenciaRepoMemoria implements EvidenciaRepo {
  private readonly datos: Map<string, Evidencia>

  async porVisita(visitId: string) {
    return [...this.datos.values()]
      .filter((e) => e.visitId === visitId)
      .sort((a, b) => a.timestampServidor.getTime() - b.timestampServidor.getTime())
      .map(copia)
  }

  async porVisitas(visitIds: string[]) {
    const set = new Set(visitIds)
    return [...this.datos.values()].filter((e) => set.has(e.visitId)).map(copia)
  }

  constructor(
    datos: Map<string, Evidencia>,
    private readonly reservasDeHash: Map<string, string>,
  ) {
    this.datos = datos
  }

  async porClienteEvidenciaId(clienteEvidenciaId: string, visitId: string, supervisorId: string) {
    for (const e of this.datos.values()) {
      if (
        e.clienteEvidenciaId === clienteEvidenciaId &&
        e.visitId === visitId &&
        e.supervisorId === supervisorId
      ) {
        return copia(e)
      }
    }
    return null
  }

  async reservarHash(hash: string, visitId: string) {
    const duenoActual = this.reservasDeHash.get(hash)
    if (duenoActual === undefined) {
      this.reservasDeHash.set(hash, visitId)
      return { esDuplicado: false }
    }
    return { esDuplicado: duenoActual !== visitId }
  }
}

export class DbMemoria implements Db {
  private readonly tablaUsuarios = new Map<string, Usuario>()
  private readonly tablaSitios = new Map<string, Sitio>()
  private readonly tablaVisitas = new Map<string, Visita>()
  private readonly tablaEvidencias = new Map<string, Evidencia>()
  /** hash → visitId que lo reservó primero. Espejo de la colección `hashesEvidencia`. */
  private readonly reservasDeHash = new Map<string, string>()

  readonly usuarios = new UsuarioRepoMemoria(this.tablaUsuarios)
  readonly sitios = new SitioRepoMemoria(this.tablaSitios)
  readonly visitas = new VisitaRepoMemoria(this.tablaVisitas)
  readonly evidencias = new EvidenciaRepoMemoria(this.tablaEvidencias, this.reservasDeHash)

  async registrarEvidencia(evidencia: Evidencia) {
    const existente = await this.evidencias.porClienteEvidenciaId(
      evidencia.clienteEvidenciaId,
      evidencia.visitId,
      evidencia.supervisorId,
    )
    if (existente) return { evidencia: existente, yaExistia: true }

    const visita = this.tablaVisitas.get(evidencia.visitId)
    if (!visita) throw noEncontrado(`Visita ${evidencia.visitId} no existe.`)

    this.tablaEvidencias.set(evidencia.id, copia(evidencia))
    this.tablaVisitas.set(visita.id, {
      ...visita,
      evidenciaCount: visita.evidenciaCount + 1,
      tieneSospechosas: visita.tieneSospechosas || evidencia.esSospechosa,
      estado: visita.estado === 'pendiente' ? 'en_progreso' : visita.estado,
      iniciadaEn: visita.iniciadaEn ?? evidencia.timestampServidor,
      revisionAlerta:
        evidencia.esSospechosa && visita.revisionAlerta.estado !== 'pendiente'
          ? {
              estado: 'pendiente',
              nota: null,
              revisadaPorId: null,
              revisadaPorNombre: null,
              revisadaEn: null,
            }
          : visita.revisionAlerta,
      actualizadoEn: new Date(),
    })

    return { evidencia: copia(evidencia), yaExistia: false }
  }

  /** Sólo para tests y para el script de seed. */
  vaciar(): void {
    this.tablaUsuarios.clear()
    this.tablaSitios.clear()
    this.tablaVisitas.clear()
    this.tablaEvidencias.clear()
  }
}
