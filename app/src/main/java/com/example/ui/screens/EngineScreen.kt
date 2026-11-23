package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AcousticFrameTelemetry
import com.example.model.EngineStatus
import com.example.model.HapticHardwareCapabilities
import com.example.ui.theme.LocalSubPulseColors

@Composable
fun EngineScreen(
    hardware: HapticHardwareCapabilities,
    telemetry: AcousticFrameTelemetry,
    status: EngineStatus,
    onPlayKickTest: () -> Unit,
    onPlayBassTest: () -> Unit,
    onPlayComboTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val signalColors = LocalSubPulseColors.current
    val isRunning = status == EngineStatus.RUNNING

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.nav_engine),
                style = MaterialTheme.typography.headlineMedium,
                color = colorScheme.onBackground
            )

            // 1. Hardware LRA Actuator Diagnostics Card
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(22.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PrecisionManufacturing,
                            contentDescription = null,
                            tint = signalColors.actuator,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.engine_lra_hardware),
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(signalColors.actuatorContainer)
                            .border(1.dp, signalColors.actuator.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "Active Tier: ${hardware.activeEngineTier}",
                            style = MaterialTheme.typography.labelLarge,
                            color = signalColors.actuatorOnContainer
                        )
                    }

                    SpecRow("Hardware Actuator", if (hardware.hasVibrator) "Detected" else "Unavailable", hardware.hasVibrator)
                    SpecRow("Android 16 PWLE Envelopes", if (hardware.supportsPwleEnvelopes) "Supported (${hardware.maxEnvelopePoints} pts)" else "Fallback Active", hardware.supportsPwleEnvelopes)
                    SpecRow("Amplitude Modulation", if (hardware.hasAmplitudeControl) "255-Step HD" else "Binary", hardware.hasAmplitudeControl)
                    SpecRow("LRA THUD / LOW_TICK Primitives", if (hardware.supportsThudPrimitive) "Native Hardware" else "Emulated", hardware.supportsThudPrimitive)
                    SpecRow(
                        "Resonant Frequency",
                        hardware.resonantFrequencyHz?.let { "${it.toInt()} Hz" } ?: "OEM Managed",
                        hardware.resonantFrequencyHz != null
                    )
                    SpecRow(
                        "Frequency Profile Range",
                        if (hardware.minFrequencyHz != null && hardware.maxFrequencyHz != null) {
                            "${hardware.minFrequencyHz.toInt()} – ${hardware.maxFrequencyHz.toInt()} Hz"
                        } else {
                            "Standard Sharpness Curve"
                        },
                        hardware.minFrequencyHz != null
                    )
                    SpecRow(
                        "Actuator Q-Factor",
                        hardware.qFactor?.let { String.format("%.2f", it) } ?: "Not Exposed",
                        hardware.qFactor != null
                    )
                }
            }

            // 2. Interactive Haptic Calibration Deck
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(22.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Vibration,
                            contentDescription = null,
                            tint = signalColors.kick,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.engine_calibration),
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "Fire calibrated envelopes directly to your LRA motor to verify distinct Kick chirp vs. Sub-Bass sustain feel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CalibrationButton(
                            label = stringResource(R.string.action_test_kick),
                            accent = signalColors.kick,
                            testTag = "test_kick_button",
                            onClick = onPlayKickTest,
                            modifier = Modifier.weight(1f)
                        )
                        CalibrationButton(
                            label = stringResource(R.string.action_test_bass),
                            accent = signalColors.bass,
                            testTag = "test_bass_button",
                            onClick = onPlayBassTest,
                            modifier = Modifier.weight(1f)
                        )
                        CalibrationButton(
                            label = stringResource(R.string.action_test_combo),
                            accent = signalColors.actuator,
                            testTag = "test_combo_button",
                            onClick = onPlayComboTest,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 3. Live 10-Feature MIR Neural Feature Vector Inspector
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(22.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Psychology,
                                contentDescription = null,
                                tint = signalColors.bass,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.engine_neural_features),
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                        }
                        Text(
                            text = if (isRunning) String.format("%.2f ms", telemetry.dspLatencyMs) else "IDLE",
                            style = MaterialTheme.typography.labelMedium,
                            color = signalColors.liveGreen
                        )
                    }

                    val f = telemetry.features
                    FeatureBarRow("Sub-Bass RMS (20–Crossover)", if (isRunning) f.subRms else 0f, signalColors.bass)
                    FeatureBarRow("Kick Punch Body (60–150Hz)", if (isRunning) f.kickBody else 0f, signalColors.kick)
                    FeatureBarRow("Bass Harmonics (145–320Hz)", if (isRunning) f.bassHarmonic else 0f, signalColors.bass)
                    FeatureBarRow("Beater Click (1.8–4.8kHz)", if (isRunning) f.beaterClick else 0f, signalColors.kick)
                    FeatureBarRow("SuperFlux Spectral Onset", if (isRunning) f.superFlux else 0f, signalColors.kick)
                    FeatureBarRow("HPSS Percussive Ratio", if (isRunning) f.hpssPercussive else 0f, signalColors.kick)
                    FeatureBarRow("HPSS Harmonic Sustain", if (isRunning) f.hpssHarmonic else 0f, signalColors.bass)
                    FeatureBarRow("Sub-180Hz Pitch Drop", if (isRunning) f.pitchDrop else 0f, signalColors.actuator)
                    FeatureBarRow("Attack Crest & Slope", if (isRunning) f.transientCrest else 0f, signalColors.kick)
                    FeatureBarRow("Sustain Stationarity", if (isRunning) f.sustainStationarity else 0f, signalColors.bass)
                }
            }

            // 4. Open-Source & 2026 Architecture Stack Card
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(22.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Memory,
                            contentDescription = null,
                            tint = signalColors.liveGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.engine_stack),
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface
                        )
                    }
                    ArchitectureItem("Audio Capture", "48 kHz ENCODING_PCM_FLOAT AudioPlaybackCapture (API 29+) & UNPROCESSED Live Mic")
                    ArchitectureItem("IIR Crossover", "uk.me.berndporr:iirj 4th-Order Butterworth multi-band digital filterbank")
                    ArchitectureItem("Spectral STFT", "JTransforms FloatFFT_1D (2048-pt Blackman-Harris, 5.33ms hop, parabolic Hz)")
                    ArchitectureItem("MIR Separation", "SuperFlux local-max onset + Causal 11-frame HPSS + Pitch-Drop detector")
                    ArchitectureItem("Neural Head", "On-device SiLU MLP (10→16→8→2) multi-label Kick vs. Bass classifier")
                    ArchitectureItem("Haptic Synthesis", "Android 16 PWLE WaveformEnvelopeBuilder / BasicEnvelopeBuilder + Sidechain Ducking")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String, highlight: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val signalColors = LocalSubPulseColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) signalColors.liveGreen else colorScheme.onSurface
        )
    }
}

@Composable
private fun CalibrationButton(
    label: String,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = accent.copy(alpha = 0.18f),
            contentColor = accent
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
        modifier = modifier
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun FeatureBarRow(
    label: String,
    value: Float,
    accent: Color
) {
    val colorScheme = MaterialTheme.colorScheme
    val animated by animateFloatAsState(
        targetValue = value.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 500f),
        label = "mir_feature"
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format("%.2f", animated),
                style = MaterialTheme.typography.labelSmall,
                color = accent
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colorScheme.background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated.coerceAtLeast(0.01f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent)
            )
        }
    }
}

@Composable
private fun ArchitectureItem(title: String, detail: String) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = colorScheme.primary,
            modifier = Modifier.widthIn(min = 104.dp)
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}
