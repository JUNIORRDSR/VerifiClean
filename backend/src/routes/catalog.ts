import { Router } from 'express'
import type { Contenedor } from '../container'
import { nuevoChecklistPorDefecto } from '../domain/checklist'
import {
  cambioActivoSchema,
  idempotencyKeyHeadersSchema,
  listarSitiosQuerySchema,
  listarUsuariosQuerySchema,
  sitioNuevoSchema,
  usuarioNuevoSchema,
  visitaNuevaSchema,
} from '../domain/schemas'
import type { Sitio, Usuario, Visita } from '../domain/types'
import { conflicto, solicitudInvalida, noEncontrado } from '../lib/errors'
import { huellaIdempotencia } from '../lib/idempotency'
import { logger } from '../lib/logger'
import { actorDe, exigirAdmin } from '../middleware/auth'
import { sitioDto, usuarioDto, visitaDetalleSupervisorDto } from './mappers'

/**
 * Alta de sedes, usuarios y asignación de visitas. Sólo admin.
 *
 * `exigirAdmin` va ruta por ruta, no en el montaje: este router cuelga de `/v1`, que
 * es también el prefijo de las rutas del supervisor. Colgarlo del montaje haría que
 * el middleware corriera para cualquier `/v1/*` — incluida la bandeja del supervisor —
 * y devolviera 403 antes de que el enrutado llegara a decidir nada.
 */
export function rutasCatalogo(c: Contenedor): Router {
  const router = Router()

  // ------------------------------------------------------------------ sites

  router.get('/sites', exigirAdmin, async (req, res) => {
    const q = listarSitiosQuerySchema.parse(req.query)
    const sitios = await c.db.sitios.listar({
      ...(q.clienteId ? { clienteId: q.clienteId } : {}),
      ...(q.activo !== undefined ? { activo: q.activo } : {}),
    })
    res.json({ items: sitios.map(sitioDto) })
  })

  router.post('/sites', exigirAdmin, async (req, res) => {
    const datos = sitioNuevoSchema.parse(req.body)
    const { idempotencyKey } = idempotencyKeyHeadersSchema.parse({
      idempotencyKey: req.get('Idempotency-Key'),
    })
    const huella = huellaIdempotencia(datos)
    const repetida = await c.db.sitios.obtenerIdempotente(idempotencyKey, huella)
    if (repetida) {
      res.status(200).json(sitioDto(repetida))
      return
    }

    // El cliente está denormalizado en cada sede. Permitir que un typo cree otro id o
    // cambie el nombre parte el reporte facturable en dos, así que la invariante se
    // protege también en servidor y no sólo en el selector Android.
    const sitiosExistentes = await c.db.sitios.listar({})
    const nombreNormalizado = normalizarNombreCliente(datos.nombreCliente)
    const mismoId = sitiosExistentes.find((s) => s.clienteId === datos.clienteId)
    if (mismoId && normalizarNombreCliente(mismoId.nombreCliente) !== nombreNormalizado) {
      throw conflicto(
        `Ese cliente ya está registrado como "${mismoId.nombreCliente}". Elígelo en la lista existente.`,
      )
    }
    const mismoNombreOtroId = sitiosExistentes.find(
      (s) =>
        s.clienteId !== datos.clienteId &&
        normalizarNombreCliente(s.nombreCliente) === nombreNormalizado,
    )
    if (mismoNombreOtroId) {
      throw conflicto(
        `Ya existe el cliente "${mismoNombreOtroId.nombreCliente}". Elígelo en la lista existente para mantener unidos sus reportes.`,
      )
    }

    const ahora = new Date()
    const sitio: Sitio = {
      id: idempotencyKey,
      ...datos,
      activo: true,
      creadoEn: ahora,
      actualizadoEn: ahora,
    }
    const resultado = await c.db.sitios.crearIdempotente(sitio, huella)
    res.status(resultado.yaExistia ? 200 : 201).json(sitioDto(resultado.recurso))
  })

  /**
   * Baja lógica de una sede. No hay DELETE: las visitas ya realizadas la siguen citando y
   * borrarla dejaría huérfano el histórico que sirve para probar el cumplimiento.
   *
   * Desactivar tiene un solo efecto: `POST /visits/assign` la rechaza. Las visitas ya
   * asignadas siguen su curso, porque el supervisor puede estar de camino y cancelarle el
   * trabajo desde el servidor sin avisarle sería peor que la sede sobrante.
   */
  router.patch('/sites/:id', exigirAdmin, async (req, res) => {
    const { activo } = cambioActivoSchema.parse(req.body)
    const id = req.params.id as string

    const sitio = await c.db.sitios.obtener(id)
    if (!sitio) throw noEncontrado(`El sitio ${id} no existe.`)

    const actualizado = await c.db.sitios.actualizar(id, { activo })
    res.json(sitioDto(actualizado))
  })

  // ------------------------------------------------------------------ users

  router.get('/users', exigirAdmin, async (req, res) => {
    const q = listarUsuariosQuerySchema.parse(req.query)
    const usuarios = await c.db.usuarios.listar(q.rol ? { rol: q.rol } : {})
    res.json({ items: usuarios.map(usuarioDto) })
  })

  router.post('/users', exigirAdmin, async (req, res) => {
    const datos = usuarioNuevoSchema.parse(req.body)
    const email = datos.email.toLowerCase()

    const uid = await c.auth.crearUsuario({ email, password: datos.password, nombre: datos.nombre })

    const ahora = new Date()
    const usuario: Usuario = {
      id: uid,
      nombre: datos.nombre,
      email,
      rol: datos.rol,
      deviceId: null,
      activo: true,
      creadoEn: ahora,
      actualizadoEn: ahora,
    }

    try {
      await c.db.usuarios.crear(usuario)
    } catch (e) {
      // Cuenta en Firebase Auth sin documento en `users` = alguien que puede
      // autenticarse pero no tiene rol, y que además bloquea el email para siempre.
      // Se deshace la mitad que sí quedó hecha.
      logger.error('Fallo al crear el documento del usuario; revirtiendo en Firebase Auth', {
        uid,
        error: String(e),
      })
      await c.auth.eliminarUsuario(uid).catch((err) =>
        logger.error('No se pudo revertir el usuario en Firebase Auth', { uid, error: String(err) }),
      )
      throw e
    }

    res.status(201).json(usuarioDto(usuario))
  })

  /**
   * Baja lógica de un usuario. Es la única forma de cortarle el acceso a alguien que deja
   * la empresa: `exigirAuth` comprueba `activo` en cada petición, así que surte efecto en
   * la siguiente llamada sin esperar a que caduque su token.
   *
   * Su histórico no se toca. Las evidencias que subió siguen siendo válidas y las visitas
   * que cerró siguen contando para el cumplimiento del cliente: dar de baja a una persona
   * no es rehacer lo que hizo mientras trabajaba.
   */
  router.patch('/users/:id', exigirAdmin, async (req, res) => {
    const { activo } = cambioActivoSchema.parse(req.body)
    const id = req.params.id as string
    const actor = actorDe(req)

    // Sin esto, un admin distraído se cierra la puerta desde dentro: si además es el
    // único, no queda nadie que pueda reactivar a nadie y el sistema sólo se recupera
    // escribiendo a mano en Firestore.
    if (id === actor.usuario.id) {
      throw solicitudInvalida('No puedes cambiar el estado de tu propia cuenta.')
    }

    const usuario = await c.db.usuarios.obtener(id)
    if (!usuario) throw noEncontrado(`El usuario ${id} no existe.`)

    const actualizado = await c.db.usuarios.actualizar(id, { activo })
    logger.info('Cambio de estado de usuario', { id, activo, porAdmin: actor.usuario.id })
    res.json(usuarioDto(actualizado))
  })

  // --------------------------------------------------------- asignar visita

  router.post('/visits/assign', exigirAdmin, async (req, res) => {
    const datos = visitaNuevaSchema.parse(req.body)
    const { idempotencyKey } = idempotencyKeyHeadersSchema.parse({
      idempotencyKey: req.get('Idempotency-Key'),
    })
    const checklist = datos.checklist ?? nuevoChecklistPorDefecto()
    const huella = huellaIdempotencia({ ...datos, checklist })
    const repetida = await c.db.visitas.obtenerIdempotente(idempotencyKey, huella)
    if (repetida) {
      res.status(200).json(visitaDetalleSupervisorDto(repetida, []))
      return
    }

    const sitio = await c.db.sitios.obtener(datos.siteId)
    if (!sitio) throw noEncontrado(`El sitio ${datos.siteId} no existe.`)
    if (!sitio.activo) throw solicitudInvalida('No se pueden asignar visitas a una sede inactiva.')

    const supervisor = await c.db.usuarios.obtener(datos.supervisorId)
    if (!supervisor) throw noEncontrado(`El usuario ${datos.supervisorId} no existe.`)
    if (supervisor.rol !== 'supervisor') {
      throw solicitudInvalida('Sólo se pueden asignar visitas a usuarios con rol supervisor.')
    }
    if (!supervisor.activo) throw solicitudInvalida('El supervisor está inactivo.')

    const ahora = new Date()
    const visita: Visita = {
      id: idempotencyKey,
      siteId: sitio.id,
      // Denormalización: la app del supervisor lista visitas sin leer `sites`, y el
      // reporte filtra por cliente sin un join que Firestore no sabe hacer.
      siteClienteId: sitio.clienteId,
      siteNombreCliente: sitio.nombreCliente,
      siteNombreSede: sitio.nombreSede,
      siteDireccion: sitio.direccion,
      siteLat: sitio.lat,
      siteLng: sitio.lng,
      // Snapshot del radio: si mañana el admin lo cambia, los veredictos ya emitidos
      // sobre esta visita siguen siendo los que se evaluaron con el radio de hoy.
      siteRadioPermitidoMetros: sitio.radioPermitidoMetros,
      supervisorId: supervisor.id,
      supervisorNombre: supervisor.nombre,
      estado: 'pendiente',
      fechaAsignada: datos.fechaAsignada,
      iniciadaEn: null,
      completadaEn: null,
      observaciones: null,
      checklist,
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

    const resultado = await c.db.visitas.crearIdempotente(visita, huella)
    res
      .status(resultado.yaExistia ? 200 : 201)
      .json(visitaDetalleSupervisorDto(resultado.recurso, []))
  })

  return router
}

function normalizarNombreCliente(valor: string): string {
  return valor
    .trim()
    .toLocaleLowerCase('es-CO')
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
}
