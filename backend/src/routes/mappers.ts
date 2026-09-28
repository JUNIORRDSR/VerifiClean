import {
  DESCRIPCION_MOTIVO,
  type Evidencia,
  type MotivoAlerta,
  type Sitio,
  type Usuario,
  type Visita,
} from '../domain/types'

/**
 * Dominio → JSON de la API. Única capa que convierte `Date` a ISO-8601.
 *
 * ## El corte entre la vista del supervisor y la del admin es antifraude, no comodidad
 *
 * `docs/ANTIFRAUD.md` dice que el servidor **marca en silencio**: nunca rechaza por
 * sospecha, porque devolver un 4xx convertiría la API en un oráculo donde el defraudador
 * ajusta un parámetro y reintenta hasta pasar.
 *
 * Responder 201 pero incluir `esSospechosa` y `motivosAlerta` en el cuerpo es
 * exactamente el mismo oráculo con otra ropa: el supervisor prueba una ubicación, lee
 * "FUERA_DE_GEOFENCE", se acerca cien metros y repite hasta que la respuesta sale limpia.
 * Lo mismo con `distanciaAlSitioMetros`, que le dice cuánto le falta, y con
 * `tieneSospechosas` en la lista de visitas.
 *
 * Por eso el supervisor recibe **sólo confirmación de registro**: que la evidencia entró
 * y a qué hora según el servidor. Todo veredicto —flags, motivos, distancias, hashes,
 * integridad y URLs de foto— vive únicamente bajo `/v1/admin`.
 */

const iso = (d: Date): string => d.toISOString()
const isoOpcional = (d: Date | null): string | null => (d ? d.toISOString() : null)

export const usuarioDto = (u: Usuario) => ({
  id: u.id,
  nombre: u.nombre,
  email: u.email,
  rol: u.rol,
  activo: u.activo,
  deviceId: u.deviceId,
  creadoEn: iso(u.creadoEn),
})

/**
 * Perfil devuelto al iniciar sesión. No reutiliza `usuarioDto`: ese DTO administrativo
 * contiene el `deviceId` vinculado y permitiría al supervisor compararlo con el suyo para
 * deducir si sus evidencias activarán `DISPOSITIVO_NO_VINCULADO`.
 */
export const usuarioSesionDto = (u: Usuario) => ({
  id: u.id,
  nombre: u.nombre,
  email: u.email,
  rol: u.rol,
  activo: u.activo,
  creadoEn: iso(u.creadoEn),
})

export const sitioDto = (s: Sitio) => ({
  id: s.id,
  clienteId: s.clienteId,
  nombreCliente: s.nombreCliente,
  nombreSede: s.nombreSede,
  direccion: s.direccion,
  lat: s.lat,
  lng: s.lng,
  radioPermitidoMetros: s.radioPermitidoMetros,
  activo: s.activo,
  creadoEn: iso(s.creadoEn),
})

// ------------------------------------------------------------- vista del admin

export const visitaResumenDto = (v: Visita) => ({
  id: v.id,
  siteId: v.siteId,
  siteClienteId: v.siteClienteId,
  siteNombreCliente: v.siteNombreCliente,
  siteNombreSede: v.siteNombreSede,
  supervisorId: v.supervisorId,
  supervisorNombre: v.supervisorNombre,
  estado: v.estado,
  fechaAsignada: iso(v.fechaAsignada),
  completadaEn: isoOpcional(v.completadaEn),
  evidenciaCount: v.evidenciaCount,
  tieneSospechosas: v.tieneSospechosas,
  revisionAlerta: revisionAlertaDto(v),
})

const revisionAlertaDto = (v: Visita) => ({
  estado: v.revisionAlerta.estado,
  nota: v.revisionAlerta.nota,
  revisadaPorId: v.revisionAlerta.revisadaPorId,
  revisadaPorNombre: v.revisionAlerta.revisadaPorNombre,
  revisadaEn: isoOpcional(v.revisionAlerta.revisadaEn),
})

/** Vista completa de una evidencia. Sólo admin. `fotoUrl` llega firmada y caduca. */
export const evidenciaDto = (e: Evidencia, fotoUrl: string | null = null) => ({
  id: e.id,
  visitId: e.visitId,
  siteId: e.siteId,
  supervisorId: e.supervisorId,
  fotoUrl,
  hashSHA256: e.hashSHA256,
  lat: e.lat,
  lng: e.lng,
  precisionMetros: e.precisionMetros,
  distanciaAlSitioMetros: e.distanciaAlSitioMetros,
  timestampServidor: iso(e.timestampServidor),
  timestampCliente: isoOpcional(e.timestampCliente),
  mockLocationReportada: e.mockLocationReportada,
  esSospechosa: e.esSospechosa,
  motivosAlerta: e.motivosAlerta,
  integrity: e.integrity,
})

// -------------------------------------------------------- vista del supervisor

/**
 * Lo único que el supervisor necesita saber: que la evidencia quedó registrada y con qué
 * hora de servidor. Ni veredicto, ni distancia, ni hash.
 */
export const evidenciaConfirmacionDto = (e: Evidencia) => ({
  id: e.id,
  visitId: e.visitId,
  timestampServidor: iso(e.timestampServidor),
})

/** Resumen de visita sin el flag de sospecha. */
export const visitaResumenSupervisorDto = (v: Visita) => ({
  id: v.id,
  siteId: v.siteId,
  siteClienteId: v.siteClienteId,
  siteNombreCliente: v.siteNombreCliente,
  siteNombreSede: v.siteNombreSede,
  supervisorId: v.supervisorId,
  supervisorNombre: v.supervisorNombre,
  estado: v.estado,
  fechaAsignada: iso(v.fechaAsignada),
  completadaEn: isoOpcional(v.completadaEn),
  evidenciaCount: v.evidenciaCount,
})

/**
 * Detalle para el supervisor. **Sin `siteRadioPermitidoMetros`**, a propósito.
 *
 * Ese número es el umbral con el que se le va a juzgar. Saber que la geocerca son 150 m
 * le dice exactamente cuánto puede alejarse antes de que la evidencia salga marcada, que
 * es el mismo oráculo que devolverle `motivosAlerta`. Quitarlo de la pantalla no bastaba:
 * el valor seguía viajando en el JSON y se lee con cualquier proxy.
 *
 * La sede sí lleva dirección y coordenadas: el supervisor tiene que llegar hasta ella, y
 * saber dónde está no le dice nada sobre el veredicto.
 */
export const visitaDetalleSupervisorDto = (v: Visita, evidencias: Evidencia[]) => ({
  ...visitaResumenSupervisorDto(v),
  siteDireccion: v.siteDireccion,
  siteLat: v.siteLat,
  siteLng: v.siteLng,
  iniciadaEn: isoOpcional(v.iniciadaEn),
  observaciones: v.observaciones,
  checklist: v.checklist,
  evidencias: evidencias.map(evidenciaConfirmacionDto),
})

// -------------------------------------------------- detalle completo del admin

const visitaDetalleBaseDto = (v: Visita) => ({
  ...visitaResumenDto(v),
  siteDireccion: v.siteDireccion,
  siteLat: v.siteLat,
  siteLng: v.siteLng,
  siteRadioPermitidoMetros: v.siteRadioPermitidoMetros,
  iniciadaEn: isoOpcional(v.iniciadaEn),
  observaciones: v.observaciones,
  checklist: v.checklist,
})

/** Agrega los motivos de todas las evidencias de una visita, con su conteo. */
export function agregarAlertas(evidencias: Evidencia[]) {
  const conteo = new Map<MotivoAlerta, number>()
  for (const e of evidencias) {
    for (const m of e.motivosAlerta) conteo.set(m, (conteo.get(m) ?? 0) + 1)
  }
  return [...conteo.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([motivo, cantidad]) => ({
      motivo,
      conteo: cantidad,
      descripcion: DESCRIPCION_MOTIVO[motivo],
    }))
}

export const visitaDetalleAdminDto = (
  v: Visita,
  evidencias: Evidencia[],
  urls: Map<string, string | null>,
) => ({
  ...visitaDetalleBaseDto(v),
  evidencias: evidencias.map((e) => ({
    id: e.id,
    timestampServidor: iso(e.timestampServidor),
    distanciaAlSitioMetros: e.distanciaAlSitioMetros,
    esSospechosa: e.esSospechosa,
  })),
  evidenciasCompletas: evidencias.map((e) => evidenciaDto(e, urls.get(e.id) ?? null)),
  alertas: agregarAlertas(evidencias),
})
