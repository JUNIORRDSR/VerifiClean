package com.verificlean.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Paleta.
 *
 * Verde profundo como color de marca (limpieza, verificado) y **rojo reservado en
 * exclusiva para las alertas de fraude**. Ningún otro elemento de la app usa rojo: en
 * un panel donde lo que importa es detectar la anomalía, gastar el color de alarma en
 * un botón de borrar o en un error de formulario le quita fuerza justo donde hace falta.
 */
private val VerdeMarca = Color(0xFF0F5C4A)
private val VerdeMarcaClaro = Color(0xFF3E8B75)
private val VerdeContenedor = Color(0xFFC7E9DD)
private val AmbarAtencion = Color(0xFF765A00)

/**
 * Colores semánticos exclusivos del veredicto antifraude.
 *
 * No viven en `ColorScheme.error`: errores de red, formulario o cámara también usan
 * ese rol de Material. Separarlos evita que una falla operativa parezca una alerta de
 * fraude y garantiza que el rojo conserve el mismo significado en toda la app.
 */
val ColorScheme.alertaFraude: Color
    get() = if (background.luminance() > 0.5f) Color(0xFFA9382F) else Color(0xFFFFB4AB)

val ColorScheme.contenedorAlertaFraude: Color
    get() = if (background.luminance() > 0.5f) Color(0xFFFFDAD6) else Color(0xFF7F1D1D)

val ColorScheme.sobreContenedorAlertaFraude: Color
    get() = if (background.luminance() > 0.5f) Color(0xFF410002) else Color(0xFFFFDAD6)

private val esquemaClaro = lightColorScheme(
    primary = VerdeMarca,
    onPrimary = Color.White,
    primaryContainer = VerdeContenedor,
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4B635B),
    tertiary = AmbarAtencion,
    // El ámbar comunica una incidencia operativa sin gastar el rojo antifraude.
    error = AmbarAtencion,
    onError = Color.White,
    errorContainer = Color(0xFFFFE08A),
    onErrorContainer = Color(0xFF251A00),
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFFCFDFC),
    surfaceVariant = Color(0xFFE1E9E5),
    outline = Color(0xFF6F7975),
)

private val esquemaOscuro = darkColorScheme(
    primary = VerdeMarcaClaro,
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005140),
    onPrimaryContainer = VerdeContenedor,
    secondary = Color(0xFFB1CCC1),
    tertiary = Color(0xFFF0C06B),
    error = Color(0xFFE8C349),
    onError = Color(0xFF3D2F00),
    errorContainer = Color(0xFF594500),
    onErrorContainer = Color(0xFFFFE08A),
    background = Color(0xFF151918),
    surface = Color(0xFF191D1C),
    surfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF899390),
)

private val tipografia = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
)

private val formas = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun VerifiCleanTheme(
    oscuro: Boolean = isSystemInDarkTheme(),
    contenido: @Composable () -> Unit,
) {
    // Sin color dinámico de Material You: el rojo de alerta tiene que ser el mismo en
    // todos los teléfonos del equipo. Si el sistema lo tiñera del fondo de pantalla del
    // usuario, "sospechosa" se vería distinto en cada dispositivo.
    val esquema = if (oscuro) esquemaOscuro else esquemaClaro

    val vista = LocalView.current
    if (!vista.isInEditMode) {
        SideEffect {
            val actividad = vista.context as? Activity ?: return@SideEffect
            WindowCompat.getInsetsController(actividad.window, vista)
                .isAppearanceLightStatusBars = !oscuro
        }
    }

    MaterialTheme(
        colorScheme = esquema,
        typography = tipografia,
        shapes = formas,
        content = contenido,
    )
}
