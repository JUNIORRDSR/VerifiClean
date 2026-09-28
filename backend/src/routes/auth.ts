import { Router } from 'express'
import type { Contenedor } from '../container'
import { loginSchema } from '../domain/schemas'
import { prohibido } from '../lib/errors'
import { usuarioSesionDto } from './mappers'

/**
 * `POST /v1/auth/login` — canje de ID token por perfil de sesión.
 *
 * Firebase Auth ya autenticó al usuario en el dispositivo; aquí no se validan
 * contraseñas. El endpoint existe porque hay tres cosas que el cliente no puede
 * resolver solo sin darle permisos que no debería tener:
 *
 *   1. Su rol — leerlo en el cliente exigiría abrir `users` a lectura propia y
 *      obligaría a confiar en lo que el cliente diga que leyó.
 *   2. Si está activo — desactivar a alguien tiene que cortar el acceso ya.
 *   3. Vincular el dispositivo — el servidor es quien decide qué `deviceId` queda
 *      registrado; si lo decidiera el cliente, la vinculación no probaría nada.
 */
export function rutasAuth(c: Contenedor): Router {
  const router = Router()

  router.post('/login', async (req, res) => {
    const { idToken, deviceId } = loginSchema.parse(req.body)

    const identidad = await c.auth.verificarIdToken(idToken)
    const usuario = await c.db.usuarios.obtener(identidad.uid)

    if (!usuario) throw prohibido('Tu usuario no está dado de alta en VerifiClean.')
    if (!usuario.activo) throw prohibido('Tu usuario está inactivo.')

    // Primer login del supervisor: se graba el dispositivo. Las evidencias posteriores
    // comparan su cabecera con este valor dentro del servicio antifraude; el login no hace
    // ni devuelve esa comparación. Reasignar exige que un admin borre el `deviceId`.
    if (deviceId) {
      if (!usuario.deviceId) {
        await c.db.usuarios.actualizar(usuario.id, { deviceId })
        usuario.deviceId = deviceId
      }
    }

    // No se devuelve el resultado de la comparación. Hacerlo permitiría al supervisor
    // deducir una de las señales antifraude y ajustar el siguiente intento.
    res.json({ usuario: usuarioSesionDto(usuario) })
  })

  return router
}
