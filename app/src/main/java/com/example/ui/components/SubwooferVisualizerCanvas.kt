package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.AcousticFrameTelemetry
import com.example.model.EngineStatus
import com.example.ui.theme.LocalSubPulseColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 60fps tactile acoustic subwoofer diaphragm & 32-band low-frequency radial FFT visualizer.
 * Physically reacts to sustained Sub-Bass (Ultrasonic Cyan) and percussive Kick shocks (Electric Coral).
 */
@Composable
fun SubwooferVisualizerCanvas(
    telemetry: AcousticFrameTelemetry,
    status: EngineStatus,
    crossoverHz: Float,
    modifier: Modifier = Modifier
) {
    val signalColors = LocalSubPulseColors.current
    val colorScheme = MaterialTheme.colorScheme
    val isActive = status == EngineStatus.RUNNING

    // Ambient breathing when standing by
    val infiniteTransition = rememberInfiniteTransition(label = "standby_pulse")
    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_breath"
    )
    val scanAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan_angle"
    )

    // Smooth bass cone excursion
    val smoothBass by animateFloatAsState(
        targetValue = if (isActive) telemetry.bassDepth else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "smooth_bass"
    )
    val smoothKick by animateFloatAsState(
        targetValue = if (isActive) telemetry.kickProbability else 0f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 680f),
        label = "smooth_kick"
    )

    // Expanding shockwave ring fired on every detected Kick onset
    val kickShockwave = remember { Animatable(1f) }
    LaunchedEffect(telemetry.kickCount) {
        if (isActive && telemetry.kickCount > 0L) {
            kickShockwave.snapTo(0f)
            kickShockwave.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
            )
        }
    }

    val freqDisplay = if (isActive && telemetry.dominantLowFreqHz >= 20f) {
        "${telemetry.dominantLowFreqHz.toInt()} Hz"
    } else {
        "-- Hz"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.12f)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        colorScheme.surfaceContainerHigh.copy(alpha = 0.75f),
                        colorScheme.surface,
                        colorScheme.background
                    )
                )
            )
            .border(
                width = 1.dp,
                color = if (isActive) {
                    signalColors.bass.copy(alpha = 0.25f + smoothBass * 0.35f)
                } else {
                    colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(28.dp)
            )
            .semantics {
                contentDescription = "Real-time acoustic subwoofer visualizer at $freqDisplay"
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val maxRadius = min(size.width, size.height) * 0.45f

            // 1. Sub-Bass radial atmospheric glow
            val bassAuraRadius = maxRadius * (0.68f + smoothBass * 0.36f + if (!isActive) idleBreath * 0.05f else 0f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        signalColors.bass.copy(alpha = if (isActive) 0.14f + smoothBass * 0.28f else 0.07f + idleBreath * 0.04f),
                        signalColors.bassDeep.copy(alpha = if (isActive) 0.06f + smoothBass * 0.12f else 0.02f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = bassAuraRadius.coerceAtLeast(1f)
                ),
                radius = bassAuraRadius,
                center = center
            )

            // 2. Kick transient radial impact flash
            if (isActive && smoothKick > 0.15f) {
                val kickGlowR = maxRadius * (0.42f + smoothKick * 0.32f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            signalColors.kick.copy(alpha = smoothKick * 0.34f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = kickGlowR.coerceAtLeast(1f)
                    ),
                    radius = kickGlowR,
                    center = center
                )
            }

            // 3. Expanding physical Kick Shockwave ring
            if (isActive && kickShockwave.value < 0.99f) {
                val p = kickShockwave.value
                val waveRadius = maxRadius * (0.34f + p * 0.68f)
                val waveAlpha = (1f - p) * 0.85f
                drawCircle(
                    color = signalColors.kickBright.copy(alpha = waveAlpha),
                    radius = waveRadius,
                    center = center,
                    style = Stroke(width = (5.dp.toPx() * (1f - p * 0.65f)).coerceAtLeast(1.5f))
                )
            }

            // 4. Outer precision radial 32-band FFT spectrum (22 Hz – 380 Hz)
            val bins = telemetry.spectrumBins
            val binCount = bins.size.coerceAtLeast(1)
            val baseSpectrumRadius = maxRadius * 0.72f
            val maxBarLen = maxRadius * 0.26f
            // Crossover index mapping (approx log index in 22..380Hz)
            val crossoverRatio = ((crossoverHz - 22f) / 358f).coerceIn(0.08f, 0.45f)

            for (i in 0 until binCount) {
                val frac = i.toFloat() / binCount.toFloat()
                // Sweep symmetrically from bottom-left around top to bottom-right (270 deg arc)
                val angleDeg = 135f + frac * 270f
                val rad = angleDeg * (PI / 180.0)
                val cosA = cos(rad).toFloat()
                val sinA = sin(rad).toFloat()

                val mag = if (isActive) {
                    bins[i].coerceIn(0f, 1f)
                } else {
                    0.06f + 0.05f * sin((scanAngle * 0.04f) + i * 0.4f).coerceAtLeast(0f)
                }

                val barLen = (4.dp.toPx() + mag * maxBarLen)
                val start = Offset(
                    center.x + cosA * baseSpectrumRadius,
                    center.y + sinA * baseSpectrumRadius
                )
                val end = Offset(
                    center.x + cosA * (baseSpectrumRadius + barLen),
                    center.y + sinA * (baseSpectrumRadius + barLen)
                )

                val barColor = when {
                    frac <= crossoverRatio -> signalColors.bass.copy(alpha = 0.45f + mag * 0.55f)
                    frac <= crossoverRatio + 0.32f -> signalColors.kick.copy(alpha = 0.40f + mag * 0.60f)
                    else -> signalColors.actuator.copy(alpha = 0.30f + mag * 0.55f)
                }

                drawLine(
                    color = barColor,
                    start = start,
                    end = end,
                    strokeWidth = 3.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 5. Concentric Subwoofer Diaphragm Rib Rings
            val coneExpansion = 1f + smoothBass * 0.10f + smoothKick * 0.08f + if (!isActive) idleBreath * 0.015f else 0f
            val ringRadii = floatArrayOf(0.62f, 0.52f, 0.42f, 0.31f)
            ringRadii.forEachIndexed { idx, factor ->
                val r = maxRadius * factor * coneExpansion
                val ringColor = when (idx) {
                    0 -> signalColors.bass.copy(alpha = 0.25f + smoothBass * 0.55f)
                    1 -> signalColors.bass.copy(alpha = 0.16f + smoothBass * 0.35f)
                    2 -> signalColors.kick.copy(alpha = 0.18f + smoothKick * 0.55f)
                    else -> colorScheme.outline.copy(alpha = 0.7f)
                }
                drawCircle(
                    color = ringColor,
                    radius = r,
                    center = center,
                    style = Stroke(width = if (idx == 0) 2.2.dp.toPx() else 1.2.dp.toPx())
                )
            }

            // 6. Inner Acoustic Dust Cap Core
            val capRadius = maxRadius * 0.29f * (1f + smoothKick * 0.09f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colorScheme.surfaceContainerHigh,
                        colorScheme.background
                    ),
                    center = center,
                    radius = capRadius.coerceAtLeast(1f)
                ),
                radius = capRadius,
                center = center
            )
            drawCircle(
                color = if (telemetry.isSidechainDucking && isActive) {
                    signalColors.kick
                } else if (isActive) {
                    signalColors.bass.copy(alpha = 0.65f)
                } else {
                    colorScheme.outline
                },
                radius = capRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Center Live Frequency & Telemetry Readout
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = freqDisplay,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isActive) colorScheme.onSurface else colorScheme.onSurfaceVariant
            )
            Text(
                text = when {
                    !isActive -> "STANDBY"
                    telemetry.isSidechainDucking -> "KICK PUNCH"
                    telemetry.bassDepth > 0.22f -> "SUB LOCK"
                    else -> "LISTENING"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    !isActive -> colorScheme.onSurfaceVariant
                    telemetry.isSidechainDucking -> signalColors.kickBright
                    telemetry.bassDepth > 0.22f -> signalColors.bass
                    else -> signalColors.liveGreen
                }
            )
        }

        // Bottom Corner Telemetry Badges (BPM & DSP Latency)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TelemetryPill(
                label = if (isActive && telemetry.estimatedBpm > 0) "${telemetry.estimatedBpm} BPM" else "-- BPM",
                accent = signalColors.kick
            )
            TelemetryPill(
                label = if (isActive) {
                    String.format("%.2f ms DSP", telemetry.dspLatencyMs)
                } else {
                    "48 kHz STFT"
                },
                accent = signalColors.bass
            )
        }
    }
}

@Composable
private fun TelemetryPill(
    label: String,
    accent: Color
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.background.copy(alpha = 0.78f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colorScheme.onSurface
        )
    }
}
