import { Firestore, Timestamp, type DocumentData, type Query } from '@google-cloud/firestore'
import type { Evidencia, Rol, Sitio, Usuario, Visita } from '../domain/types'
import { conflicto, noEncontrado } from '../lib/errors'
import { codificarCursor, decodificarCursor } from './memory'
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
 * Adaptador Firestore. Única capa del backend que conoce `Timestamp`, colecciones y
 * consultas; hacia arriba todo son objetos del dominio con `Date`.
 *
 * Las consultas de aquí son exactamente las que justifican cada índice de
 * `contracts/firestore.indexes.json`. Si cambia una, hay que revisar ese archivo.
 */

const COL = {
  users: 'users',
  sites: 'sites',
  visits: 'visits',
  evidences: 'evidences',
  /**
   * Un documento por hash de imagen, con id determinista. No guarda datos de negocio:
   * existe para que la decisión "esta foto ya se usó en otra visita" se tome dentro de
   * una transacción sobre un documento concreto, en vez de con una consulta que dos
   * peticiones simultáneas pueden ver vacía a la vez.
   */
  hashes: 'hashesEvidencia',
} as const

const aFecha = (v: unknown): Date =>
  v instanceof Timestamp ? v.toDate() : v instanceof Date ? v : new Date(0)

const aFechaOpcional = (v: unknown): Date | null =>
  v === null || v === undefined ? null : aFecha(v)

const aTimestamp = (d: Date): Timestamp => Timestamp.fromDate(d)

// ------------------------------------------------------------ mapeos

const docAUsuario = (id: string, d: DocumentData): Usuario => ({
  id,
  nombre: d.nombre,
  email: d.email,
  rol: d.rol,
  deviceId: d.deviceId ?? null,
  activo: d.activo === true,
  creadoEn: aFecha(d.creadoEn),
  actualizadoEn: aFecha(d.actualizadoEn),
})

const usuarioADoc = (u: Usuario): DocumentData => ({
  nombre: u.nombre,
  email: u.email,
  rol: u.rol,
  deviceId: u.deviceId,
  activo: u.activo,
  creadoEn: aTimestamp(u.creadoEn),
  actualizadoEn: aTimestamp(u.actualizadoEn),
})

const docASitio = (id: string, d: DocumentData): Sitio => ({
  id,
  clienteId: d.clienteId,
  nombreCliente: d.nombreCliente,
  nombreSede: d.nombreSede,
  direccion: d.direccion,
  lat: d.lat,
  lng: d.lng,
  radioPermitidoMetros: d.radioPermitidoMetros,
  activo: d.activo === true,
  creadoEn: aFecha(d.creadoEn),
  actualizadoEn: aFecha(d.actualizadoEn),
})

const sitioADoc = (s: Sitio): DocumentData => ({
  clienteId: s.clienteId,
  nombreCliente: s.nombreCliente,
  nombreSede: s.nombreSede,
  direccion: s.direccion,
  lat: s.lat,
  lng: s.lng,
  radioPermitidoMetros: s.radioPermitidoMetros,
  activo: s.activo,
  creadoEn: aTimestamp(s.creadoEn),
  actualizadoEn: aTimestamp(s.actualizadoEn),
})

const docAVisita = (id: string, d: DocumentData): Visita => ({
  id,
  siteId: d.siteId,
  siteClienteId: d.siteClienteId,
  siteNombreCliente: d.siteNombreCliente,
  siteNombreSede: d.siteNombreSede,
  siteDireccion: d.siteDireccion ?? '',
  siteLat: d.siteLat,
  siteLng: d.siteLng,
  siteRadioPermitidoMetros: d.siteRadioPermitidoMetros,
  supervisorId: d.supervisorId,
  supervisorNombre: d.supervisorNombre,
  estado: d.estado,
  fechaAsignada: aFecha(d.fechaAsignada),
  iniciadaEn: aFechaOpcional(d.iniciadaEn),
  completadaEn: aFechaOpcional(d.completadaEn),
  observaciones: d.observaciones ?? null,
  checklist: d.checklist ?? [],
  evidenciaCount: d.evidenciaCount ?? 0,
  tieneSospechosas: d.tieneSospechosas === true,
  revisionAlerta: {
    estado: d.revisionAlerta?.estado ?? 'pendiente',
    nota: d.revisionAlerta?.nota ?? null,
    revisadaPorId: d.revisionAlerta?.revisadaPorId ?? null,
    revisadaPorNombre: d.revisionAlerta?.revisadaPorNombre ?? null,
    revisadaEn: aFechaOpcional(d.revisionAlerta?.revisadaEn),
  },
  creadoEn: aFecha(d.creadoEn),
  actualizadoEn: aFecha(d.actualizadoEn),
})

const visitaADoc = (v: Visita): DocumentData => ({
  siteId: v.siteId,
  siteClienteId: v.siteClienteId,
  siteNombreCliente: v.siteNombreCliente,
  siteNombreSede: v.siteNombreSede,
  siteDireccion: v.siteDireccion,
  siteLat: v.siteLat,
  siteLng: v.siteLng,
  siteRadioPermitidoMetros: v.siteRadioPermitidoMetros,
  supervisorId: v.supervisorId,
  supervisorNombre: v.supervisorNombre,
  estado: v.estado,
  fechaAsignada: aTimestamp(v.fechaAsignada),
  iniciadaEn: v.iniciadaEn ? aTimestamp(v.iniciadaEn) : null,
  completadaEn: v.completadaEn ? aTimestamp(v.completadaEn) : null,
  observaciones: v.observaciones,
  checklist: v.checklist,
  evidenciaCount: v.evidenciaCount,
  tieneSospechosas: v.tieneSospechosas,
  revisionAlerta: {
    ...v.revisionAlerta,
    revisadaEn: v.revisionAlerta.revisadaEn
      ? aTimestamp(v.revisionAlerta.revisadaEn)
      : null,
  },
  creadoEn: aTimestamp(v.creadoEn),
  actualizadoEn: aTimestamp(v.actualizadoEn),
})

const docAEvidencia = (id: string, d: DocumentData): Evidencia => ({
  id,
  visitId: d.visitId,
  siteId: d.siteId,
  supervisorId: d.supervisorId,
  clienteEvidenciaId: d.clienteEvidenciaId,
  storagePath: d.storagePath,
  storageUrl: d.storageUrl,
  hashSHA256: d.hashSHA256,
  hashClienteReportado: d.hashClienteReportado ?? null,
  lat: d.lat,
  lng: d.lng,
  precisionMetros: d.precisionMetros ?? null,
  distanciaAlSitioMetros: d.distanciaAlSitioMetros,
  timestampServidor: aFecha(d.timestampServidor),
  timestampCliente: aFechaOpcional(d.timestampCliente),
  mockLocationReportada: d.mockLocationReportada === true,
  integrity: d.integrity,
  esSospechosa: d.esSospechosa === true,
  motivosAlerta: d.motivosAlerta ?? [],
  checklistSnapshot: d.checklistSnapshot ?? null,
  creadoEn: aFecha(d.creadoEn),
})

const evidenciaADoc = (e: Evidencia): DocumentData => ({
  visitId: e.visitId,
  siteId: e.siteId,
  supervisorId: e.supervisorId,
  clienteEvidenciaId: e.clienteEvidenciaId,
  storagePath: e.storagePath,
  storageUrl: e.storageUrl,
  hashSHA256: e.hashSHA256,
  hashClienteReportado: e.hashClienteReportado,
  lat: e.lat,
  lng: e.lng,
  precisionMetros: e.precisionMetros,
  distanciaAlSitioMetros: e.distanciaAlSitioMetros,
  timestampServidor: aTimestamp(e.timestampServidor),
  timestampCliente: e.timestampCliente ? aTimestamp(e.timestampCliente) : null,
  mockLocationReportada: e.mockLocationReportada,
  integrity: e.integrity,
  esSospechosa: e.esSospechosa,
  motivosAlerta: e.motivosAlerta,
  checklistSnapshot: e.checklistSnapshot,
  creadoEn: aTimestamp(e.creadoEn),
})

// ------------------------------------------------------------ repos

class UsuarioRepoFirestore implements UsuarioRepo {
  constructor(private readonly db: Firestore) {}

  async obtener(id: string) {
    const snap = await this.db.collection(COL.users).doc(id).get()
    return snap.exists ? docAUsuario(snap.id, snap.data()!) : null
  }

  async porEmail(email: string) {
    const snap = await this.db
      .collection(COL.users)
      .where('email', '==', email.toLowerCase())
      .limit(1)
      .get()
    const doc = snap.docs[0]
    return doc ? docAUsuario(doc.id, doc.data()) : null
  }

  async listar(filtro: { rol?: Rol }) {
    let q: Query = this.db.collection(COL.users)
    if (filtro.rol) q = q.where('rol', '==', filtro.rol)
    const snap = await q.orderBy('nombre').limit(500).get()
    return snap.docs.map((d) => docAUsuario(d.id, d.data()))
  }

  async crear(usuario: Usuario) {
    await this.db.collection(COL.users).doc(usuario.id).create(usuarioADoc(usuario))
    return usuario
  }

  async actualizar(id: string, cambios: Partial<Usuario>) {
    const ref = this.db.collection(COL.users).doc(id)
    const parche: DocumentData = { actualizadoEn: aTimestamp(new Date()) }
    if (cambios.nombre !== undefined) parche.nombre = cambios.nombre
    if (cambios.rol !== undefined) parche.rol = cambios.rol
    if (cambios.deviceId !== undefined) parche.deviceId = cambios.deviceId
    if (cambios.activo !== undefined) parche.activo = cambios.activo
    await ref.update(parche)
    const snap = await ref.get()
    if (!snap.exists) throw noEncontrado(`Usuario ${id} no existe.`)
    return docAUsuario(snap.id, snap.data()!)
  }
}

class SitioRepoFirestore implements SitioRepo {
  constructor(private readonly db: Firestore) {}

  async obtener(id: string) {
    const snap = await this.db.collection(COL.sites).doc(id).get()
    return snap.exists ? docASitio(snap.id, snap.data()!) : null
  }

  async listar(filtro: { clienteId?: string; activo?: boolean }) {
    let q: Query = this.db.collection(COL.sites)
    if (filtro.clienteId) q = q.where('clienteId', '==', filtro.clienteId)
    if (filtro.activo !== undefined) q = q.where('activo', '==', filtro.activo)
    const snap = await q.orderBy('nombreSede').limit(500).get()
    return snap.docs.map((d) => docASitio(d.id, d.data()))
  }

  async crear(sitio: Sitio) {
    await this.db.collection(COL.sites).doc(sitio.id).create(sitioADoc(sitio))
    return sitio
  }

  async obtenerIdempotente(id: string, huella: string) {
    const snap = await this.db.collection(COL.sites).doc(id).get()
    if (!snap.exists) return null
    if (snap.get('_idempotencyFingerprint') !== huella) {
      throw conflicto('La clave de idempotencia ya se usó para crear otra sede.')
    }
    return docASitio(snap.id, snap.data()!)
  }

  async crearIdempotente(sitio: Sitio, huella: string) {
    const ref = this.db.collection(COL.sites).doc(sitio.id)
    return this.db.runTransaction(async (tx) => {
      const snap = await tx.get(ref)
      if (snap.exists) {
        if (snap.get('_idempotencyFingerprint') !== huella) {
          throw conflicto('La clave de idempotencia ya se usó para crear otra sede.')
        }
        return { recurso: docASitio(snap.id, snap.data()!), yaExistia: true }
      }

      tx.create(ref, { ...sitioADoc(sitio), _idempotencyFingerprint: huella })
      return { recurso: sitio, yaExistia: false }
    })
  }

  /**
   * Sólo `activo` es modificable. Las coordenadas y el radio no: cada visita se lleva su
   * propio snapshot al asignarse, así que editarlos aquí no cambiaría ningún veredicto ya
   * emitido y daría la impresión contraria.
   */
  async actualizar(id: string, cambios: Partial<Sitio>) {
    const ref = this.db.collection(COL.sites).doc(id)
    const parche: DocumentData = { actualizadoEn: aTimestamp(new Date()) }
    if (cambios.activo !== undefined) parche.activo = cambios.activo
    await ref.update(parche)
    const snap = await ref.get()
    if (!snap.exists) throw noEncontrado(`Sitio ${id} no existe.`)
    return docASitio(snap.id, snap.data()!)
  }
}

class VisitaRepoFirestore implements VisitaRepo {
  constructor(private readonly db: Firestore) {}

  async obtener(id: string) {
    const snap = await this.db.collection(COL.visits).doc(id).get()
    return snap.exists ? docAVisita(snap.id, snap.data()!) : null
  }

  async listar(filtro: FiltroVisitas): Promise<PaginaVisitas> {
    let q: Query = this.db.collection(COL.visits)
    if (filtro.supervisorId) q = q.where('supervisorId', '==', filtro.supervisorId)
    if (filtro.siteId) q = q.where('siteId', '==', filtro.siteId)
    if (filtro.siteClienteId) q = q.where('siteClienteId', '==', filtro.siteClienteId)
    if (filtro.estado) q = q.where('estado', '==', filtro.estado)
    if (filtro.soloSospechosas === true) q = q.where('tieneSospechosas', '==', true)
    if (filtro.desde) q = q.where('fechaAsignada', '>=', aTimestamp(filtro.desde))
    if (filtro.hasta) q = q.where('fechaAsignada', '<=', aTimestamp(filtro.hasta))

    // `__name__` como segundo criterio hace el orden total y estable, que es lo que
    // vuelve confiable el cursor cuando varias visitas comparten fecha exacta.
    q = q.orderBy('fechaAsignada', 'desc').orderBy('__name__', 'desc')

    if (filtro.cursor) {
      const dec = decodificarCursor(filtro.cursor)
      if (dec) {
        q = q.startAfter(Timestamp.fromMillis(dec.millis), this.db.collection(COL.visits).doc(dec.id))
      }
    }

    // Se pide uno de más para saber si hay página siguiente sin un count() extra.
    const snap = await q.limit(filtro.limite + 1).get()
    const docs = snap.docs.slice(0, filtro.limite)
    const items = docs.map((d) => docAVisita(d.id, d.data()))
    const ultimo = items.at(-1)
    const hayMas = snap.docs.length > filtro.limite

    return {
      items,
      siguienteCursor: hayMas && ultimo ? codificarCursor(ultimo.fechaAsignada, ultimo.id) : null,
    }
  }

  async crear(visita: Visita) {
    await this.db.collection(COL.visits).doc(visita.id).create(visitaADoc(visita))
    return visita
  }

  async obtenerIdempotente(id: string, huella: string) {
    const snap = await this.db.collection(COL.visits).doc(id).get()
    if (!snap.exists) return null
    if (snap.get('_idempotencyFingerprint') !== huella) {
      throw conflicto('La clave de idempotencia ya se usó para asignar otra visita.')
    }
    return docAVisita(snap.id, snap.data()!)
  }

  async crearIdempotente(visita: Visita, huella: string) {
    const ref = this.db.collection(COL.visits).doc(visita.id)
    return this.db.runTransaction(async (tx) => {
      const snap = await tx.get(ref)
      if (snap.exists) {
        if (snap.get('_idempotencyFingerprint') !== huella) {
          throw conflicto('La clave de idempotencia ya se usó para asignar otra visita.')
        }
        return { recurso: docAVisita(snap.id, snap.data()!), yaExistia: true }
      }

      tx.create(ref, { ...visitaADoc(visita), _idempotencyFingerprint: huella })
      return { recurso: visita, yaExistia: false }
    })
  }

  async actualizar(id: string, cambios: Partial<Visita>) {
    const ref = this.db.collection(COL.visits).doc(id)
    const parche: DocumentData = { actualizadoEn: aTimestamp(new Date()) }
    if (cambios.estado !== undefined) parche.estado = cambios.estado
    if (cambios.checklist !== undefined) parche.checklist = cambios.checklist
    if (cambios.observaciones !== undefined) parche.observaciones = cambios.observaciones
    if (cambios.completadaEn !== undefined)
      parche.completadaEn = cambios.completadaEn ? aTimestamp(cambios.completadaEn) : null
    if (cambios.iniciadaEn !== undefined)
      parche.iniciadaEn = cambios.iniciadaEn ? aTimestamp(cambios.iniciadaEn) : null
    if (cambios.revisionAlerta !== undefined) {
      parche.revisionAlerta = {
        ...cambios.revisionAlerta,
        revisadaEn: cambios.revisionAlerta.revisadaEn
          ? aTimestamp(cambios.revisionAlerta.revisadaEn)
          : null,
      }
    }
    await ref.update(parche)
    const snap = await ref.get()
    if (!snap.exists) throw noEncontrado(`Visita ${id} no existe.`)
    return docAVisita(snap.id, snap.data()!)
  }
}

class EvidenciaRepoFirestore implements EvidenciaRepo {
  constructor(private readonly db: Firestore) {}

  async porVisita(visitId: string) {
    const snap = await this.db
      .collection(COL.evidences)
      .where('visitId', '==', visitId)
      .orderBy('timestampServidor', 'asc')
      .get()
    return snap.docs.map((d) => docAEvidencia(d.id, d.data()))
  }

  async porVisitas(visitIds: string[]) {
    if (visitIds.length === 0) return []
    // Firestore limita `in` a 30 valores por consulta; se trocea y se paraleliza.
    const lotes: string[][] = []
    for (let i = 0; i < visitIds.length; i += 30) lotes.push(visitIds.slice(i, i + 30))
    const resultados = await Promise.all(
      lotes.map((lote) =>
        this.db.collection(COL.evidences).where('visitId', 'in', lote).get(),
      ),
    )
    return resultados.flatMap((snap) => snap.docs.map((d) => docAEvidencia(d.id, d.data())))
  }

  async porClienteEvidenciaId(clienteEvidenciaId: string, visitId: string, supervisorId: string) {
    const snap = await this.db
      .collection(COL.evidences)
      .where('clienteEvidenciaId', '==', clienteEvidenciaId)
      .where('visitId', '==', visitId)
      .where('supervisorId', '==', supervisorId)
      .limit(1)
      .get()
    const doc = snap.docs[0]
    return doc ? docAEvidencia(doc.id, doc.data()) : null
  }

  async reservarHash(hash: string, visitId: string) {
    const ref = this.db.collection(COL.hashes).doc(hash)

    return this.db.runTransaction(async (tx) => {
      const snap = await tx.get(ref)
      if (!snap.exists) {
        tx.create(ref, { visitId, creadoEn: Timestamp.now() })
        return { esDuplicado: false }
      }
      // Repetir la misma foto dentro de la misma visita es legítimo: fotografiar dos
      // veces el mismo punto no es fraude.
      return { esDuplicado: snap.data()?.visitId !== visitId }
    })
  }
}

export class DbFirestore implements Db {
  readonly usuarios: UsuarioRepo
  readonly sitios: SitioRepo
  readonly visitas: VisitaRepo
  readonly evidencias: EvidenciaRepo

  constructor(private readonly db: Firestore) {
    this.usuarios = new UsuarioRepoFirestore(db)
    this.sitios = new SitioRepoFirestore(db)
    this.visitas = new VisitaRepoFirestore(db)
    this.evidencias = new EvidenciaRepoFirestore(db)
  }

  async registrarEvidencia(evidencia: Evidencia) {
    const refEvidencia = this.db.collection(COL.evidences).doc(evidencia.id)
    const refVisita = this.db.collection(COL.visits).doc(evidencia.visitId)
    const consultaIdempotencia = this.db
      .collection(COL.evidences)
      .where('clienteEvidenciaId', '==', evidencia.clienteEvidenciaId)
      .where('visitId', '==', evidencia.visitId)
      .where('supervisorId', '==', evidencia.supervisorId)
      .limit(1)

    return this.db.runTransaction(async (tx) => {
      // Firestore exige todas las lecturas antes de cualquier escritura.
      const yaSubida = await tx.get(consultaIdempotencia)
      const previa = yaSubida.docs[0]
      if (previa) {
        return { evidencia: docAEvidencia(previa.id, previa.data()), yaExistia: true }
      }

      const snapVisita = await tx.get(refVisita)
      if (!snapVisita.exists) throw noEncontrado(`Visita ${evidencia.visitId} no existe.`)
      const visita = docAVisita(snapVisita.id, snapVisita.data()!)
      if (visita.estado === 'completada') {
        throw conflicto('La visita ya está completada y no admite más evidencias.')
      }

      tx.create(refEvidencia, evidenciaADoc(evidencia))
      tx.update(refVisita, {
        evidenciaCount: visita.evidenciaCount + 1,
        tieneSospechosas: visita.tieneSospechosas || evidencia.esSospechosa,
        estado: visita.estado === 'pendiente' ? 'en_progreso' : visita.estado,
        iniciadaEn: visita.iniciadaEn
          ? aTimestamp(visita.iniciadaEn)
          : aTimestamp(evidencia.timestampServidor),
        actualizadoEn: aTimestamp(new Date()),
        ...(evidencia.esSospechosa && visita.revisionAlerta.estado !== 'pendiente'
          ? {
              revisionAlerta: {
                estado: 'pendiente',
                nota: null,
                revisadaPorId: null,
                revisadaPorNombre: null,
                revisadaEn: null,
              },
            }
          : {}),
      })

      return { evidencia, yaExistia: false }
    })
  }
}

/** Cliente Firestore. Respeta `FIRESTORE_EMULATOR_HOST` si está definido. */
export function crearFirestore(projectId?: string): Firestore {
  return new Firestore(projectId ? { projectId } : {})
}
