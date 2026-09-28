package com.verificlean.app.domain.repository

import com.verificlean.app.core.Resultado
import com.verificlean.app.domain.model.ChecklistItem
import com.verificlean.app.domain.model.EstadoVisita
import com.verificlean.app.domain.model.EvidenciaPendiente
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.model.Visita
import com.verificlean.app.domain.model.VisitaDetalle
import com.verificlean.app.domain.model.VisitaDetalleAdmin
import com.verificlean.app.domain.model.VisitaResumenAdmin
import com.verificlean.app.domain.model.EstadoRevisionAlerta
import kotlinx.coroutines.flow.Flow

/**
 * Puertos que consume la capa de presentación. Los ViewModel dependen sólo de estas
 * interfaces, nunca de Retrofit ni de Room.
 */

interface SesionRepository {
    /** Emite el usuario en sesión, o `null` si no hay ninguna. */
    val usuarioEnSesion: Flow<Usuario?>

    suspend fun iniciarSesion(email: String, password: String): Resultado<Usuario>

    suspend fun cerrarSesion()

}

interface VisitaRepository {
    /**
     * Visitas del supervisor en sesión, desde la caché local.
     *
     * Emite primero lo que hay en Room y luego lo que llegue de la red: la bandeja
     * tiene que abrirse instantáneamente en un sótano sin señal, que es donde de verdad
     * se usa esta app.
     */
    fun observarVisitas(estado: EstadoVisita? = null): Flow<List<Visita>>

    /** Refresca desde la API y actualiza la caché. */
    suspend fun refrescarVisitas(): Resultado<Unit>

    suspend fun obtenerDetalle(visitId: String): Resultado<VisitaDetalle>

    suspend fun completarVisita(
        visitId: String,
        checklist: List<ChecklistItem>,
        observaciones: String?,
    ): Resultado<VisitaDetalle>
}

interface EvidenciaRepository {
    /**
     * Guarda la evidencia cifrada en el dispositivo y la encola para subir. **No** hace
     * red: devuelve en cuanto está a salvo en disco.
     *
     * Esta es la operación crítica del producto. Si fallara la subida y no hubiera cola
     * local, el supervisor habría hecho el trayecto para nada.
     */
    suspend fun capturarEvidencia(entrada: EntradaEvidencia): Resultado<String>

    /** Cola de evidencias todavía no confirmadas por el servidor. */
    fun observarPendientes(): Flow<List<EvidenciaPendiente>>

    /** Fuerza un intento de subida ya, sin esperar a la ventana de WorkManager. */
    fun sincronizarAhora()

    /** Reencola una evidencia marcada como fallida. */
    suspend fun reintentar(clienteEvidenciaId: String): Resultado<Unit>
}

/**
 * Datos que la pantalla de captura entrega al repositorio.
 *
 * Sólo lleva lo que la cámara produce. La ubicación y el token de integridad los resuelve
 * el repositorio **después** de haber puesto la foto a salvo en disco: pedirlos antes
 * dejaba el JPEG viviendo en memoria durante segundos, y una muerte del proceso en esa
 * ventana lo perdía para siempre.
 */
data class EntradaEvidencia(
    val visitId: String,
    val visitaNombreSede: String,
    val jpeg: ByteArray,
    val checklist: List<ChecklistItem>,
) {
    // `ByteArray` compara por referencia en el `equals` que genera `data class`, lo que
    // hace que dos entradas con la misma foto se consideren distintas. Se sobreescribe
    // para que el comportamiento no sorprenda en tests ni en colecciones.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EntradaEvidencia) return false
        return visitId == other.visitId &&
            jpeg.contentEquals(other.jpeg) &&
            checklist == other.checklist
    }

    override fun hashCode(): Int {
        var resultado = visitId.hashCode()
        resultado = 31 * resultado + jpeg.contentHashCode()
        resultado = 31 * resultado + checklist.hashCode()
        return resultado
    }
}

interface AdminRepository {
    suspend fun listarVisitas(
        soloSospechosas: Boolean = false,
        siteId: String? = null,
        estado: EstadoVisita? = null,
        desde: java.time.Instant? = null,
        hasta: java.time.Instant? = null,
        cursor: String? = null,
    ): Resultado<PaginaVisitas>

    suspend fun obtenerDetalle(visitId: String): Resultado<VisitaDetalleAdmin>

    suspend fun actualizarRevisionAlerta(
        visitId: String,
        estado: EstadoRevisionAlerta,
        nota: String?,
    ): Resultado<VisitaDetalleAdmin>
}

data class PaginaVisitas(val items: List<VisitaResumenAdmin>, val siguienteCursor: String?)
