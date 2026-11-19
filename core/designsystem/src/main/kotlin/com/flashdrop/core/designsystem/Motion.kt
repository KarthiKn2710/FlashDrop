package com.flashdrop.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Motion tokens: short, decelerating, never bouncy enough to feel playful. */
object FdMotion {
    const val SHORT = 180
    const val MEDIUM = 280
    const val LONG = 420

    /** Material 3 "emphasized decelerate": things arriving on screen. */
    val Enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Material 3 "emphasized accelerate": things leaving the screen. */
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/** Shrinks an element slightly while it is pressed, so touches feel physical. */
fun Modifier.pressScale(interaction: InteractionSource, pressedScale: Float = 0.97f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The outcome of a transfer: a tinted disc springs in, a check (or cross) draws itself, and on
 * success a single faint ring spreads out. Purely decorative for accessibility services; the
 * screen states the result in text.
 */
@Composable
fun ResultMark(success: Boolean, modifier: Modifier = Modifier, size: Dp = 88.dp) {
    val color = if (success) FdColors.Success else FdColors.Error
    val disc = remember { Animatable(0f) }
    val stroke = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    LaunchedEffect(success) {
        disc.snapTo(0f)
        stroke.snapTo(0f)
        ring.snapTo(0f)
        launch { disc.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 420f)) }
        delay(140)
        launch { stroke.animateTo(1f, tween(360, easing = FastOutSlowInEasing)) }
        if (success) {
            delay(220)
            ring.animateTo(1f, tween(900, easing = FdMotion.Enter))
        }
    }
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val radius = this.size.minDimension / 2f
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        if (ring.value in 0.001f..0.999f) {
            drawCircle(
                color = color.copy(alpha = 0.35f * (1f - ring.value)),
                radius = radius * (0.9f + 0.45f * ring.value),
                center = centre,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        val r = radius * 0.9f * disc.value
        drawCircle(color.copy(alpha = 0.16f), r, centre)
        drawCircle(color.copy(alpha = 0.55f), r, centre, style = Stroke(1.5.dp.toPx()))

        if (stroke.value > 0f) {
            // A check is one stroke; a cross is two, drawn one after the other.
            val strokes = if (success) {
                listOf(
                    Path().apply {
                        moveTo(centre.x - radius * 0.30f, centre.y + radius * 0.02f)
                        lineTo(centre.x - radius * 0.08f, centre.y + radius * 0.24f)
                        lineTo(centre.x + radius * 0.33f, centre.y - radius * 0.22f)
                    },
                )
            } else {
                val d = radius * 0.26f
                listOf(
                    Path().apply { moveTo(centre.x - d, centre.y - d); lineTo(centre.x + d, centre.y + d) },
                    Path().apply { moveTo(centre.x + d, centre.y - d); lineTo(centre.x - d, centre.y + d) },
                )
            }
            val partial = Path()
            strokes.forEachIndexed { i, path ->
                val share = (stroke.value * strokes.size - i).coerceIn(0f, 1f)
                if (share > 0f) {
                    val measure = PathMeasure().apply { setPath(path, false) }
                    measure.getSegment(0f, measure.length * share, partial, true)
                }
            }
            drawPath(partial, color, style = Stroke(width = radius * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * Concentric ripples spreading from an icon: the device is looking for (or waiting for)
 * nearby devices. Static and grey when [active] is false. Decorative for accessibility.
 */
@Composable
fun ScanningRadar(
    active: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    color: Color = FdColors.Accent,
    size: Dp = 160.dp,
    coreSize: Dp = 84.dp,
) {
    val tint = if (active) color else FdColors.TextSecondary
    val transition = rememberInfiniteTransition(label = "radar")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "radarPhase",
    )
    Box(modifier.size(size).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (active) {
            Canvas(Modifier.size(size)) {
                val max = this.size.minDimension / 2f
                val min = coreSize.toPx() / 2f
                for (i in 0 until RIPPLES) {
                    val t = (phase + i.toFloat() / RIPPLES) % 1f
                    val eased = FdMotion.Enter.transform(t)
                    drawCircle(
                        color = tint.copy(alpha = 0.30f * (1f - t)),
                        radius = min + (max - min) * eased,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }
        Box(
            Modifier.size(coreSize).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(coreSize * 0.43f))
        }
    }
}

private const val RIPPLES = 3
