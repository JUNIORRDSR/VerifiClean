import { getAuth } from 'firebase-admin/auth'
import { noAutorizado, conflicto } from '../lib/errors'

/**
 * Verificación de identidad. Dos implementaciones: Firebase Auth en ejecución real y
 * una falsa para desarrollo y tests.
 *
 * El modo falso existe porque exigir credenciales de GCP para correr `npm test`
 * significaría, en la práctica, que nadie corre los tests. `cargarEnv` se niega a
 * arrancar en `NODE_ENV=production` con `AUTH_MODE=fake`, así que el atajo no puede
 * llegar a producción.
 */

export interface IdentidadToken {
  uid: string
  email: string | null
}

export interface ServicioAuth {
  verificarIdToken(idToken: string): Promise<IdentidadToken>
  crearUsuario(datos: { email: string; password: string; nombre: string }): Promise<string>
  eliminarUsuario(uid: string): Promise<void>
}

export class ServicioAuthFirebase implements ServicioAuth {
  async verificarIdToken(idToken: string): Promise<IdentidadToken> {
    try {
      // `checkRevoked=true`: desactivar un supervisor debe cortarle el acceso ya,
      // no cuando expire el token que tenga en la mano.
      const decodificado = await getAuth().verifyIdToken(idToken, true)
      return { uid: decodificado.uid, email: decodificado.email ?? null }
    } catch {
      throw noAutorizado()
    }
  }

  async crearUsuario(datos: { email: string; password: string; nombre: string }): Promise<string> {
    try {
      const usuario = await getAuth().createUser({
        email: datos.email,
        password: datos.password,
        displayName: datos.nombre,
      })
      return usuario.uid
    } catch (e) {
      const codigo = (e as { code?: string }).code
      if (codigo === 'auth/email-already-exists') {
        throw conflicto('Ya existe un usuario con ese email.')
      }
      throw e
    }
  }

  async eliminarUsuario(uid: string): Promise<void> {
    await getAuth().deleteUser(uid)
  }
}

/**
 * Modo desarrollo: `Authorization: Bearer dev:<uid>` autentica como ese uid.
 * No hay firma, no hay expiración, no hay nada que verificar — es exactamente por
 * eso que `cargarEnv` lo prohíbe en producción.
 */
export class ServicioAuthFalso implements ServicioAuth {
  private readonly creados = new Map<string, string>() // uid -> email
  private siguiente = 1

  async verificarIdToken(idToken: string): Promise<IdentidadToken> {
    if (!idToken.startsWith('dev:')) {
      throw noAutorizado('En AUTH_MODE=fake el token debe tener la forma `dev:<uid>`.')
    }
    const uid = idToken.slice('dev:'.length).trim()
    if (!uid) throw noAutorizado('El token `dev:` no trae uid.')
    return { uid, email: this.creados.get(uid) ?? null }
  }

  async crearUsuario(datos: { email: string; password: string; nombre: string }): Promise<string> {
    for (const email of this.creados.values()) {
      if (email.toLowerCase() === datos.email.toLowerCase()) {
        throw conflicto('Ya existe un usuario con ese email.')
      }
    }
    const uid = `uid_fake_${this.siguiente++}`
    this.creados.set(uid, datos.email)
    return uid
  }

  async eliminarUsuario(uid: string): Promise<void> {
    this.creados.delete(uid)
  }

  /** Para tests: registra un uid preexistente sin pasar por `crearUsuario`. */
  registrar(uid: string, email: string): void {
    this.creados.set(uid, email)
  }
}
