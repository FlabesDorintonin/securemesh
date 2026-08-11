package dev.securemesh.commander.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Motion language shared by the presentation layer.
 * Springs are deliberately restrained: the UI should feel physical, not bouncy or toy-like.
 */
object SecureMeshMotion {
    const val Fast = 150
    const val Medium = 240
    const val Slow = 380

    const val PressScale = 0.975f
    const val GentleDamping = 0.88f
    const val GentleStiffness = 520f
}

@Composable
fun PressScaleSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = SecureMeshColors.SurfaceHigh,
    shape: Shape = MaterialTheme.shapes.large,
    border: BorderStroke? = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .72f)),
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) SecureMeshMotion.PressScale else 1f,
        animationSpec = spring(
            dampingRatio = SecureMeshMotion.GentleDamping,
            stiffness = SecureMeshMotion.GentleStiffness,
        ),
        label = "press-scale",
    )
    val elevation by animateDpAsState(
        targetValue = if (pressed && enabled) 1.dp else 5.dp,
        animationSpec = spring(dampingRatio = .9f, stiffness = 600f),
        label = "press-elevation",
    )

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        color = color,
        shape = shape,
        border = border,
        shadowElevation = elevation,
    ) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
fun StaggeredReveal(
    visible: Boolean,
    delayMillis: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(SecureMeshMotion.Medium, delayMillis = delayMillis)) +
            slideInVertically(tween(SecureMeshMotion.Slow, delayMillis = delayMillis)) { it / 8 } +
            scaleIn(tween(SecureMeshMotion.Slow, delayMillis = delayMillis), initialScale = .985f),
        exit = fadeOut(tween(SecureMeshMotion.Fast)) +
            slideOutVertically(tween(SecureMeshMotion.Fast)) { -it / 12 } +
            scaleOut(tween(SecureMeshMotion.Fast), targetScale = .99f),
    ) {
        content()
    }
}

@Composable
fun SoftGlowSurface(
    modifier: Modifier = Modifier,
    accent: Color = SecureMeshColors.Cyan,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = accent.copy(alpha = .08f),
        shape = CircleShape,
        border = BorderStroke(1.dp, accent.copy(alpha = .18f)),
    ) {
        Box(Modifier.fillMaxSize().alpha(.98f), content = content)
    }
}
