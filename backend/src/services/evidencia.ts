import { randomUUID } from 'node:crypto'
import type { EvidenciaFormInput } from '../domain/schemas'
import type { Actor, Evidencia, Visita } from '../domain/types'
import { conflicto, solicitudInvalida } from '../lib/errors'
import { distanciaHaversineMetros, esCoordenadaValida } from '../lib/geo'
import { hashCoincide, sha256Hex } from '../lib/hash'
import { logger } from '../lib/logger'
import { calcularNonceEsperado } from '../lib/nonce'
import type { Db } from '../repos/types'
import { checklistCoincide, esJpeg, evaluarAlertas } from './antifraude'
import type { ServicioIntegridad } from './integrity'
import type { ServicioAlmacenamiento } from './storage'

/**
 * Orquesta el registro de una evidencia: mide los hechos, aplica las reglas de
 * `antifraude.ts` y persiste.
 *
 * El orden de las operaciones es parte del diseño, no casualidad:
 *
 * 1. Validaciones baratas y locales (formato, coordenada). Una petición mal formada no
 *    llega a gastar ni una lectura.
 * 2. Idempotencia acotada a esta visita y este supervisor.
 * 3. Hash de los bytes recibidos — necesario antes de nada más, porque el nonce del
 *    token de integridad se calcula sobre él.
 * 4. Reserva atómica del hash, que es lo que serializa la detección de duplicados.
 * 5. Integridad y distancia.
 * 6. Subida a Storage, la última: así una petición rechazada no deja un objeto huérfano
 *    en el bucket.
 */
export class ServicioEvidencia {
  constructor(
    private readonly db: Db,
    private readonly almacenamiento: ServicioAlmacenamiento,
    private readonly integridad: ServicioIntegridad,
  ) {}

  async registrar(params: {
    visita: Visita
    actor: Actor
    foto: { buffer: Buffer; mimetype: string }
    form: EvidenciaFormInput
    /** `X-Device-Id` de la petición. `null` si el cliente no lo mandó. */
    deviceId: string | null
  }): Promise<{ evidencia: Evidencia; creada: boolean }> {
    const { visita, actor, foto, form } = params

    if (visita.estado === 'completada') {
      throw conflicto('La visita ya está completada y no admite más evidencias.')
    }
    if (!esJpeg(foto.buffer)) {
      throw solicitudInvalida('La foto debe ser un JPEG capturado por la app.')
    }
    // La ubicación puede faltar por completo (sin señal, permiso revocado a mitad); lo
    // que no se acepta es una coordenada presente pero imposible.
    const hayUbicacion = form.lat != null && form.lng != null
    if (hayUbicacion && !esCoordenadaValida({ lat: form.lat!, lng: form.lng! })) {
      throw solicitudInvalida('Coordenadas fuera de rango.')
    }

    // Reenvío de WorkManager tras un timeout que en realidad sí se procesó: se devuelve
    // la evidencia original en vez de duplicarla. Se comprueba aquí, antes de gastar la
    // subida a Storage, y otra vez dentro de la transacción.
    const previa = await this.db.evidencias.porClienteEvidenciaId(
      form.clienteEvidenciaId,
      visita.id,
      actor.uid,
    )
    if (previa) return { evidencia: previa, creada: false }

    const hashSHA256 = sha256Hex(foto.buffer)

    const distanciaAlSitioMetros = hayUbicacion
      ? distanciaHaversineMetros(
          { lat: form.lat!, lng: form.lng! },
          { lat: visita.siteLat, lng: visita.siteLng },
        )
      : null

    // El nonce se calcula sobre el hash que el servidor midió, no sobre el que declaró el
    // cliente: así el token queda atado a esta foto exacta y no a la que el cliente diga.
    const nonceEsperado = calcularNonceEsperado({
      visitId: visita.id,
      clienteEvidenciaId: form.clienteEvidenciaId,
      hashSHA256,
    })

    const [evaluacion, reserva] = await Promise.all([
      this.integridad.evaluar({ token: form.integrityToken, nonceEsperado }),
      this.db.evidencias.reservarHash(hashSHA256, visita.id),
    ])

    const motivosAlerta = evaluarAlertas({
      distanciaMetros: distanciaAlSitioMetros,
      radioPermitidoMetros: visita.siteRadioPermitidoMetros,
      precisionMetros: form.precisionMetros ?? null,
      mockLocationReportada: form.mockLocationReportada,
      integridad: evaluacion.resultado,
      hayDuplicadoDeHash: reserva.esDuplicado,
      hashCoincideConDeclarado: hashCoincide(hashSHA256, form.hashClienteReportado),
      dispositivoVinculado: dispositivoVinculado(actor, params.deviceId),
      tokenIntegridadReutilizado: evaluacion.nonceNoCoincide,
      tokenIntegridadCaducado: evaluacion.caducado,
      // Un checklist ausente no es alteración: la evidencia puede subirse antes de
      // responder nada. Sólo se compara si el cliente mandó uno.
      checklistAlterado: form.checklist
        ? !checklistCoincide(form.checklist, visita.checklist)
        : false,
    })

    const id = randomUUID()
    const timestampServidor = new Date() // única fuente temporal confiable
    const ruta = `evidences/${visita.id}/${id}.jpg`

    const guardado = await this.almacenamiento.guardar({
      ruta,
      bytes: foto.buffer,
      contentType: foto.mimetype || 'image/jpeg',
      // Metadata en el objeto: si algún día se pierde Firestore, el bucket todavía dice a
      // qué visita pertenece cada foto y con qué hash se registró.
      metadata: {
        visitId: visita.id,
        siteId: visita.siteId,
        supervisorId: actor.uid,
        hashSHA256,
        timestampServidor: timestampServidor.toISOString(),
      },
    })

    const evidencia: Evidencia = {
      id,
      visitId: visita.id,
      siteId: visita.siteId,
      supervisorId: actor.uid,
      clienteEvidenciaId: form.clienteEvidenciaId,
      storagePath: guardado.storagePath,
      storageUrl: guardado.storageUrl,
      hashSHA256,
      hashClienteReportado: form.hashClienteReportado ?? null,
      lat: form.lat ?? null,
      lng: form.lng ?? null,
      precisionMetros: form.precisionMetros ?? null,
      distanciaAlSitioMetros:
        distanciaAlSitioMetros === null ? null : Math.round(distanciaAlSitioMetros * 10) / 10,
      timestampServidor,
      timestampCliente: form.capturadoEn ?? null,
      mockLocationReportada: form.mockLocationReportada,
      integrity: evaluacion.resultado,
      esSospechosa: motivosAlerta.length > 0,
      motivosAlerta,
      // El checklist se guarda tal como llegó incluso si está alterado: es la prueba de
      // qué mandó el cliente, y borrarla destruiría la evidencia del propio fraude.
      checklistSnapshot: form.checklist ?? null,
      creadoEn: timestampServidor,
    }

    const resultado = await this.db.registrarEvidencia(evidencia)

    if (resultado.evidencia.esSospechosa && !resultado.yaExistia) {
      logger.warn('Evidencia marcada como sospechosa', {
        evidenceId: resultado.evidencia.id,
        visitId: visita.id,
        supervisorId: actor.uid,
        motivos: resultado.evidencia.motivosAlerta,
        distanciaMetros: resultado.evidencia.distanciaAlSitioMetros,
        radioPermitidoMetros: visita.siteRadioPermitidoMetros,
      })
    }

    return { evidencia: resultado.evidencia, creada: !resultado.yaExistia }
  }
}

/**
 * `false` sólo cuando hay contradicción demostrable: el supervisor tiene un dispositivo
 * registrado, el cliente mandó el suyo, y no son el mismo. Sin dato registrado o sin
 * header no se alerta — ausencia de evidencia no es evidencia.
 */
function dispositivoVinculado(actor: Actor, deviceId: string | null): boolean {
  const registrado = actor.usuario.deviceId
  if (!registrado || !deviceId) return true
  return registrado === deviceId
}
