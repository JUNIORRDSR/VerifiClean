package com.verificlean.app.data.remote

import com.verificlean.app.data.remote.dto.CompletarVisitaRequestDto
import com.verificlean.app.data.remote.dto.CambioActivoDto
import com.verificlean.app.data.remote.dto.EvidenciaConfirmacionDto
import com.verificlean.app.data.remote.dto.ListaSitiosDto
import com.verificlean.app.data.remote.dto.ListaUsuariosDto
import com.verificlean.app.data.remote.dto.LoginRequestDto
import com.verificlean.app.data.remote.dto.LoginResponseDto
import com.verificlean.app.data.remote.dto.PaginaVisitasAdminDto
import com.verificlean.app.data.remote.dto.PaginaVisitasSupervisorDto
import com.verificlean.app.data.remote.dto.ReporteDto
import com.verificlean.app.data.remote.dto.SitioDto
import com.verificlean.app.data.remote.dto.SitioNuevoDto
import com.verificlean.app.data.remote.dto.UsuarioDto
import com.verificlean.app.data.remote.dto.UsuarioNuevoDto
import com.verificlean.app.data.remote.dto.VisitaDetalleAdminDto
import com.verificlean.app.data.remote.dto.VisitaDetalleSupervisorDto
import com.verificlean.app.data.remote.dto.VisitaNuevaDto
import com.verificlean.app.data.remote.dto.RevisionAlertaRequestDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Contrato HTTP. Cada firma corresponde a una entrada de `contracts/openapi.yaml`.
 *
 * Todo devuelve `Response<T>` en vez de `T` a secas: el código de estado importa —
 * `POST /evidence` distingue 201 (creada) de 200 (reenvío idempotente ya registrado),
 * y el traductor de errores necesita el cuerpo del error, no una excepción.
 */
interface VerifiCleanApi {

    @POST("v1/auth/login")
    suspend fun login(@Body cuerpo: LoginRequestDto): Response<LoginResponseDto>

    @GET("v1/visits")
    suspend fun listarVisitas(
        @Query("estado") estado: String? = null,
        @Query("limite") limite: Int = 50,
        @Query("cursor") cursor: String? = null,
    ): Response<PaginaVisitasSupervisorDto>

    @GET("v1/visits/{id}")
    suspend fun obtenerVisita(@Path("id") id: String): Response<VisitaDetalleSupervisorDto>

    /**
     * Subida de evidencia.
     *
     * `foto` va como `Part` con nombre explícito para que multer la reciba en el campo
     * que espera el servidor. El resto son campos de texto: multipart no anida
     * estructuras, así que `checklist` viaja serializado a JSON.
     */
    @Multipart
    @POST("v1/visits/{id}/evidence")
    suspend fun subirEvidencia(
        @Path("id") visitId: String,
        @Part foto: MultipartBody.Part,
        @Part("clienteEvidenciaId") clienteEvidenciaId: RequestBody,
        // Nulos cuando el dispositivo no consiguió ubicación: Retrofit omite la parte y el
        // servidor marca SIN_UBICACION en vez de inventar una distancia.
        @Part("lat") lat: RequestBody?,
        @Part("lng") lng: RequestBody?,
        @Part("precisionMetros") precisionMetros: RequestBody?,
        @Part("mockLocationReportada") mockLocationReportada: RequestBody,
        @Part("capturadoEn") capturadoEn: RequestBody,
        @Part("hashClienteReportado") hashClienteReportado: RequestBody,
        @Part("integrityToken") integrityToken: RequestBody?,
        @Part("checklist") checklist: RequestBody?,
    ): Response<EvidenciaConfirmacionDto>

    @PATCH("v1/visits/{id}/complete")
    suspend fun completarVisita(
        @Path("id") id: String,
        @Body cuerpo: CompletarVisitaRequestDto,
    ): Response<VisitaDetalleSupervisorDto>

    @GET("v1/admin/visits")
    suspend fun adminListarVisitas(
        @Query("siteId") siteId: String? = null,
        @Query("supervisorId") supervisorId: String? = null,
        @Query("estado") estado: String? = null,
        @Query("sospechosas") sospechosas: Boolean? = null,
        @Query("desde") desde: String? = null,
        @Query("hasta") hasta: String? = null,
        @Query("limite") limite: Int = 200,
        @Query("cursor") cursor: String? = null,
    ): Response<PaginaVisitasAdminDto>

    @GET("v1/admin/visits/{id}")
    suspend fun adminObtenerVisita(@Path("id") id: String): Response<VisitaDetalleAdminDto>

    @PATCH("v1/admin/visits/{id}/alert-review")
    suspend fun adminActualizarRevisionAlerta(
        @Path("id") id: String,
        @Body cuerpo: RevisionAlertaRequestDto,
    ): Response<VisitaDetalleAdminDto>

    @GET("v1/admin/reports")
    suspend fun adminReporte(
        @Query("clienteId") clienteId: String,
        @Query("desde") desde: String,
        @Query("hasta") hasta: String,
    ): Response<ReporteDto>

    // ------------------------------------------------------------------ catálogo
    //
    // Alta de sedes, supervisores y asignación de visitas. Todo exige rol admin; el
    // servidor lo vuelve a comprobar en cada petición.

    @GET("v1/sites")
    suspend fun listarSitios(
        @Query("clienteId") clienteId: String? = null,
        @Query("activo") activo: Boolean? = null,
    ): Response<ListaSitiosDto>

    @POST("v1/sites")
    suspend fun crearSitio(
        @Header("Idempotency-Key") claveIdempotencia: String,
        @Body cuerpo: SitioNuevoDto,
    ): Response<SitioDto>

    @PATCH("v1/sites/{id}")
    suspend fun actualizarSitioActivo(
        @Path("id") id: String,
        @Body cuerpo: CambioActivoDto,
    ): Response<SitioDto>

    @GET("v1/users")
    suspend fun listarUsuarios(@Query("rol") rol: String? = null): Response<ListaUsuariosDto>

    @POST("v1/users")
    suspend fun crearUsuario(@Body cuerpo: UsuarioNuevoDto): Response<UsuarioDto>

    @PATCH("v1/users/{id}")
    suspend fun actualizarUsuarioActivo(
        @Path("id") id: String,
        @Body cuerpo: CambioActivoDto,
    ): Response<UsuarioDto>

    @POST("v1/visits/assign")
    suspend fun asignarVisita(
        @Header("Idempotency-Key") claveIdempotencia: String,
        @Body cuerpo: VisitaNuevaDto,
    ): Response<VisitaDetalleSupervisorDto>
}
