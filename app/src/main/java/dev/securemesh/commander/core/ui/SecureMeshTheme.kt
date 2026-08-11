package dev.securemesh.commander.core.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SecureMeshColors {
    val Graphite = Color(0xFF091117)
    val Surface = Color(0xFF0E1921)
    val SurfaceHigh = Color(0xFF15242E)
    val Cyan = Color(0xFF46C8F2)
    val Blue = Color(0xFF5793FF)
    val Healthy = Color(0xFF55D68A)
    val Warning = Color(0xFFFFC857)
    val Critical = Color(0xFFFF6470)
    val Muted = Color(0xFF8EA3AF)
    val Text = Color(0xFFE9F3F7)
}

private val DarkScheme = darkColorScheme(
    primary = SecureMeshColors.Cyan,
    secondary = SecureMeshColors.Blue,
    background = SecureMeshColors.Graphite,
    surface = SecureMeshColors.Surface,
    surfaceVariant = SecureMeshColors.SurfaceHigh,
    onPrimary = Color(0xFF001E29),
    onBackground = SecureMeshColors.Text,
    onSurface = SecureMeshColors.Text,
    error = SecureMeshColors.Critical,
)

@Composable
fun SecureMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = Typography(),
        content = content,
    )
}
