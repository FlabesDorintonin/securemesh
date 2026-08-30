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
    val Graphite = Color(0xFF030A10)
    val GraphiteSoft = Color(0xFF06131C)
    val Surface = Color(0xFF0A1822)
    val SurfaceHigh = Color(0xFF102733)
    val SurfaceBright = Color(0xFF173A49)
    val Cyan = Color(0xFF35D9FF)
    val CyanHot = Color(0xFF74EAFF)
    val Blue = Color(0xFF6E83FF)
    val Violet = Color(0xFFA56EFF)
    val Healthy = Color(0xFF56F0A7)
    val Warning = Color(0xFFFFC861)
    val Critical = Color(0xFFFF637D)
    val Muted = Color(0xFF8EA7B6)
    val Text = Color(0xFFF6FBFF)
    val TextSecondary = Color(0xFFC3D4DE)
    val Divider = Color(0xFF1C3A49)
    val BubbleIncoming = Color(0xFF102631)
    val BubbleOutgoing = Color(0xFF0E4055)
    val Navigation = Color(0xFF07151E)
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
    onPrimary = Color(0xFF002631),
    onSecondary = Color(0xFF071B34),
    onTertiary = Color(0xFF20003B),
    onBackground = SecureMeshColors.Text,
    onSurface = SecureMeshColors.Text,
    onSurfaceVariant = SecureMeshColors.TextSecondary,
    outline = SecureMeshColors.Divider,
    error = SecureMeshColors.Critical,
)

private val SecureMeshTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp,
        lineHeight = 41.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 29.sp,
        lineHeight = 35.sp,
        letterSpacing = (-0.4).sp,
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
