package com.verificlean.app.ui.navegacion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.model.Usuario
import com.verificlean.app.domain.repository.SesionRepository
import com.verificlean.app.ui.pantallas.admin.DetalleAdminScreen
import com.verificlean.app.ui.pantallas.admin.PanelAdminScreen
import com.verificlean.app.ui.pantallas.captura.CapturaScreen
import com.verificlean.app.ui.pantallas.gestion.AsignarVisitaScreen
import com.verificlean.app.ui.pantallas.gestion.SedesScreen
import com.verificlean.app.ui.pantallas.gestion.SupervisoresScreen
import com.verificlean.app.ui.pantallas.login.LoginScreen
import com.verificlean.app.ui.pantallas.reportes.ReportesScreen
import com.verificlean.app.ui.pantallas.visitas.DetalleVisitaScreen
import com.verificlean.app.ui.pantallas.visitas.VisitasScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Rutas. El grafo se decide por rol: un supervisor nunca tiene una ruta hacia el panel
 * de admin, en lugar de tenerla y esconder el botón. Aun así el servidor vuelve a
 * comprobar el rol en cada petición — la navegación es comodidad, no seguridad.
 */
object Rutas {
    const val CARGANDO = "cargando"
    const val LOGIN = "login"
    const val VISITAS = "visitas"
    const val DETALLE_VISITA = "visitas/{visitId}"
    const val CAPTURA = "visitas/{visitId}/captura"
    const val ADMIN = "admin"

    // `admin/visita/{visitId}` y no `admin/{visitId}`: con el patrón corto, la ruta del
    // detalle solapa con `admin/sedes` y hermanas, y cuál gana depende de detalles de
    // resolución de Navigation en vez de una decisión nuestra.
    const val DETALLE_ADMIN = "admin/visita/{visitId}"

    // Operación: lo que hace existir el trabajo. Sin estas pantallas el producto sólo se
    // podía administrar por curl.
    const val SEDES = "admin/sedes"
    const val SUPERVISORES = "admin/supervisores"
    const val ASIGNAR = "admin/asignar"
    const val REPORTES = "admin/reportes"

    fun detalleVisita(visitId: String) = "visitas/$visitId"
    fun captura(visitId: String) = "visitas/$visitId/captura"
    fun detalleAdmin(visitId: String) = "admin/visita/$visitId"
}

/** Señal que la pantalla de captura deja al detalle para que se recargue al volver. */
const val CLAVE_EVIDENCIA_NUEVA = "evidencia_nueva"

sealed interface EstadoSesion {
    data object Cargando : EstadoSesion
    data object SinSesion : EstadoSesion
    data class ConSesion(val usuario: Usuario) : EstadoSesion
}

@HiltViewModel
class SesionViewModel @Inject constructor(
    sesionRepository: SesionRepository,
) : ViewModel() {

    val estado: StateFlow<EstadoSesion> = sesionRepository.usuarioEnSesion
        .map { usuario ->
            if (usuario == null) EstadoSesion.SinSesion else EstadoSesion.ConSesion(usuario)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            // Arranca en Cargando y no en SinSesion: mostrar el login durante el
            // instante que tarda DataStore en leer produciría un parpadeo al login en
            // cada arranque de una sesión ya iniciada.
            initialValue = EstadoSesion.Cargando,
        )
}

@Composable
fun NavegacionVerifiClean(
    navController: NavHostController = rememberNavController(),
    sesionViewModel: SesionViewModel = hiltViewModel(),
) {
    val estado by sesionViewModel.estado.collectAsStateWithLifecycle()

    when (val actual = estado) {
        EstadoSesion.Cargando -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }

        EstadoSesion.SinSesion -> LoginScreen()

        is EstadoSesion.ConSesion -> {
            val inicio = if (actual.usuario.rol == Rol.ADMIN) Rutas.ADMIN else Rutas.VISITAS

            val esAdmin = actual.usuario.rol == Rol.ADMIN

            NavHost(navController = navController, startDestination = inicio) {
                // Las rutas se registran por rol, no sólo el destino inicial. Antes el
                // grafo declaraba las diez para todo el mundo y el rol únicamente decidía
                // por cuál se empezaba: cualquier `navigate("admin/sedes")` —desde un
                // deep link futuro, una restauración de back stack o un descuido en una
                // pantalla— habría construido la pantalla de admin en la sesión de un
                // supervisor. Las consultas fallarían con 403, pero el formulario ya
                // enseña el rango de radio permitido y qué produce falsas alertas, que es
                // información antifraude que ese rol no debe leer.
                //
                // El servidor comprueba el rol igual en cada petición: esto es la puerta,
                // no la cerradura.
                if (!esAdmin) grafoSupervisor(navController)
                if (esAdmin) grafoAdmin(navController)
            }
        }
    }
}

private fun NavGraphBuilder.grafoSupervisor(navController: NavHostController) {
    composable(Rutas.VISITAS) {
        VisitasScreen(alAbrirVisita = { navController.navigate(Rutas.detalleVisita(it)) })
    }

    composable(Rutas.DETALLE_VISITA) { entrada ->
        val visitId = entrada.arguments?.getString("visitId").orEmpty()
        val hayEvidenciaNueva by entrada.savedStateHandle
            .getStateFlow(CLAVE_EVIDENCIA_NUEVA, false)
            .collectAsStateWithLifecycle()

        DetalleVisitaScreen(
            visitId = visitId,
            recargarPorEvidenciaNueva = hayEvidenciaNueva,
            alConsumirRecarga = { entrada.savedStateHandle[CLAVE_EVIDENCIA_NUEVA] = false },
            alCapturar = { navController.navigate(Rutas.captura(visitId)) },
            alVolver = { navController.popBackStack() },
        )
    }

    composable(Rutas.CAPTURA) { entrada ->
        CapturaScreen(
            visitId = entrada.arguments?.getString("visitId").orEmpty(),
            alTerminar = {
                // Se avisa al detalle de que hay una evidencia nueva. Sin esta señal,
                // `popBackStack` devolvía a una pantalla que conserva su estado, seguía
                // diciendo "falta evidencia" y dejaba el botón de completar apagado
                // después de haber tomado la foto.
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(CLAVE_EVIDENCIA_NUEVA, true)
                navController.popBackStack()
            },
        )
    }
}

private fun NavGraphBuilder.grafoAdmin(navController: NavHostController) {
    composable(Rutas.ADMIN) {
        // El panel se envuelve en una barra inferior que vive aquí, no dentro de la
        // pantalla: así el rediseño visual del panel y la navegación entre secciones
        // evolucionan por separado sin pisarse.
        ContenedorAdmin(navController, Rutas.ADMIN) {
            PanelAdminScreen(alAbrirVisita = { navController.navigate(Rutas.detalleAdmin(it)) })
        }
    }

    composable(Rutas.DETALLE_ADMIN) { entrada ->
        DetalleAdminScreen(
            visitId = entrada.arguments?.getString("visitId").orEmpty(),
            alVolver = { navController.popBackStack() },
        )
    }

    composable(Rutas.SEDES) {
        ContenedorAdmin(navController, Rutas.SEDES) {
            SedesScreen(alVolver = { navController.popBackStack() })
        }
    }

    composable(Rutas.SUPERVISORES) {
        ContenedorAdmin(navController, Rutas.SUPERVISORES) {
            SupervisoresScreen(alVolver = { navController.popBackStack() })
        }
    }

    composable(Rutas.ASIGNAR) {
        ContenedorAdmin(navController, Rutas.ASIGNAR) {
            AsignarVisitaScreen(alVolver = { navController.popBackStack() })
        }
    }

    composable(Rutas.REPORTES) {
        ContenedorAdmin(navController, Rutas.REPORTES) {
            ReportesScreen(alVolver = { navController.popBackStack() })
        }
    }
}
