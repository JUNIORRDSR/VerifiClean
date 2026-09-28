package com.verificlean.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Usuario
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "sesion")

/**
 * Sesión persistida: token y perfil.
 *
 * El token vive aquí y no en memoria porque WorkManager puede despertar el proceso
 * horas después de que el usuario cerrara la app, sin pasar por ninguna pantalla, y
 * necesita autenticarse para subir la evidencia que quedó en cola.
 */
@Singleton
class SesionStore @Inject constructor(
    @ApplicationContext private val contexto: Context,
    private val idDispositivo: IdDispositivo,
) {

    val usuario: Flow<Usuario?> = contexto.dataStore.data.map { prefs ->
        val id = prefs[UID] ?: return@map null
        Usuario(
            id = id,
            nombre = prefs[NOMBRE].orEmpty(),
            email = prefs[EMAIL].orEmpty(),
            rol = if (prefs[ROL] == "admin") Rol.ADMIN else Rol.SUPERVISOR,
            activo = prefs[ACTIVO] ?: true,
        )
    }

    /**
     * Guarda sólo el token, sin perfil.
     *
     * Hace falta entre los dos pasos del login: el interceptor lee el token de aquí para
     * poder llamar a `/v1/auth/login`, pero el perfil todavía no se conoce. Escribir un
     * usuario de relleno haría que `usuario` emitiera algo no nulo y la UI navegaría a
     * la pantalla principal con una sesión que aún puede ser rechazada.
     */
    suspend fun guardarTokenProvisional(token: String) {
        contexto.dataStore.edit { it[TOKEN] = token }
    }

    suspend fun guardar(usuario: Usuario, token: String) {
        contexto.dataStore.edit { prefs ->
            prefs[UID] = usuario.id
            prefs[NOMBRE] = usuario.nombre
            prefs[EMAIL] = usuario.email
            prefs[ROL] = if (usuario.rol == Rol.ADMIN) "admin" else "supervisor"
            prefs[ACTIVO] = usuario.activo
            prefs[TOKEN] = token
        }
    }

    suspend fun limpiar() {
        contexto.dataStore.edit { it.clear() }
    }

    suspend fun token(): String? = contexto.dataStore.data.first()[TOKEN]

    /** Lo que necesita el interceptor en una sola lectura del DataStore. */
    suspend fun tokenYDispositivo(): Pair<String?, String> =
        contexto.dataStore.data.first()[TOKEN] to idDispositivo.valor()

    private companion object {
        val UID = stringPreferencesKey("uid")
        val NOMBRE = stringPreferencesKey("nombre")
        val EMAIL = stringPreferencesKey("email")
        val ROL = stringPreferencesKey("rol")
        val ACTIVO = booleanPreferencesKey("activo")
        val TOKEN = stringPreferencesKey("token")
    }
}
