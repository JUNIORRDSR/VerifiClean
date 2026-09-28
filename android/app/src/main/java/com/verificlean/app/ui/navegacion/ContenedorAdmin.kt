package com.verificlean.app.ui.navegacion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination

/**
 * Barra inferior de las secciones de administración.
 *
 * Vive aquí y no dentro de cada pantalla por dos razones. La práctica: cada pantalla trae
 * su propio `Scaffold` y repetir la barra en las cinco sería copiar el mismo bloque cinco
 * veces. Y la de diseño: la navegación entre secciones es una decisión de arquitectura de
 * la información, no de cada pantalla, así que rediseñar el aspecto de una no debería
 * poder romper cómo se llega a las demás.
 *
 * El orden de las secciones sigue la frecuencia de uso real de un administrador:
 * revisar alertas a diario, asignar la jornada a diario, y consultar el catálogo o sacar
 * un reporte de vez en cuando.
 */
@Composable
fun ContenedorAdmin(
    navController: NavHostController,
    rutaActual: String,
    contenido: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val navegar: (SeccionAdmin) -> Unit = { seccion ->
            if (rutaActual != seccion.ruta) {
                navController.navigate(seccion.ruta) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }

        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    SECCIONES.forEach { seccion ->
                        NavigationRailItem(
                            selected = rutaActual == seccion.ruta,
                            onClick = { navegar(seccion) },
                            icon = { Icon(seccion.icono, contentDescription = null) },
                            label = { Text(seccion.etiqueta) },
                        )
                    }
                }
                Box(Modifier.fillMaxHeight().fillMaxWidth()) { contenido() }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        SECCIONES.forEach { seccion ->
                            NavigationBarItem(
                                selected = rutaActual == seccion.ruta,
                                onClick = { navegar(seccion) },
                                icon = { Icon(seccion.icono, contentDescription = null) },
                                label = { Text(seccion.etiqueta) },
                            )
                        }
                    }
                },
            ) { relleno ->
                Box(Modifier.fillMaxSize().padding(bottom = relleno.calculateBottomPadding())) {
                    contenido()
                }
            }
        }
    }
}

private data class SeccionAdmin(
    val ruta: String,
    val etiqueta: String,
    val icono: ImageVector,
)

private val SECCIONES = listOf(
    SeccionAdmin(Rutas.ADMIN, "Alertas", Icons.Default.Shield),
    SeccionAdmin(Rutas.ASIGNAR, "Asignar", Icons.Default.EventAvailable),
    SeccionAdmin(Rutas.SEDES, "Sedes", Icons.Default.LocationCity),
    SeccionAdmin(Rutas.SUPERVISORES, "Equipo", Icons.Default.Groups),
    SeccionAdmin(Rutas.REPORTES, "Reportes", Icons.Default.Assessment),
)
