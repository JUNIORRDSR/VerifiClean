import type { NextFunction, Request, RequestHandler, Response } from 'express'
import type { Actor } from '../domain/types'
import { noAutorizado, prohibido } from '../lib/errors'
import type { Db } from '../repos/types'
import type { ServicioAuth } from '../services/auth'

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      /** Identidad ya verificada. Presente en todo handler detrás de `exigirAuth`. */
      actor?: Actor
      /** `X-Device-Id` crudo de la petición. */
      deviceId?: string | null
    }
  }
}

/** Cabecera con la que el cliente declara su dispositivo. */
export const HEADER_DEVICE_ID = 'x-device-id'

/**
 * Verifica el ID token y resuelve el perfil.
 *
 * El rol se lee siempre de `users/{uid}` en el servidor, nunca de un custom claim del
 * token: un claim viaja en el JWT y sigue siendo válido hasta que expire, así que
 * degradar a un admin no tendría efecto inmediato. La lectura extra a Firestore cuesta
 * poco y hace que revocar permisos surta efecto en la siguiente petición.
 */
export function exigirAuth(auth: ServicioAuth, db: Db): RequestHandler {
  return async (req: Request, _res: Response, next: NextFunction) => {
    try {
      const cabecera = req.header('authorization') ?? ''
      const [esquema, token] = cabecera.split(' ')
      if (esquema?.toLowerCase() !== 'bearer' || !token) {
        throw noAutorizado('Falta la cabecera `Authorization: Bearer <idToken>`.')
      }

      const identidad = await auth.verificarIdToken(token)
      const usuario = await db.usuarios.obtener(identidad.uid)
      if (!usuario) {
        // Autenticado en Firebase pero sin documento en `users`: no tiene rol, así
        // que no puede hacer nada. Es 403, no 401 — el token sí era válido.
        throw prohibido('Tu usuario no está dado de alta en VerifiClean.')
      }
      if (!usuario.activo) throw prohibido('Tu usuario está inactivo.')

      req.actor = { uid: identidad.uid, email: identidad.email ?? usuario.email, usuario }
      req.deviceId = req.header(HEADER_DEVICE_ID) ?? null
      next()
    } catch (e) {
      next(e)
    }
  }
}

/** Corta el paso a quien no sea admin. Va siempre después de `exigirAuth`. */
export const exigirAdmin: RequestHandler = (req, _res, next) => {
  if (req.actor?.usuario.rol !== 'admin') {
    next(prohibido('Esta operación requiere rol admin.'))
    return
  }
  next()
}

/** Lee el actor ya verificado. Lanza si se usó fuera de una ruta protegida. */
export function actorDe(req: Request): Actor {
  if (!req.actor) throw noAutorizado()
  return req.actor
}
