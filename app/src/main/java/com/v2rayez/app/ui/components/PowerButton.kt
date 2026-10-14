package com.v2rayez.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v2rayez.app.ui.theme.LocalReduceMotion

/**
 * Большая круглая кнопка питания.
 * - connected: мягкое свечение и расходящиеся кольца (без анимации — статичное свечение);
 * - working: вращающаяся дуга вокруг кнопки (подготовка/подключение);
 * - нажатие: пружинящее «вдавливание»; цвет плавно перетекает между состояниями.
 * Весь компонент занимает diameter * 1.3 — запас под кольца и дугу.
 */
@Composable
fun PowerButton(
    color: Color,
    connected: Boolean,
    working: Boolean,
    enabled: Boolean,
    diameter: Dp,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = LocalReduceMotion.current
) {
    val tint by animateColorAsState(color, tween(450), label = "powerTint")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "powerScale"
    )

    Box(modifier.size(diameter * 1.3f), contentAlignment = Alignment.Center) {
        if (connected) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(tint.copy(alpha = 0.10f), radius = size.minDimension / 2f * 0.9f)
            }
            if (!reduceMotion) PulseRings(tint, Modifier.fillMaxSize())
        }
        if (working) SpinnerArc(tint, reduceMotion, Modifier.fillMaxSize())

        Box(
            Modifier
                .size(diameter)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .alpha(if (enabled || working || connected) 1f else 0.55f)
                .shadow(
                    elevation = if (enabled || connected) 18.dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = tint,
                    spotColor = tint
                )
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.12f), lerp(tint, Color.Black, 0.28f))))
                .border(1.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                .semantics { contentDescription = description }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            PowerGlyph(Modifier.size(diameter * 0.4f), Color.White.copy(alpha = if (working) 0.6f else 1f))
        }
    }
}

/** Значок питания: дуга с разрывом сверху и вертикальная черта. */
@Composable
fun PowerGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.11f
        val r = size.minDimension * 0.36f
        val c = Offset(center.x, center.y + r * 0.12f)
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(c.x, c.y - r * 1.1f),
            end = Offset(c.x, c.y - r * 0.05f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun PulseRings(color: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "rings")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "phase"
    )
    Canvas(modifier) {
        val outer = size.minDimension / 2f
        val core = outer / 1.3f
        for (k in 0..1) {
            val q = (phase + k * 0.5f) % 1f
            drawCircle(
                color = color.copy(alpha = (1f - q) * 0.30f),
                radius = core + (outer - core) * q,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
private fun SpinnerArc(color: Color, reduceMotion: Boolean, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "angle"
    )
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val r = size.minDimension / 2f / 1.3f + 10.dp.toPx()
        drawArc(
            color = color,
            startAngle = if (reduceMotion) -90f else angle,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}
