import { Router } from 'express'
import multer from 'multer'
import type { Contenedor } from '../container'
import {
  completarVisitaSchema,
  evidenciaFormSchema,
  listarVisitasQuerySchema,
} from '../domain/schemas'
import type { ChecklistItem, Visita } from '../domain/types'
import { conflicto, noEncontrado, prohibido, solicitudInvalida } from '../lib/errors'
import { paramTexto } from '../lib/http'
import { actorDe } from '../middleware/auth'
import { checklistCoincide } from '../services/antifraude'
import {
  evidenciaConfirmacionDto,
  visitaDetalleSupervisorDto,
  visitaResumenSupervisorDto,
} from './mappers'

/**
 * Rutas del supervisor.
 *
 * El admin puede **leer** las visitas de cualquiera desde aquí, pero no puede subir
 * evidencia ni cerrarlas: esas dos operaciones exigen rol supervisor y asignación. Si un
 * admin pudiera subir, la evidencia quedaría con su uid como `supervisorId` —
 * contradiciendo al supervisor asignado y rompiendo la atribución sobre la que se apoya
 * todo el modelo antifraude.
 */
export function rutasVisitas(c: Contenedor): Router {
  const router = Router()

  // La foto va a memoria, no a disco: Cloud Run tiene sistema de archivos efímero y el
  // buffer se necesita entero de todas formas para hashearlo antes de subirlo.
  const subida = multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: c.env.MAX_FOTO_BYTES, files: 1, fields: 20 },
  })

  router.get('/', async (req, res) => {
    const actor = actorDe(req)
    const q = listarVisitasQuerySchema.parse(req.query)

    // Un supervisor sólo ve lo suyo. Si pide el buzón de otro, 403 — no una lista vacía:
    // fallar en silencio esconde bugs de cliente y de permisos por igual.
    let supervisorId = q.supervisorId
    if (actor.usuario.rol === 'supervisor') {
      if (supervisorId && supervisorId !== actor.uid) {
        throw prohibido('Sólo puedes consultar tus propias visitas.')
      }
      supervisorId = actor.uid
    }

    const pagina = await c.db.visitas.listar({
      ...(supervisorId ? { supervisorId } : {}),
      ...(q.estado ? { estado: q.estado } : {}),
      ...(q.desde ? { desde: q.desde } : {}),
      ...(q.hasta ? { hasta: q.hasta } : {}),
      ...(q.cursor ? { cursor: q.cursor } : {}),
      limite: q.limite,
    })

    res.json({
      items: pagina.items.map(visitaResumenSupervisorDto),
      siguienteCursor: pagina.siguienteCursor,
    })
  })

  router.get('/:id', async (req, res) => {
    const actor = actorDe(req)
    const visita = await cargarVisitaAccesible(c, paramTexto(req, 'id'), actor.uid, actor.usuario.rol)
    const evidencias = await c.db.evidencias.porVisita(visita.id)
    res.json(visitaDetalleSupervisorDto(visita, evidencias))
  })

  router.post('/:id/evidence', subida.single('foto'), async (req, res) => {
    const actor = actorDe(req)
    if (!req.file) throw solicitudInvalida('Falta el archivo `foto`.')

    const visita = await cargarVisitaAccesible(c, paramTexto(req, 'id'), actor.uid, actor.usuario.rol)
    exigirSupervisorAsignado(actor, visita, 'subir evidencia a')

    const form = evidenciaFormSchema.parse(req.body)
    const { evidencia, creada } = await c.evidencia.registrar({
      visita,
      actor,
      foto: { buffer: req.file.buffer, mimetype: req.file.mimetype },
      form,
      deviceId: req.deviceId ?? null,
    })

    // 201 la primera vez, 200 en un reenvío idempotente. El cliente distingue así si debe
    // contar la evidencia o si ya la tenía contada. El cuerpo es sólo confirmación: el
    // veredicto antifraude no viaja al supervisor (ver `mappers.ts`).
    res.status(creada ? 201 : 200).json(evidenciaConfirmacionDto(evidencia))
  })

  router.patch('/:id/complete', async (req, res) => {
    const actor = actorDe(req)
    const cuerpo = completarVisitaSchema.parse(req.body)
    const visita = await cargarVisitaAccesible(c, paramTexto(req, 'id'), actor.uid, actor.usuario.rol)
    exigirSupervisorAsignado(actor, visita, 'completar')

    // Idempotente: WorkManager reintenta, y un segundo cierre no debe ser un error.
    if (visita.estado === 'completada') {
      const evidencias = await c.db.evidencias.porVisita(visita.id)
      res.json(visitaDetalleSupervisorDto(visita, evidencias))
      return
    }

    if (visita.evidenciaCount === 0) {
      throw conflicto('No se puede cerrar una visita sin al menos una evidencia fotográfica.')
    }

    // El checklist enviado tiene que ser el asignado. Sin esta comprobación, un cliente
    // modificado manda el mismo número de ítems con otros ids y otras preguntas, todas
    // respondidas, y la visita se cierra guardando un checklist inventado — perdiendo
    // justo lo que el checklist debía probar.
    if (!checklistCoincide(cuerpo.checklist, visita.checklist)) {
      throw conflicto('El checklist enviado no corresponde al asignado a esta visita.')
    }

    const sinResponder = cuerpo.checklist.filter((i) => i.respuesta === null).map((i) => i.id)
    if (sinResponder.length > 0) {
      throw conflicto('El checklist tiene ítems sin responder.', { sinResponder })
    }

    const actualizada = await c.db.visitas.actualizar(visita.id, {
      estado: 'completada',
      completadaEn: new Date(), // sello del servidor, no del dispositivo
      checklist: fusionarChecklist(visita.checklist, cuerpo.checklist),
      observaciones: cuerpo.observaciones ?? null,
    })

    const evidencias = await c.db.evidencias.porVisita(visita.id)
    res.json(visitaDetalleSupervisorDto(actualizada, evidencias))
  })

  return router
}

/**
 * Conserva las preguntas del servidor y toma del cliente sólo respuesta y nota.
 *
 * El texto de cada pregunta es del servidor: si el cliente pudiera reescribirlo, el
 * checklist guardado diría lo que al supervisor le conviniera que dijera.
 */
function fusionarChecklist(
  asignado: readonly ChecklistItem[],
  enviado: readonly ChecklistItem[],
): ChecklistItem[] {
  const respuestas = new Map(enviado.map((i) => [i.id, i]))
  return asignado.map((item) => ({
    ...item,
    respuesta: respuestas.get(item.id)?.respuesta ?? null,
    nota: respuestas.get(item.id)?.nota ?? null,
  }))
}

/**
 * Escribir sobre una visita exige ser el supervisor asignado.
 *
 * El admin queda fuera a propósito: una evidencia subida por un admin llevaría su uid
 * como `supervisorId`, contradiciendo al supervisor de la visita. Si algún día hace falta
 * intervención administrativa, será una operación aparte y auditada, no ésta disfrazada.
 */
function exigirSupervisorAsignado(
  actor: { uid: string; usuario: { rol: string } },
  visita: Visita,
  accion: string,
): void {
  if (actor.usuario.rol !== 'supervisor') {
    throw prohibido(`Sólo el supervisor asignado puede ${accion} esta visita.`)
  }
  if (visita.supervisorId !== actor.uid) {
    throw prohibido('Esta visita no está asignada a ti.')
  }
}

/**
 * Carga una visita comprobando acceso de lectura. Devuelve 404 (no 403) cuando un
 * supervisor pide una visita ajena: distinguir "no existe" de "existe pero no es tuya" le
 * permitiría enumerar identificadores de visitas de sus compañeros.
 */
async function cargarVisitaAccesible(
  c: Contenedor,
  id: string,
  uid: string,
  rol: string,
): Promise<Visita> {
  const visita = await c.db.visitas.obtener(id)
  if (!visita) throw noEncontrado('Visita no encontrada.')
  if (rol !== 'admin' && visita.supervisorId !== uid) throw noEncontrado('Visita no encontrada.')
  return visita
}
