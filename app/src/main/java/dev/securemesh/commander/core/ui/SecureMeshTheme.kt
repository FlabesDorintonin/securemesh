package dev.securemesh.commander.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object SecureMeshColors {
    val Graphite = Color(0xFF071118)
    val Surface = Color(0xFF0D1A24)
    val SurfaceHigh = Color(0xFF132531)
    val SurfaceBright = Color(0xFF193140)
    val Cyan = Color(0xFF52D5FF)
    val Blue = Color(0xFF6A9CFF)
    val Healthy = Color(0xFF55E39A)
    val Warning = Color(0xFFFFC857)
    val Critical = Color(0xFFFF6574)
    val Muted = Color(0xFF8FA5B2)
    val Text = Color(0xFFF2F7FA)
    val TextSecondary = Color(0xFFB7C8D1)
    val Divider = Color(0xFF1C3441)
    val BubbleIncoming = Color(0xFF132733)
    val BubbleOutgoing = Color(0xFF12394A)
    val Navigation = Color(0xFF0A1720)
}

private val DarkScheme = darkColorScheme(
    primary = SecureMeshColors.Cyan,
    secondary = SecureMeshColors.Blue,
    tertiary = SecureMeshColors.Healthy,
    background = SecureMeshColors.Graphite,
    surface = SecureMeshColors.Surface,
    surfaceVariant = SecureMeshColors.SurfaceHigh,
    onPrimary = Color(0xFF00222E),
    onSecondary = Color(0xFF071B34),
    onBackground = SecureMeshColors.Text,
    onSurface = SecureMeshColors.Text,
    onSurfaceVariant = SecureMeshColors.TextSecondary,
    outline = SecureMeshColors.Divider,
    error = SecureMeshColors.Critical,
)

private val SecureMeshTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.35).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

private val SecureMeshShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun SecureMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = SecureMeshTypography,
        shapes = SecureMeshShapes,
        content = content,
    )
}
