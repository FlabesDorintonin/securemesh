package dev.securemesh.commander.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * SecureMesh operator experience. This is a UI shell, not a replacement for Android:
 * networking remains behind ViewModel -> Repository -> Transport.
 */
@Composable
fun SecureMeshBootSequence(modifier: Modifier = Modifier, onFinished: () -> Unit) {
    var stage by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        stage = 1
        delay(320)
        stage = 2
        delay(380)
        stage = 3
        delay(430)
        onFinished()
    }
    Box(modifier.fillMaxSize().background(SecureMeshColors.Graphite), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            MeshBootGlyph(stage)
            AnimatedVisibility(visible = stage >= 2, enter = fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 5 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SECUREMESH", color = SecureMeshColors.Text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text("ЛОКАЛЬНАЯ СЕТЬ", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
            AnimatedVisibility(visible = stage >= 3, enter = fadeIn(tween(240)) + scaleIn(initialScale = .98f)) {
                StatusChip("СИСТЕМА ГОТОВА", SecureMeshColors.Healthy)
            }
        }
    }
}

@Composable
private fun MeshBootGlyph(stage: Int) {
    val progress by animateFloatAsState(
        targetValue = when (stage) { 0 -> 0f; 1 -> .34f; 2 -> .74f; else -> 1f },
        animationSpec = spring(dampingRatio = .86f, stiffness = 260f),
        label = "mesh-boot-progress",
    )
    Canvas(Modifier.size(122.dp)) {
        val c = center
        val r = size.minDimension * .34f
        val points = listOf(
            Offset(c.x, c.y - r),
            Offset(c.x + r * .88f, c.y + r * .50f),
            Offset(c.x - r * .88f, c.y + r * .50f),
        )
        drawCircle(SecureMeshColors.Cyan.copy(alpha = .12f * progress), size.minDimension * (.24f + .18f * progress), c, style = Stroke(width = 2.dp.toPx()))
        if (progress > .22f) {
            val alpha = ((progress - .22f) / .78f).coerceIn(0f, 1f)
            drawLine(SecureMeshColors.Cyan.copy(alpha = .52f * alpha), points[0], points[1], 2.dp.toPx(), StrokeCap.Round)
            drawLine(SecureMeshColors.Cyan.copy(alpha = .52f * alpha), points[1], points[2], 2.dp.toPx(), StrokeCap.Round)
            drawLine(SecureMeshColors.Blue.copy(alpha = .46f * alpha), points[2], points[0], 2.dp.toPx(), StrokeCap.Round)
        }
        points.forEachIndexed { index, p ->
            val threshold = .18f + index * .16f
            val alpha = ((progress - threshold) * 4f).coerceIn(0f, 1f)
            drawCircle(if (index == 0) SecureMeshColors.CyanHot else SecureMeshColors.Cyan, 7.dp.toPx() * alpha, p)
            drawCircle(SecureMeshColors.Cyan.copy(alpha = .14f * alpha), 14.dp.toPx() * alpha, p)
        }
        drawCircle(SecureMeshColors.Text.copy(alpha = progress), 5.dp.toPx() * progress, c)
    }
}

@Composable
fun OsScreenHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = SecureMeshColors.Text, fontWeight = FontWeight.ExtraBold)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SecureMeshColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun OsHeroCard(
    eyebrow: String,
    title: String,
    subtitle: String,
    accent: Color = SecureMeshColors.Cyan,
    modifier: Modifier = Modifier,
    status: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Surface(modifier.fillMaxWidth(), color = SecureMeshColors.SurfaceHigh, shape = MaterialTheme.shapes.extraLarge, border = BorderStroke(1.dp, accent.copy(alpha = .26f)), shadowElevation = 7.dp) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(eyebrow.uppercase(), color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                if (status != null) StatusChip(status, accent)
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SecureMeshColors.TextSecondary)
            content?.let { Spacer(Modifier.height(2.dp)); it() }
        }
    }
}

@Composable
fun OsActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PressScaleSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 104.dp),
        enabled = enabled,
        color = SecureMeshColors.SurfaceHigh,
        border = BorderStroke(1.dp, if (enabled) accent.copy(alpha = .22f) else SecureMeshColors.Divider.copy(alpha = .65f)),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(shape = CircleShape, color = accent.copy(alpha = if (enabled) .13f else .06f)) {
                Icon(icon, contentDescription = null, tint = if (enabled) accent else SecureMeshColors.Muted, modifier = Modifier.padding(9.dp).size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (enabled) SecureMeshColors.Text else SecureMeshColors.Muted)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = SecureMeshColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun OsStat(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = SecureMeshColors.Surface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .78f))) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            AnimatedContent(targetState = value, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "os-stat") { current ->
                Text(current, color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ConnectionStepStrip(currentStep: Int, modifier: Modifier = Modifier) {
    val labels = listOf("Поиск", "Код", "Готово")
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            val active = index <= currentStep
            val color = if (active) SecureMeshColors.Cyan else SecureMeshColors.Divider
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(24.dp), shape = CircleShape, color = if (active) color.copy(alpha = .18f) else SecureMeshColors.SurfaceHigh, border = BorderStroke(1.dp, color.copy(alpha = if (active) .72f else .55f))) {
                    Box(contentAlignment = Alignment.Center) { Text((index + 1).toString(), color = if (active) SecureMeshColors.CyanHot else SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(5.dp))
                Text(label, color = if (active) SecureMeshColors.TextSecondary else SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            if (index < labels.lastIndex) {
                Box(Modifier.weight(1f).padding(horizontal = 7.dp).height(1.dp).background(if (index < currentStep) SecureMeshColors.Cyan.copy(alpha = .55f) else SecureMeshColors.Divider))
            }
        }
    }
}

@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier, activeColor: Color = SecureMeshColors.Cyan) {
    val bars = when { rssi >= -60 -> 4; rssi >= -72 -> 3; rssi >= -85 -> 2; else -> 1 }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        (1..4).forEach { index ->
            Box(Modifier.width(3.dp).height((4 + index * 3).dp).background(if (index <= bars) activeColor else SecureMeshColors.Divider, CircleShape))
        }
    }
}
