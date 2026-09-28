package com.verificlean.app.di

import android.content.Context
import androidx.room.Room
import com.verificlean.app.BuildConfig
import com.verificlean.app.capture.Autenticador
import com.verificlean.app.capture.AutenticadorFalso
import com.verificlean.app.capture.AutenticadorFirebase
import com.verificlean.app.capture.ProveedorIntegridad
import com.verificlean.app.capture.ProveedorIntegridadFalso
import com.verificlean.app.capture.ProveedorIntegridadPlay
import com.verificlean.app.core.DispatcherIo
import com.verificlean.app.core.DispatcherPorDefecto
import com.verificlean.app.core.ScopeAplicacion
import com.verificlean.app.data.local.CarpetaEvidencias
import com.verificlean.app.data.local.EvidenciaPendienteDao
import com.verificlean.app.data.local.ProveedorClaveEvidencia
import com.verificlean.app.data.local.ProveedorClaveKeystore
import com.verificlean.app.data.local.VerifiCleanDatabase
import com.verificlean.app.data.local.VisitaDao
import com.verificlean.app.data.remote.AutenticacionInterceptor
import com.verificlean.app.data.remote.AutenticadorDeReintento
import com.verificlean.app.data.remote.VerifiCleanApi
import com.verificlean.app.data.repository.AdminRepositoryImpl
import com.verificlean.app.data.repository.CatalogoRepositoryImpl
import com.verificlean.app.data.repository.EvidenciaRepositoryImpl
import com.verificlean.app.data.repository.SesionRepositoryImpl
import com.verificlean.app.data.repository.VisitaRepositoryImpl
import com.verificlean.app.domain.repository.AdminRepository
import com.verificlean.app.domain.repository.CatalogoRepository
import com.verificlean.app.domain.repository.EvidenciaRepository
import com.verificlean.app.domain.repository.SesionRepository
import com.verificlean.app.domain.repository.VisitaRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RedModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        // Tolerante por diseño: si el backend añade un campo, las apps ya instaladas
        // deben seguir funcionando en vez de fallar al deserializar.
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun okHttp(
        autenticacion: AutenticacionInterceptor,
        reintentoAuth: AutenticadorDeReintento,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(autenticacion)
            // Renueva el token y repite una vez ante un 401. Sin esto, una evidencia que
            // esperó horas en la cola offline llega con el token caducado y el worker
            // gasta sus reintentos contra un rechazo que se arreglaba renovando.
            .authenticator(reintentoAuth)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            // BASIC y no BODY: el cuerpo de una subida de evidencia son
                            // megas de JPEG en base64 en el logcat, y las respuestas
                            // llevan URLs firmadas que no deberían quedar en el log.
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            // Generoso a propósito: subir una foto de varios MB desde el sótano de un
            // edificio con 3G puede tardar. Cortar antes obligaría a un reintento
            // completo y gastaría más datos que esperar.
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    fun retrofit(cliente: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(cliente)
            // El paquete lleva el prefijo `com.jakewharton`, no `retrofit2` a secas:
            // este converter es de terceros, no el oficial de Retrofit.
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun api(retrofit: Retrofit): VerifiCleanApi = retrofit.create(VerifiCleanApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
object BaseDatosModule {

    @Provides
    @Singleton
    fun baseDatos(@ApplicationContext contexto: Context): VerifiCleanDatabase =
        Room.databaseBuilder(contexto, VerifiCleanDatabase::class.java, VerifiCleanDatabase.NOMBRE)
            // Sin `fallbackToDestructiveMigration`: borraría las evidencias que aún no
            // se han subido. Cada cambio de esquema lleva su migración explícita.
            .build()

    @Provides
    fun visitaDao(bd: VerifiCleanDatabase): VisitaDao = bd.visitaDao()

    @Provides
    fun evidenciaDao(bd: VerifiCleanDatabase): EvidenciaPendienteDao = bd.evidenciaPendienteDao()
}

@Module
@InstallIn(SingletonComponent::class)
object AlmacenamientoLocalModule {

    @Provides
    @Singleton
    @CarpetaEvidencias
    fun carpetaEvidencias(@ApplicationContext contexto: Context): java.io.File =
        java.io.File(contexto.filesDir, "evidencias_pendientes")

    @Provides
    @Singleton
    fun proveedorClave(impl: ProveedorClaveKeystore): ProveedorClaveEvidencia = impl
}

@Module
@InstallIn(SingletonComponent::class)
object DespachadoresModule {
    @Provides @DispatcherIo fun io(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DispatcherPorDefecto fun porDefecto(): CoroutineDispatcher = Dispatchers.Default

    /** Trabajo corto que sobrevive a la pantalla; WorkManager cubre una muerte del proceso. */
    @Provides
    @Singleton
    @ScopeAplicacion
    fun scopeAplicacion(@DispatcherIo io: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + io)
}

/**
 * Adaptadores que cambian según el build.
 *
 * En debug entran las versiones falsas de autenticación e integridad, que hablan con un
 * backend en `AUTH_MODE=fake` / `INTEGRITY_MODE=fake`. Permite desarrollar y probar la
 * app entera —incluidas las reglas antifraude— sin proyecto de GCP.
 */
@Module
@InstallIn(SingletonComponent::class)
object AdaptadoresModule {

    @Provides
    @Singleton
    fun autenticador(
        firebase: dagger.Lazy<AutenticadorFirebase>,
        falso: dagger.Lazy<AutenticadorFalso>,
    ): Autenticador = if (BuildConfig.USA_AUTH_FALSO) falso.get() else firebase.get()

    @Provides
    @Singleton
    fun proveedorIntegridad(
        @ApplicationContext contexto: Context,
        falso: dagger.Lazy<ProveedorIntegridadFalso>,
    ): ProveedorIntegridad =
        if (BuildConfig.USA_AUTH_FALSO) {
            falso.get()
        } else {
            // El número de proyecto de Google Cloud lo exige Play Integrity para poder
            // descifrar el token en el servidor. Se lee de los recursos para no
            // recompilar al cambiar de proyecto.
            ProveedorIntegridadPlay(contexto, NUMERO_PROYECTO_CLOUD)
        }

    /**
     * Reemplázalo por el número de proyecto real al configurar GCP (Consola de Google
     * Cloud → Configuración del proyecto → Número de proyecto). Con 0 la llamada falla
     * y el servidor marca la evidencia como INTEGRIDAD_NO_EVALUADA.
     */
    private const val NUMERO_PROYECTO_CLOUD = 0L
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositorioModule {
    @Binds abstract fun sesion(impl: SesionRepositoryImpl): SesionRepository
    @Binds abstract fun visitas(impl: VisitaRepositoryImpl): VisitaRepository
    @Binds abstract fun evidencias(impl: EvidenciaRepositoryImpl): EvidenciaRepository
    @Binds abstract fun admin(impl: AdminRepositoryImpl): AdminRepository
    @Binds abstract fun catalogo(impl: CatalogoRepositoryImpl): CatalogoRepository
}
