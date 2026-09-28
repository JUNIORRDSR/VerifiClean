package com.verificlean.app.data.repository

import com.verificlean.app.capture.Autenticador
import com.verificlean.app.core.DispatcherIo
import com.verificlean.app.core.ErrorApp
import com.verificlean.app.core.Resultado
import com.verificlean.app.data.local.IdDispositivo
import com.verificlean.app.data.local.SesionStore
import com.verificlean.app.data.local.VerifiCleanDatabase
import com.verificlean.app.data.remote.VerifiCleanApi
import com.verificlean.app.data.remote.aDominio
import com.verificlean.app.data.remote.dto.LoginRequestDto
import com.verificlean.app.data.remote.llamadaApi
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.repository.SesionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SesionRepositoryImpl @Inject constructor(
    private val api: VerifiCleanApi,
    private val autenticador: Autenticador,
    private val sesionStore: SesionStore,
    private val idDispositivo: IdDispositivo,
    private val baseDatos: VerifiCleanDatabase,
    @DispatcherIo private val io: CoroutineDispatcher,
) : SesionRepository {

    override val usuarioEnSesion: Flow<Usuario?> = sesionStore.usuario

    /**
     * Login en dos pasos: primero Firebase (o el autenticador falso) devuelve el ID
     * token, después la API lo canjea por el perfil.
     *
     * El segundo paso no es opcional: el rol y el estado `activo` los decide el
     * servidor. Confiar en lo que diga el cliente sobre su propio rol convertiría el
     * panel de admin en algo que cualquiera puede abrir editando el almacenamiento
     * local.
     */
    override suspend fun iniciarSesion(email: String, password: String): Resultado<Usuario> =
        withContext(io) {
            val token = autenticador.iniciarSesion(email, password)
                ?: return@withContext Resultado.Fallo(
                    ErrorApp.Validacion("Correo o contraseña incorrectos."),
                )

            // El token tiene que estar guardado antes de llamar: el interceptor lo lee
            // del store, no de esta variable. Va sin perfil hasta que el canje lo
            // confirme, para que `usuarioEnSesion` no emita una sesión a medias.
            sesionStore.guardarTokenProvisional(token)

            val respuesta = llamadaApi {
                api.login(LoginRequestDto(idToken = token, deviceId = idDispositivo.valor()))
            }

            when (respuesta) {
                is Resultado.Exito -> {
                    val usuario = respuesta.datos.usuario.aDominio()
                    sesionStore.guardar(usuario, token)
                    Resultado.Exito(usuario)
                }
                is Resultado.Fallo -> {
                    // El canje falló: no se deja una sesión a medias con token válido y
                    // perfil vacío, porque la app la trataría como sesión iniciada.
                    sesionStore.limpiar()
                    autenticador.cerrarSesion()
                    respuesta
                }
            }
        }

    override suspend fun cerrarSesion() = withContext(io) {
        autenticador.cerrarSesion()
        sesionStore.limpiar()
        // La caché de visitas es de este supervisor: dejarla ahí se la mostraría al
        // siguiente que entre en el mismo dispositivo.
        baseDatos.visitaDao().borrarTodas()
        // Las evidencias pendientes NO se borran a propósito: son trabajo real todavía
        // sin subir. Se quedan en cola y se envían cuando vuelva a haber sesión.
    }

}
