package com.verificlean.app.capture

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Obtiene el ID token que la API espera en `Authorization: Bearer`.
 *
 * Dos implementaciones, igual que en el backend: Firebase Authentication en ejecución
 * real y una falsa para desarrollar sin GCP. El backend acepta `dev:<uid>` cuando corre
 * con `AUTH_MODE=fake`, así que los dos lados encajan y toda la app —red, cola offline,
 * reglas antifraude— se puede probar de punta a punta antes de tocar la nube.
 */
interface Autenticador {
    /** Autentica y devuelve el ID token. `null` si las credenciales no son válidas. */
    suspend fun iniciarSesion(email: String, password: String): String?

    /**
     * Token vigente. `null` si no hay sesión activa en este proceso.
     *
     * `forzarRefresco` lo activa el reintento tras un 401: significa "el que tenías ya no
     * vale, pide uno nuevo aunque el SDK crea que aún es bueno".
     */
    suspend fun tokenVigente(forzarRefresco: Boolean = false): String?

    suspend fun cerrarSesion()
}

@Singleton
class AutenticadorFirebase @Inject constructor() : Autenticador {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    override suspend fun iniciarSesion(email: String, password: String): String? =
        suspendCancellableCoroutine { continuacion ->
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { resultado ->
                    resultado.user?.getIdToken(false)
                        ?.addOnSuccessListener { continuacion.resume(it.token) }
                        ?.addOnFailureListener { continuacion.resume(null) }
                        ?: continuacion.resume(null)
                }
                .addOnFailureListener { continuacion.resume(null) }
        }

    /**
     * Con `forzarRefresco = false` el SDK renueva solo cuando quedan menos de cinco
     * minutos de vigencia, así que la llamada normal no cuesta un viaje de red. Sólo el
     * reintento tras un 401 pide `true`, y esa es exactamente la situación en la que
     * gastar el viaje merece la pena.
     */
    override suspend fun tokenVigente(forzarRefresco: Boolean): String? {
        val usuario = auth.currentUser ?: return null
        return suspendCancellableCoroutine { continuacion ->
            usuario.getIdToken(forzarRefresco)
                .addOnSuccessListener { continuacion.resume(it.token) }
                .addOnFailureListener { continuacion.resume(null) }
        }
    }

    override suspend fun cerrarSesion() {
        auth.signOut()
    }
}

/**
 * Modo desarrollo, contra un backend con `AUTH_MODE=fake`.
 *
 * No valida contraseñas: traduce el correo a un uid del seed y devuelve `dev:<uid>`. El
 * mapa refleja `contracts/seed/seed.json`. También acepta que se escriba un uid
 * directamente en el campo de correo, para probar con usuarios fuera del seed.
 *
 * Sus tokens no caducan nunca, así que `tokenVigente` devuelve siempre el mismo. Eso hace
 * que el camino del 401 **no** se ejercite en desarrollo — de ahí que exista un test
 * dedicado con MockWebServer para esa ruta.
 */
@Singleton
class AutenticadorFalso @Inject constructor() : Autenticador {

    private var tokenActual: String? = null

    override suspend fun iniciarSesion(email: String, password: String): String? {
        val entrada = email.trim().lowercase()
        if (entrada.isEmpty() || password.isEmpty()) return null

        val uid = UIDS_DEL_SEED[entrada] ?: entrada.takeIf { it.startsWith("u_") } ?: return null
        return "dev:$uid".also { tokenActual = it }
    }

    override suspend fun tokenVigente(forzarRefresco: Boolean): String? = tokenActual

    override suspend fun cerrarSesion() {
        tokenActual = null
    }

    private companion object {
        val UIDS_DEL_SEED = mapOf(
            "admin@verificlean.test" to "u_admin",
            "carlos@verificlean.test" to "u_sup_1",
            "diana@verificlean.test" to "u_sup_2",
            "pedro@verificlean.test" to "u_sup_inactivo",
        )
    }
}
