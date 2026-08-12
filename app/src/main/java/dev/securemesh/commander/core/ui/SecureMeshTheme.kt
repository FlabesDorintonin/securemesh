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

/** Visual tokens for the SecureMesh operator UI. */
object SecureMeshColors {
    val Graphite = Color(0xFF05090D)
    val GraphiteSoft = Color(0xFF081018)
    val Surface = Color(0xFF0B131B)
    val SurfaceHigh = Color(0xFF101C26)
    val SurfaceBright = Color(0xFF172733)
    val SurfaceRaised = Color(0xFF1C303D)

    val Cyan = Color(0xFF40D9F7)
    val CyanHot = Color(0xFF8BEAFF)
    val Blue = Color(0xFF7392FF)
    val Violet = Color(0xFFA888FF)
    val Healthy = Color(0xFF4BE3A1)
    val Warning = Color(0xFFF7C45A)
    val Critical = Color(0xFFFF667C)

    val Muted = Color(0xFF8398A6)
    val Text = Color(0xFFF4F8FB)
    val TextSecondary = Color(0xFFB8C7D1)
    val Divider = Color(0xFF223642)
    val BubbleIncoming = Color(0xFF111F29)
    val BubbleOutgoing = Color(0xFF123848)
    val Navigation = Color(0xFF091219)
}

private val DarkScheme = darkColorScheme(
    primary = SecureMeshColors.Cyan,
    secondary = SecureMeshColors.Blue,
    tertiary = SecureMeshColors.Violet,
    background = SecureMeshColors.Graphite,
    surface = SecureMeshColors.Surface,
    surfaceVariant = SecureMeshColors.SurfaceHigh,
    primaryContainer = SecureMeshColors.SurfaceBright,
    secondaryContainer = SecureMeshColors.SurfaceHigh,
    onPrimary = Color(0xFF00232C),
    onSecondary = Color(0xFF08172F),
    onTertiary = Color(0xFF1A1031),
    onBackground = SecureMeshColors.Text,
    onSurface = SecureMeshColors.Text,
    onSurfaceVariant = SecureMeshColors.TextSecondary,
    outline = SecureMeshColors.Divider,
    error = SecureMeshColors.Critical,
)

private val SecureMeshTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 39.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.35).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 29.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)

private val SecureMeshShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(17.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SecureMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = SecureMeshTypography, shapes = SecureMeshShapes, content = content)
}
