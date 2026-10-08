package dev.catarina.assistant.ui.mascot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import dev.catarina.assistant.state.Phase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ---- Cores (calibre o rosto aqui: pele parda, olhos azuis, chiquinhas) ----

private val Skin = Color(0xFFA06A4A)
private val Hair = Color(0xFF2B1B12)
private val Eyes = Color(0xFF5FA8DC)
private val EyeGlint = Color.White
private val Blush = Color(0xFFD96C5F).copy(alpha = 0.32f)
private val Onesie = Color(0xFFF2B8C6)
private val OnesieDark = Color(0xFFE095A8)
private val Tie = Color(0xFFE5527E)
private val Mouth = Color(0xFF6B3226)
private val Ring = Color(0xFFE5527E)
private val Shadow = Color(0x28000000)
private val Confetti = listOf(
    Color(0xFFF6C445),
    Color(0xFF5FA8DC),
    Color(0xFFE5527E),
    Color(0xFF63B96F),
)

// ---- Geometria (frações do lado do canvas quadrado) ----

private const val HEAD_CY = 0.40f
private const val HEAD_R = 0.26f
private val TAU = (2.0 * PI).toFloat()

/**
 * A bebezinha Catarina: desenhada e animada 100% por código (Canvas Compose).
 * Cada [Phase] tem uma reação própria — piscar/respirar em repouso, anel
 * pulsante ouvindo a wake word, boca sincronizada com o TTS, pulo com
 * confetti quando executa uma ação, carinha triste em erro/recusa e anel de
 * progresso durante o download dos pesos.
 */
@Composable
fun BabyMascot(phase: Phase, progress: Float?, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "mascote")
    val clock by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "clock",
    )
    val breathe by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3400, easing = LinearEasing)),
        label = "breathe",
    )
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 3800
                1f at 0
                0.06f at 3350
                1f at 3480
                1f at 3800
            },
        ),
        label = "blink",
    )

    val celebrating = phase == Phase.CELEBRATING
    val sad = phase == Phase.SAD
    val hearing = phase == Phase.HEARING
    val thinking = phase == Phase.THINKING
    val speaking = phase == Phase.SPEAKING

    val armUp by animateFloatAsState(
        if (celebrating) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "armUp",
    )
    val jump by animateFloatAsState(
        if (celebrating) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "jump",
    )
    val sadAmt by animateFloatAsState(if (sad) 1f else 0f, spring(stiffness = Spring.StiffnessLow), label = "sad")
    val ringAmt by animateFloatAsState(
        if (phase == Phase.LISTENING || hearing) 1f else 0f,
        tween(300),
        label = "ring",
    )
    val hearingAmt by animateFloatAsState(if (hearing) 1f else 0f, tween(200), label = "hearing")
    val thinkingAmt by animateFloatAsState(if (thinking) 1f else 0f, tween(200), label = "thinking")
    val speakingAmt by animateFloatAsState(if (speaking) 1f else 0f, tween(150), label = "speaking")

    Canvas(modifier.aspectRatio(1f)) {
        val s = size.minDimension
        val cx = size.width / 2f

        // sombra no chão (clareia quando ela pula)
        drawCircle(
            Shadow,
            radius = s * 0.19f * (1f - 0.25f * jump),
            center = Offset(cx, s * 0.94f),
        )

        withTransform({
            rotate(sadAmt * 5f, Offset(cx, s * 0.90f))
            translate(0f, -jump * s * 0.10f)
            translate(0f, -0.006f * s * speakingAmt * (0.5f - 0.5f * cos(TAU * clock * 2f)))
            val br = 1f + 0.018f * (0.5f - 0.5f * cos(TAU * breathe))
            scale(br, br, Offset(cx, s * 0.92f))
        }) {
            halo(ringAmt, clock, s, cx)
            pigtail(s, cx, clock, sadAmt, speakingAmt * 5f + armUp * 4f, left = true)
            pigtail(s, cx, clock, sadAmt, speakingAmt * 5f + armUp * 4f, left = false)
            body(s, cx, armUp)
            head(s, cx)
            hair(s, cx)
            ties(s, cx)
            eyes(s, cx, blink, hearingAmt, thinkingAmt)
            blush(s, cx)
            mouth(s, cx, clock, sadAmt, speakingAmt)
            thinkingDots(s, cx, thinkingAmt, clock)
        }

        if (celebrating) confetti(clock, size.width, size.height, s)
        if (phase == Phase.BOOTING) bootRing(progress, clock, s, cx)
    }
}

// ---- Partes do desenho ----

private fun DrawScope.halo(ringAmt: Float, clock: Float, s: Float, cx: Float) {
    if (ringAmt <= 0.01f) return
    drawCircle(
        Ring.copy(alpha = ringAmt * (1f - clock) * 0.50f),
        radius = (0.435f + 0.035f * clock) * s,
        center = Offset(cx, 0.50f * s),
        style = Stroke(0.008f * s),
    )
}

private fun DrawScope.pigtail(
    s: Float,
    cx: Float,
    clock: Float,
    sadAmt: Float,
    swingExtra: Float,
    left: Boolean,
) {
    val sign = if (left) -1f else 1f
    val tiePoint = Offset(cx + sign * HEAD_R * s, HEAD_CY * s - 0.030f * s)
    val swing = (3f + swingExtra) * sin(TAU * clock) * sign
    val droop = sadAmt * 18f * sign
    val off = rot(Offset(sign * 0.115f * s, -0.010f * s), swing + droop)
    drawCircle(Hair, radius = s * 0.095f, center = tiePoint + off)
    drawCircle(Hair, radius = s * 0.055f, center = tiePoint + off * 0.55f)
}

private fun DrawScope.ties(s: Float, cx: Float) {
    val tieY = HEAD_CY * s - 0.030f * s
    for (sign in listOf(-1f, 1f)) {
        val c = Offset(cx + sign * HEAD_R * s, tieY)
        drawCircle(Tie, radius = s * 0.030f, center = c)
        drawCircle(Tie, radius = s * 0.016f, center = c + Offset(-0.026f * s * sign, -0.008f * s))
        drawCircle(Tie, radius = s * 0.016f, center = c + Offset(0.026f * s * sign, -0.008f * s))
    }
}

private fun DrawScope.body(s: Float, cx: Float, armUp: Float) {
    drawRoundRect(
        Onesie,
        topLeft = Offset(cx - 0.17f * s, 0.60f * s),
        size = Size(0.34f * s, 0.30f * s),
        cornerRadius = CornerRadius(0.10f * s),
    )
    for (sign in listOf(-1f, 1f)) {
        val shoulder = Offset(cx + sign * 0.155f * s, 0.695f * s)
        val handDown = Offset(cx + sign * 0.245f * s, 0.825f * s)
        val handUp = Offset(cx + sign * 0.272f * s, 0.565f * s)
        val hand = handDown + (handUp - handDown) * armUp
        drawLine(OnesieDark, shoulder, hand, strokeWidth = 0.050f * s, cap = StrokeCap.Round)
        drawCircle(Skin, radius = 0.032f * s, center = hand)
    }
}

private fun DrawScope.head(s: Float, cx: Float) {
    drawCircle(Skin, radius = HEAD_R * s, center = Offset(cx, HEAD_CY * s))
}

private fun DrawScope.hair(s: Float, cx: Float) {
    // casquete (metade de cima da cabeça)
    drawArc(
        Hair,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(cx - HEAD_R * s - 0.004f * s, (HEAD_CY - HEAD_R) * s - 0.004f * s),
        size = Size((HEAD_R + 0.004f) * 2f * s, (HEAD_R + 0.004f) * 2f * s),
    )
    // franja em scallop (bump central + têmporas, fora da coluna dos olhos)
    drawCircle(Hair, radius = 0.052f * s, center = Offset(cx, 0.375f * s))
    drawCircle(Hair, radius = 0.045f * s, center = Offset(cx - 0.20f * s, 0.378f * s))
    drawCircle(Hair, radius = 0.045f * s, center = Offset(cx + 0.20f * s, 0.378f * s))
}

private fun DrawScope.eyes(
    s: Float,
    cx: Float,
    blink: Float,
    hearingAmt: Float,
    thinkingAmt: Float,
) {
    val wide = 1f + 0.12f * hearingAmt
    val eyeY = 0.445f * s
    val scleraR = 0.048f * s * wide
    val lookUp = 0.014f * s * thinkingAmt
    for (sign in listOf(-1f, 1f)) {
        val c = Offset(cx + sign * 0.100f * s, eyeY)
        withTransform({ scale(1f, blink, pivot = c) }) {
            drawCircle(EyeGlint, radius = scleraR, center = c)
            drawCircle(Eyes, radius = 0.034f * s, center = c + Offset(0f, lookUp))
            drawCircle(
                EyeGlint,
                radius = 0.011f * s,
                center = c + Offset(-0.012f * s, lookUp - 0.012f * s),
            )
        }
    }
}

private fun DrawScope.blush(s: Float, cx: Float) {
    drawCircle(Blush, radius = 0.036f * s, center = Offset(cx - 0.175f * s, 0.520f * s))
    drawCircle(Blush, radius = 0.036f * s, center = Offset(cx + 0.175f * s, 0.520f * s))
}

private fun DrawScope.mouth(
    s: Float,
    cx: Float,
    clock: Float,
    sadAmt: Float,
    speakingAmt: Float,
) {
    val my = 0.560f * s
    // sorriso <-> tristeza (o arco gira com o estado)
    drawArc(
        Mouth.copy(alpha = 1f - speakingAmt),
        startAngle = 25f + 180f * sadAmt,
        sweepAngle = 130f,
        useCenter = false,
        topLeft = Offset(cx - 0.052f * s, my - 0.040f * s),
        size = Size(0.104f * s, 0.080f * s),
        style = Stroke(0.013f * s, cap = StrokeCap.Round),
    )
    // falando: boca abre/fecha ("tagarela")
    if (speakingAmt > 0.01f) {
        val open = abs(sin(TAU * clock * 4f))
        val h = (0.018f + 0.052f * open) * s
        drawOval(
            Mouth.copy(alpha = speakingAmt),
            topLeft = Offset(cx - 0.038f * s, my - h / 2f),
            size = Size(0.076f * s, h),
        )
    }
}

private fun DrawScope.thinkingDots(s: Float, cx: Float, thinkingAmt: Float, clock: Float) {
    if (thinkingAmt <= 0.01f) return
    for (i in 0..2) {
        val p = (sin(TAU * clock - i * 0.9f) + 1f) / 2f
        val c = Offset(
            cx + 0.320f * s + i * 0.050f * s,
            0.150f * s - i * 0.042f * s + (1f - p) * 0.018f * s,
        )
        drawCircle(
            Hair.copy(alpha = thinkingAmt * (0.30f + 0.70f * p)),
            radius = (0.012f + 0.014f * p) * s,
            center = c,
        )
    }
}

private fun DrawScope.confetti(clock: Float, w: Float, h: Float, s: Float) {
    for (i in 0 until 12) {
        val fall = (clock * 0.85f + i / 12f) % 1f
        val x = ((i * 137 + 41) % 100) / 100f * w
        val y = fall * h
        val c = Offset(x, y)
        withTransform({ rotate((clock * 360f + i * 47f) % 360f, c) }) {
            drawRect(
                Confetti[i % Confetti.size],
                topLeft = Offset(x - 0.010f * s, y - 0.006f * s),
                size = Size(0.020f * s, 0.012f * s),
            )
        }
    }
}

private fun DrawScope.bootRing(progress: Float?, clock: Float, s: Float, cx: Float) {
    val c = Offset(cx, 0.50f * s)
    val r = 0.462f * s
    drawCircle(Ring.copy(alpha = 0.15f), radius = r, center = c, style = Stroke(0.016f * s))
    val sweep = progress?.let { 360f * it.coerceIn(0f, 1f) } ?: (60f + 40f * sin(TAU * clock))
    drawArc(
        Ring,
        startAngle = -90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(c.x - r, c.y - r),
        size = Size(2f * r, 2f * r),
        style = Stroke(0.016f * s, cap = StrokeCap.Round),
    )
}

private fun rot(v: Offset, deg: Float): Offset {
    val a = deg * (PI.toFloat() / 180f)
    val c = cos(a)
    val sn = sin(a)
    return Offset(v.x * c - v.y * sn, v.x * sn + v.y * c)
}
