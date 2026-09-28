/**
 * Tipos del dominio. Espejo de `contracts/firestore.schema.md` y `contracts/openapi.yaml`.
 *
 * Convención de tiempo: dentro del servidor todo instante viaja como `Date`.
 * La conversión a `Timestamp` de Firestore ocurre en `repos/firestore.ts`, y la
 * conversión a ISO-8601 en `routes/_mappers.ts`. Ninguna otra capa hace conversiones.
 */

export type Rol = 'admin' | 'supervisor'

export type EstadoVisita = 'pendiente' | 'en_progreso' | 'completada'

export type EstadoRevisionAlerta = 'pendiente' | 'revisada' | 'justificada' | 'escalada'

/** Memoria administrativa del caso. Nunca se proyecta en DTO de supervisor. */
export interface RevisionAlerta {
  estado: EstadoRevisionAlerta
  nota: string | null
  revisadaPorId: string | null
  revisadaPorNombre: string | null
  revisadaEn: Date | null
}

export type RespuestaChecklist = 'si' | 'no' | 'na'

export type VeredictoIntegridad = 'CONFIABLE' | 'NO_CONFIABLE' | 'NO_EVALUADO'

export const MOTIVOS_ALERTA = [
  'FUERA_DE_GEOFENCE',
  'IMAGEN_DUPLICADA',
  'DISPOSITIVO_NO_CONFIABLE',
  'MOCK_LOCATION',
  'PRECISION_GPS_BAJA',
  'DISPOSITIVO_NO_VINCULADO',
  'HASH_NO_COINCIDE',
  'INTEGRIDAD_NO_EVALUADA',
  'TOKEN_INTEGRIDAD_REUTILIZADO',
  'TOKEN_INTEGRIDAD_CADUCADO',
  'CHECKLIST_ALTERADO',
  'SIN_UBICACION',
] as const

export type MotivoAlerta = (typeof MOTIVOS_ALERTA)[number]

/** Texto que ve el admin en el dashboard. Uno por motivo, sin excepción. */
export const DESCRIPCION_MOTIVO: Record<MotivoAlerta, string> = {
  FUERA_DE_GEOFENCE:
    'La foto se tomó fuera del radio permitido alrededor de la sede.',
  IMAGEN_DUPLICADA:
    'Esta imagen ya se había subido en otra visita. Posible reutilización de evidencia.',
  DISPOSITIVO_NO_CONFIABLE:
    'Play Integrity reporta un dispositivo comprometido (root, bootloader abierto o app modificada).',
  MOCK_LOCATION:
    'Android reportó la ubicación como simulada. Hay una app de GPS falso activa.',
  PRECISION_GPS_BAJA:
    'La precisión del GPS es peor que el radio de la sede: no se puede afirmar ni descartar que estuvo dentro.',
  DISPOSITIVO_NO_VINCULADO:
    'La evidencia llegó desde un dispositivo distinto al registrado para este supervisor.',
  HASH_NO_COINCIDE:
    'Los bytes recibidos no coinciden con el hash que declaró la app. Imagen alterada en tránsito o cliente modificado.',
  INTEGRIDAD_NO_EVALUADA:
    'No llegó token de Play Integrity, así que no se pudo verificar el dispositivo.',
  TOKEN_INTEGRIDAD_REUTILIZADO:
    'El token de Play Integrity no corresponde a esta foto ni a esta visita: se obtuvo para otra cosa y se está reutilizando aquí.',
  TOKEN_INTEGRIDAD_CADUCADO:
    'El token de Play Integrity se emitió fuera de la ventana de validez. Puede ser una subida muy demorada o un token guardado para reutilizarlo.',
  CHECKLIST_ALTERADO:
    'El checklist enviado no coincide con el que se asignó a la visita.',
  SIN_UBICACION:
    'El dispositivo no pudo determinar la ubicación al tomar la foto. No prueba presencia, pero tampoco la desmiente.',
}

export interface ChecklistItem {
  id: string
  pregunta: string
  respuesta: RespuestaChecklist | null
  nota: string | null
}

export interface Usuario {
  id: string
  nombre: string
  email: string
  rol: Rol
  deviceId: string | null
  activo: boolean
  creadoEn: Date
  actualizadoEn: Date
}

export interface Sitio {
  id: string
  clienteId: string
  nombreCliente: string
  nombreSede: string
  direccion: string
  lat: number
  lng: number
  radioPermitidoMetros: number
  activo: boolean
  creadoEn: Date
  actualizadoEn: Date
}

export interface Visita {
  id: string
  siteId: string
  siteClienteId: string
  siteNombreCliente: string
  siteNombreSede: string
  siteDireccion: string
  siteLat: number
  siteLng: number
  /** Snapshot: cambiar el radio del sitio no reescribe veredictos ya emitidos. */
  siteRadioPermitidoMetros: number
  supervisorId: string
  supervisorNombre: string
  estado: EstadoVisita
  fechaAsignada: Date
  iniciadaEn: Date | null
  completadaEn: Date | null
  observaciones: string | null
  checklist: ChecklistItem[]
  evidenciaCount: number
  /** Denormalizado: Firestore no hace joins y el filtro del admin depende de esto. */
  tieneSospechosas: boolean
  revisionAlerta: RevisionAlerta
  creadoEn: Date
  actualizadoEn: Date
}

export interface ResultadoIntegridad {
  veredicto: VeredictoIntegridad
  deviceRecognition: string[]
  appRecognition: string | null
  motivo: string | null
}

export interface Evidencia {
  id: string
  visitId: string
  siteId: string
  supervisorId: string
  /** Clave de idempotencia generada en el dispositivo. */
  clienteEvidenciaId: string
  storagePath: string
  storageUrl: string
  /** Calculado por el servidor sobre los bytes recibidos. */
  hashSHA256: string
  hashClienteReportado: string | null
  /** `null` si el dispositivo no consiguió ubicación al capturar. */
  lat: number | null
  lng: number | null
  precisionMetros: number | null
  /** `null` cuando no hay ubicación: no se inventa una distancia. */
  distanciaAlSitioMetros: number | null
  timestampServidor: Date
  timestampCliente: Date | null
  mockLocationReportada: boolean
  integrity: ResultadoIntegridad
  esSospechosa: boolean
  motivosAlerta: MotivoAlerta[]
  checklistSnapshot: ChecklistItem[] | null
  creadoEn: Date
}

/** Identidad resuelta desde el ID token + `users/{uid}`. Se cuelga de `req.actor`. */
export interface Actor {
  uid: string
  email: string | null
  usuario: Usuario
}
