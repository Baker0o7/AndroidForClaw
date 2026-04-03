package ai.openclaw.app.avatar

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

// Face colors
private val FaceLight = Color(0xFF6EC6FF)
private val FaceDark = Color(0xFF4A9FD9)
private val FaceStroke = Color(0xFF3A7FB0)
private val EyeColor = Color(0xFF1A3A5C)
private val PupilColor = Color(0xFF0D1F33)
private val EyeHighlight = Color(0xCCFFFFFF)
private val MouthColor = Color(0xFF1A3A5C)
private val BlushColor = Color(0x33FF6B9D)
private val RingColor = Color(0xFF6EC6FF)

@Composable
fun CanvasAvatarView(modifier: Modifier = Modifier) {
    val phase by AvatarStateHolder.phase.collectAsState()

    val inf = rememberInfiniteTransition(label = "avatar")

    // Breathing: gentle scale oscillation
    val breathScale = inf.animateFloat(
        initialValue = 0.97f, targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath",
    )

    // Blinking
    var blinkProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay((2500L..5000L).random())
            // Close eyes
            for (i in 1..5) { blinkProgress = i / 5f; delay(20) }
            delay(60)
            // Open eyes
            for (i in 4 downTo 0) { blinkProgress = i / 5f; delay(20) }
        }
    }

    // Talking: mouth open/close oscillation
    val talkCycle = inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "talk",
    )

    // Thinking: dots rotation
    val thinkRotation = inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "think",
    )

    // Listening: pulse ring
    val listenPulse = inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "listen",
    )

    // One-shot trigger animation
    var triggerAnim by remember { mutableStateOf<String?>(null) }
    val triggerProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        AvatarStateHolder.triggers.collect { trigger ->
            triggerAnim = trigger
            triggerProgress.snapTo(0f)
            triggerProgress.animateTo(1f, tween(800))
            triggerAnim = null
        }
    }

    // Smooth phase transition for eye position
    val eyeYOffset by animateFloatAsState(
        targetValue = when (phase) {
            AvatarPhase.Thinking -> -0.15f
            else -> 0f
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "eyeY",
    )

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val faceRadius = size.minDimension * 0.38f

            // Listening: outer pulse rings
            if (phase == AvatarPhase.Listening) {
                val r1 = faceRadius * (1.1f + listenPulse.value * 0.4f)
                val a1 = (1f - listenPulse.value) * 0.3f
                drawCircle(RingColor.copy(alpha = a1), r1, Offset(cx, cy), style = Stroke(3.dp.toPx()))
                val r2 = faceRadius * (1.1f + ((listenPulse.value + 0.5f) % 1f) * 0.4f)
                val a2 = (1f - ((listenPulse.value + 0.5f) % 1f)) * 0.2f
                drawCircle(RingColor.copy(alpha = a2), r2, Offset(cx, cy), style = Stroke(2.dp.toPx()))
            }

            // Talking: speech ripples
            if (phase == AvatarPhase.Talking) {
                val rippleT = talkCycle.value
                val r1 = faceRadius * (1.05f + rippleT * 0.2f)
                drawCircle(RingColor.copy(alpha = (1f - rippleT) * 0.25f), r1, Offset(cx, cy), style = Stroke(2.dp.toPx()))
            }

            // Apply breathing scale
            scale(breathScale.value, pivot = Offset(cx, cy)) {
                // Smile trigger: extra scale bounce
                val trigScale = if (triggerAnim == "smile" || triggerAnim == "celebrate") {
                    1f + sin(triggerProgress.value * PI.toFloat()) * 0.08f
                } else 1f
                scale(trigScale, pivot = Offset(cx, cy)) {
                    drawFace(cx, cy, faceRadius, phase, blinkProgress, talkCycle.value, eyeYOffset, triggerAnim, triggerProgress.value)
                }
            }

            // Thinking: rotating dots above head
            if (phase == AvatarPhase.Thinking) {
                val dotRadius = faceRadius * 0.06f
                val orbitRadius = faceRadius * 0.3f
                val dotCy = cy - faceRadius - faceRadius * 0.25f
                for (i in 0..2) {
                    val angle = Math.toRadians((thinkRotation.value + i * 120f).toDouble())
                    val dx = cx + orbitRadius * kotlin.math.cos(angle).toFloat()
                    val dy = dotCy + orbitRadius * 0.4f * sin(angle).toFloat()
                    val alpha = 0.4f + 0.6f * ((i + 1) / 3f)
                    drawCircle(EyeColor.copy(alpha = alpha), dotRadius, Offset(dx, dy))
                }
            }
        }
    }
}

private fun DrawScope.drawFace(
    cx: Float, cy: Float, r: Float,
    phase: AvatarPhase,
    blinkProgress: Float,
    talkCycle: Float,
    eyeYOffset: Float,
    trigger: String?,
    triggerProgress: Float,
) {
    // Face circle with gradient
    val faceGradient = Brush.radialGradient(
        colors = listOf(FaceLight, FaceDark),
        center = Offset(cx - r * 0.2f, cy - r * 0.2f),
        radius = r * 1.6f,
    )
    drawCircle(faceGradient, r, Offset(cx, cy))
    drawCircle(FaceStroke, r, Offset(cx, cy), style = Stroke(2.dp.toPx()))

    // Blush
    val blushR = r * 0.15f
    drawCircle(BlushColor, blushR, Offset(cx - r * 0.52f, cy + r * 0.15f))
    drawCircle(BlushColor, blushR, Offset(cx + r * 0.52f, cy + r * 0.15f))

    // Eyes
    val eyeSpacing = r * 0.32f
    val eyeY = cy - r * 0.1f + r * eyeYOffset
    val eyeW = r * 0.2f
    val eyeH = r * 0.24f

    // Surprise trigger: bigger eyes
    val eyeScale = if (trigger == "surprise") {
        1f + sin(triggerProgress * PI.toFloat()) * 0.4f
    } else if (phase == AvatarPhase.Listening) 1.15f else 1f

    for (side in listOf(-1f, 1f)) {
        val ex = cx + eyeSpacing * side
        val actualW = eyeW * eyeScale
        val actualH = eyeH * eyeScale * (1f - blinkProgress * 0.85f)

        // Eye white
        drawOval(
            Color.White,
            topLeft = Offset(ex - actualW, eyeY - actualH),
            size = Size(actualW * 2f, actualH * 2f),
        )
        // Eye border
        drawOval(
            EyeColor,
            topLeft = Offset(ex - actualW, eyeY - actualH),
            size = Size(actualW * 2f, actualH * 2f),
            style = Stroke(1.5f.dp.toPx()),
        )

        if (blinkProgress < 0.7f) {
            // Pupil
            val pupilR = actualW * 0.45f
            drawCircle(PupilColor, pupilR, Offset(ex, eyeY))
            // Highlight
            drawCircle(EyeHighlight, pupilR * 0.35f, Offset(ex + pupilR * 0.3f, eyeY - pupilR * 0.3f))
        }
    }

    // Mouth
    drawMouth(cx, cy, r, phase, talkCycle, trigger, triggerProgress)

    // Wave trigger: arm-like arc on the right side
    if (trigger == "wave") {
        val waveAngle = sin(triggerProgress * PI.toFloat() * 3) * 20f
        rotate(waveAngle, pivot = Offset(cx + r * 0.9f, cy + r * 0.3f)) {
            val armPath = Path().apply {
                moveTo(cx + r * 0.85f, cy + r * 0.3f)
                quadraticBezierTo(
                    cx + r * 1.3f, cy - r * 0.2f,
                    cx + r * 1.1f, cy - r * 0.5f,
                )
            }
            drawPath(armPath, EyeColor, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            // Hand dot
            drawCircle(FaceLight, r * 0.08f, Offset(cx + r * 1.1f, cy - r * 0.5f))
        }
    }

    // Nod trigger: the face bounces (handled externally via scale, but we add eyebrow raise)
    if (trigger == "nod") {
        val browLift = sin(triggerProgress * PI.toFloat()) * r * 0.08f
        val browY = eyeY - eyeH * 1.4f - browLift
        for (side in listOf(-1f, 1f)) {
            val bx = cx + eyeSpacing * side
            drawLine(
                EyeColor,
                start = Offset(bx - eyeW * 0.8f, browY),
                end = Offset(bx + eyeW * 0.8f, browY - r * 0.02f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun DrawScope.drawMouth(
    cx: Float, cy: Float, r: Float,
    phase: AvatarPhase,
    talkCycle: Float,
    trigger: String?,
    triggerProgress: Float,
) {
    val mouthY = cy + r * 0.3f
    val mouthW = r * 0.3f

    // Smile trigger override
    val isSmiling = trigger == "smile" || trigger == "celebrate"
    val smileAmount = if (isSmiling) sin(triggerProgress * PI.toFloat()) else 0f

    when {
        // Talking: animated open/close mouth
        phase == AvatarPhase.Talking && !isSmiling -> {
            val openAmount = 0.3f + talkCycle * 0.5f
            val mouthH = r * 0.12f * openAmount
            val mouthPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(cx - mouthW * 0.6f, mouthY - mouthH, cx + mouthW * 0.6f, mouthY + mouthH),
                        CornerRadius(mouthH),
                    )
                )
            }
            drawPath(mouthPath, MouthColor, style = Fill)
            // Tongue hint
            if (openAmount > 0.5f) {
                drawCircle(Color(0xFFE87D7D), mouthH * 0.5f, Offset(cx, mouthY + mouthH * 0.3f))
            }
        }
        // Smile (trigger or idle default)
        isSmiling || phase == AvatarPhase.Idle -> {
            val smileCurve = if (isSmiling) 0.12f + smileAmount * 0.08f else 0.1f
            val smilePath = Path().apply {
                moveTo(cx - mouthW, mouthY)
                quadraticBezierTo(cx, mouthY + r * smileCurve, cx + mouthW, mouthY)
            }
            drawPath(smilePath, MouthColor, style = Stroke(2.5f.dp.toPx(), cap = StrokeCap.Round))
        }
        // Thinking: small 'o' mouth
        phase == AvatarPhase.Thinking -> {
            drawCircle(MouthColor, r * 0.06f, Offset(cx, mouthY), style = Stroke(2.dp.toPx()))
        }
        // Listening: slight open mouth
        phase == AvatarPhase.Listening -> {
            val mouthPath = Path().apply {
                moveTo(cx - mouthW * 0.6f, mouthY)
                quadraticBezierTo(cx, mouthY + r * 0.06f, cx + mouthW * 0.6f, mouthY)
            }
            drawPath(mouthPath, MouthColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }

    // Surprise trigger: big O mouth
    if (trigger == "surprise" && triggerProgress < 0.8f) {
        val oSize = r * 0.1f * sin(triggerProgress / 0.8f * PI.toFloat())
        drawCircle(MouthColor, oSize, Offset(cx, mouthY), style = Fill)
    }

    // Sad trigger: downturned mouth
    if (trigger == "sad") {
        val sadAmount = sin(triggerProgress * PI.toFloat())
        val sadPath = Path().apply {
            moveTo(cx - mouthW * 0.7f, mouthY + r * 0.02f)
            quadraticBezierTo(cx, mouthY - r * 0.08f * sadAmount, cx + mouthW * 0.7f, mouthY + r * 0.02f)
        }
        drawPath(sadPath, MouthColor, style = Stroke(2.5f.dp.toPx(), cap = StrokeCap.Round))
    }
}
