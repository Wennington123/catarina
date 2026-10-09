package dev.catarina.assistant.ui.mascot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.catarina.assistant.state.Phase
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

// ---- Cores dos efeitos desenhados por cima do sprite ----

private val Ring = Color(0xFFE5527E)
private val Dots = Color(0xFF2B1B12)
private val Confetti = listOf(
    Color(0xFFF6C445),
    Color(0xFF5FA8DC),
    Color(0xFFE5527E),
    Color(0xFF63B96F),
)
private val TAU = (2.0 * PI).toFloat()

/**
 * Fase -> nomes de animacao do `mascot_config.json`, em ordem de preferencia.
 * Se a fase ainda nao tem frame proprio, cai no proximo nome que existir — hoje
 * `thinking`, `celebrating` e `booting` caem em `idle`/`listening`. Quando esses
 * frames existirem no sheet, basta adicionar a animacao no JSON: nada muda aqui.
 */
private fun animationKeys(phase: Phase): List<String> = when (phase) {
    Phase.IDLE -> listOf("idle")
    Phase.BOOTING -> listOf("booting", "idle")
    Phase.LISTENING -> listOf("listening", "idle")
    Phase.HEARING -> listOf("hearing", "listening", "idle")
    Phase.THINKING -> listOf("thinking", "listening", "idle")
    Phase.SPEAKING -> listOf("talking", "idle")
    Phase.CELEBRATING -> listOf("celebrating", "listening", "idle")
    Phase.SAD -> listOf("sad", "idle")
}

/**
 * A bebezinha Catarina, desenhada a partir do sprite sheet (`mascote_anim/`):
 * o frame vem do JSON de animacoes e e ampliado sem suavizar
 * (`FilterQuality.None`), o que mantem o pixel art duro. Os efeitos que
 * dependem de estado (anel de escuta, pontinhos de "pensando", anel de
 * progresso do download e confetti) seguem desenhados por codigo, por cima.
 */
@Composable
fun BabyMascot(phase: Phase, progress: Float?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sprite = remember { MascotSprite.load(context) }
    val phaseKey = phase.name

    val animation = remember(sprite, phaseKey) {
        sprite?.animation(*animationKeys(phase).toTypedArray())
    }

    var frameSlot by remember(animation) { mutableIntStateOf(0) }

    LaunchedEffect(animation) {
        frameSlot = 0
        val frames = animation?.frames ?: return@LaunchedEffect
        if (frames.size <= 1) return@LaunchedEffect
        val step = (1000L / animation.frameRate.coerceAtLeast(1)).coerceAtLeast(60L)
        var index = 0
        while (true) {
            delay(step)
            index = (index + 1) % frames.size
            frameSlot = index
        }
    }

    val effectClock = rememberInfiniteTransition(label = "efeitos")
    val clock by effectClock.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "clock",
    )

    Canvas(modifier.aspectRatio(1f)) {
        val s = size.minDimension
        val cx = size.width / 2f

        val frames = animation?.frames
        val frame = if (frames != null && sprite != null) frames[frameSlot % frames.size] else null
        if (sprite != null && frame != null) {
            drawImage(
                image = sprite.image,
                srcOffset = IntOffset(frame * sprite.frameSize, 0),
                srcSize = IntSize(sprite.frameSize, sprite.frameSize),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.None,
            )
        }

        when (phase) {
            Phase.LISTENING, Phase.HEARING -> halo(clock, s, cx)
            Phase.THINKING -> thinkingDots(s, cx, clock)
            Phase.BOOTING -> bootRing(progress, clock, s, cx)
            else -> Unit
        }
        if (phase == Phase.CELEBRATING) confetti(clock, size.width, size.height, s)
    }
}

// ---- Efeitos ----

/** Anel que pulsa enquanto ela esta ouvindo. */
private fun DrawScope.halo(clock: Float, s: Float, cx: Float) {
    drawCircle(
        Ring.copy(alpha = (1f - clock) * 0.45f),
        radius = (0.42f + 0.04f * clock) * s,
        center = Offset(cx, 0.50f * s),
        style = Stroke(0.010f * s),
    )
}

private fun DrawScope.thinkingDots(s: Float, cx: Float, clock: Float) {
    for (i in 0..2) {
        val p = (sin(TAU * clock - i * 0.9f) + 1f) / 2f
        drawCircle(
            Dots.copy(alpha = 0.30f + 0.70f * p),
            radius = (0.010f + 0.012f * p) * s,
            center = Offset(
                cx + 0.30f * s + i * 0.045f * s,
                0.16f * s - i * 0.038f * s + (1f - p) * 0.016f * s,
            ),
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

/** Anel de progresso do download dos pesos (indeterminado quando `progress` e null). */
private fun DrawScope.bootRing(progress: Float?, clock: Float, s: Float, cx: Float) {
    val c = Offset(cx, 0.50f * s)
    val r = 0.46f * s
    drawCircle(Ring.copy(alpha = 0.15f), radius = r, center = c, style = Stroke(0.014f * s))
    val sweep = progress?.let { 360f * it.coerceIn(0f, 1f) } ?: (60f + 40f * sin(TAU * clock))
    drawArc(
        color = Ring,
        startAngle = -90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(c.x - r, c.y - r),
        size = Size(2f * r, 2f * r),
        style = Stroke(0.014f * s, cap = StrokeCap.Round),
    )
}
