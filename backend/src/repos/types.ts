import type { Evidencia, Rol, Sitio, Usuario, Visita, EstadoVisita } from '../domain/types'

/**
 * Puertos de persistencia. Existen dos implementaciones y las dos se usan de verdad:
 * `firestore.ts` en ejecución real y `memory.ts` en la suite de tests, que corre sin
 * emulador ni credenciales. Sin este corte, testear la lógica antifraude exigiría
 * levantar Java + firebase-tools en cada máquina y en CI.
 *
 * Regla: ninguna capa fuera de `repos/` importa `firebase-admin`.
 */

export interface FiltroVisitas {
  supervisorId?: string
  siteId?: string
  siteClienteId?: string
  estado?: EstadoVisita
  soloSospechosas?: boolean
  desde?: Date
  hasta?: Date
  limite: number
  /** Opaco para quien llama. Formato interno: `<epochMillis>_<id>`. */
  cursor?: string
}

export interface PaginaVisitas {
  items: Visita[]
  siguienteCursor: string | null
}

export interface CreacionIdempotente<T> {
  recurso: T
  yaExistia: boolean
}

export interface UsuarioRepo {
  obtener(id: string): Promise<Usuario | null>
  porEmail(email: string): Promise<Usuario | null>
  listar(filtro: { rol?: Rol }): Promise<Usuario[]>
  crear(usuario: Usuario): Promise<Usuario>
  actualizar(id: string, cambios: Partial<Usuario>): Promise<Usuario>
}

export interface SitioRepo {
  obtener(id: string): Promise<Sitio | null>
  listar(filtro: { clienteId?: string; activo?: boolean }): Promise<Sitio[]>
  crear(sitio: Sitio): Promise<Sitio>
  obtenerIdempotente(id: string, huella: string): Promise<Sitio | null>
  crearIdempotente(sitio: Sitio, huella: string): Promise<CreacionIdempotente<Sitio>>
  actualizar(id: string, cambios: Partial<Sitio>): Promise<Sitio>
}

export interface VisitaRepo {
  obtener(id: string): Promise<Visita | null>
  listar(filtro: FiltroVisitas): Promise<PaginaVisitas>
  crear(visita: Visita): Promise<Visita>
  obtenerIdempotente(id: string, huella: string): Promise<Visita | null>
  crearIdempotente(visita: Visita, huella: string): Promise<CreacionIdempotente<Visita>>
  actualizar(id: string, cambios: Partial<Visita>): Promise<Visita>
}

export interface EvidenciaRepo {
  porVisita(visitId: string): Promise<Evidencia[]>
  porVisitas(visitIds: string[]): Promise<Evidencia[]>

  /**
   * Idempotencia: el mismo UUID de dispositivo no debe crear dos evidencias.
   *
   * Va acotada por visita **y** por supervisor a propósito. Con una búsqueda global, un
   * supervisor que conociera el `clienteEvidenciaId` de un compañero lo mandaría en su
   * propia visita y recibiría de vuelta la evidencia ajena entera — hash incluido, que es
   * justo lo que necesita para fabricar un duplicado que pase el filtro.
   */
  porClienteEvidenciaId(
    clienteEvidenciaId: string,
    visitId: string,
    supervisorId: string,
  ): Promise<Evidencia | null>

  /**
   * Reserva un hash de imagen de forma atómica y dice si ya pertenecía a otra visita.
   *
   * Existe porque comprobar duplicados con una consulta normal deja una ventana de
   * carrera: dos subidas simultáneas de la misma foto a visitas distintas consultan
   * antes de que la otra exista, ninguna ve nada, y las dos entran sin marcar. Al tocar
   * documentos de visita diferentes, tampoco hay contención que fuerce un reintento.
   *
   * Reservar un documento determinista por hash serializa esa decisión: el segundo en
   * llegar encuentra el del primero. Repetir la misma foto dentro de la **misma** visita
   * no cuenta como duplicado — fotografiar dos veces el mismo punto es legítimo.
   *
   * **Reserva huérfana**: si la evidencia falla *después* de reservar (por ejemplo, se
   * cae la subida a Storage), el hash queda reservado a una visita sin evidencia. Las
   * consecuencias son asimétricas y por eso se acepta:
   *
   * - Un reintento **de la misma visita** ve su propio `visitId` y no se marca. Correcto.
   * - Otra visita que subiera esa misma foto se marcaría como duplicada sin que exista la
   *   original. Es un falso positivo, pero exige que dos visitas distintas usen la misma
   *   imagen —que es exactamente el fraude que se busca— y el error cae del lado de
   *   marcar de más, que el admin puede descartar mirando la foto.
   *
   * Limpiar la reserva en el camino de error costaría una escritura extra en cada fallo y
   * abriría su propia carrera. No compensa.
   */
  reservarHash(hash: string, visitId: string): Promise<{ esDuplicado: boolean }>
}

export interface Db {
  usuarios: UsuarioRepo
  sitios: SitioRepo
  visitas: VisitaRepo
  evidencias: EvidenciaRepo

  /**
   * Crea la evidencia y actualiza los contadores denormalizados de la visita en una
   * sola transacción: `evidenciaCount`, `tieneSospechosas`, y el paso a
   * `en_progreso` + `iniciadaEn` si es la primera.
   *
   * Va junto porque la invariante 3 del esquema (`tieneSospechosas` == OR de las
   * evidencias) sólo se sostiene si ambas escrituras son atómicas. Devuelve la
   * evidencia ya existente si `clienteEvidenciaId` se repite.
   */
  registrarEvidencia(
    evidencia: Evidencia,
  ): Promise<{ evidencia: Evidencia; yaExistia: boolean }>
}
