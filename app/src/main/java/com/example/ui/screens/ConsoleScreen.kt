package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.CaptureSource
import com.example.model.EngineStatus
import com.example.model.SubLabPattern
import com.example.model.SubwooferPreset
import com.example.ui.components.SubwooferVisualizerCanvas
import com.example.ui.theme.LocalSubPulseColors
import com.example.viewmodel.SubwooferUiState

@Composable
fun ConsoleScreen(
    uiState: SubwooferUiState,
    onSelectSource: (CaptureSource) -> Unit,
    onToggleEngine: () -> Unit,
    onToggleHapticMute: () -> Unit,
    onSelectPreset: (SubwooferPreset) -> Unit,
    onSelectSubLabPattern: (SubLabPattern) -> Unit,
    onTriggerKickPad: () -> Unit,
    onSetBassPadHeld: (Boolean) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val signalColors = LocalSubPulseColors.current
    val isRunning = uiState.status == EngineStatus.RUNNING

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
            // 1. Studio Header Bar
            ConsoleHeader(
                isRunning = isRunning,
                hapticsMuted = uiState.tuning.hapticsMuted,
                engineTier = uiState.hardware.activeEngineTier,
                onToggleMute = onToggleHapticMute
            )

            // 2. Dismissible Error / Permission Banner
            AnimatedVisibility(
                visible = !uiState.errorMessage.isNullOrBlank(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissError,
                            modifier = Modifier.testTag("dismiss_error_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss error",
                                tint = colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // 3. Hero Reactive Subwoofer Visualizer Canvas
            SubwooferVisualizerCanvas(
                telemetry = uiState.telemetry,
                status = uiState.status,
                crossoverHz = uiState.tuning.bassCrossoverHz
            )

            // 4. Dual-Channel Neural Separation Meters (Kick vs. Sub-Bass)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ChannelMeterCard(
                    title = stringResource(R.string.channel_kick),
                    value = if (isRunning) uiState.telemetry.kickProbability else 0f,
                    metricLabel = if (isRunning) "${uiState.telemetry.kickCount} HITS" else "PUNCH",
                    accentColor = signalColors.kick,
                    containerColor = signalColors.kickContainer,
                    icon = Icons.Filled.Bolt,
                    modifier = Modifier.weight(1f)
                )
                ChannelMeterCard(
                    title = stringResource(R.string.channel_bass),
                    value = if (isRunning) uiState.telemetry.bassDepth else 0f,
                    metricLabel = if (isRunning && uiState.telemetry.dominantLowFreqHz >= 20f) {
                        "${uiState.telemetry.dominantLowFreqHz.toInt()} Hz"
                    } else {
                        "RUMBLE"
                    },
                    accentColor = signalColors.bass,
                    containerColor = signalColors.bassContainer,
                    icon = Icons.Filled.Waves,
                    modifier = Modifier.weight(1f)
                )
            }

            // 5. Live LRA Actuator Output Strip
            ActuatorDriveStrip(
                driveLevel = if (isRunning) uiState.telemetry.hapticDriveLevel else 0f,
                motorFreqHz = uiState.telemetry.hapticMotorFreqHz,
                latencySyncMs = uiState.tuning.latencyOffsetMs,
                isMuted = uiState.tuning.hapticsMuted,
                isRunning = isRunning
            )

            // 6. Audio Capture Source Selector + Primary Activate/Stop Hero Button
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(22.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SourceSelectorPill(
                            label = stringResource(R.string.source_app_audio),
                            icon = Icons.Filled.Headphones,
                            selected = uiState.selectedSource == CaptureSource.INTERNAL_APP_AUDIO,
                            accent = signalColors.bass,
                            testTag = "source_app_audio_pill",
                            onClick = { onSelectSource(CaptureSource.INTERNAL_APP_AUDIO) },
                            modifier = Modifier.weight(1f)
                        )
                        SourceSelectorPill(
                            label = stringResource(R.string.source_live_mic),
                            icon = Icons.Filled.Mic,
                            selected = uiState.selectedSource == CaptureSource.LIVE_MICROPHONE,
                            accent = signalColors.kick,
                            testTag = "source_live_mic_pill",
                            onClick = { onSelectSource(CaptureSource.LIVE_MICROPHONE) },
                            modifier = Modifier.weight(1f)
                        )
                        SourceSelectorPill(
                            label = stringResource(R.string.source_sub_lab),
                            icon = Icons.Filled.GraphicEq,
                            selected = uiState.selectedSource == CaptureSource.STUDIO_SUB_LAB,
                            accent = signalColors.actuator,
                            testTag = "source_sub_lab_pill",
                            onClick = { onSelectSource(CaptureSource.STUDIO_SUB_LAB) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = when (uiState.selectedSource) {
                            CaptureSource.INTERNAL_APP_AUDIO -> stringResource(R.string.source_app_audio_desc)
                            CaptureSource.LIVE_MICROPHONE -> stringResource(R.string.source_live_mic_desc)
                            CaptureSource.STUDIO_SUB_LAB -> stringResource(R.string.source_sub_lab_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onToggleEngine,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) signalColors.kick else signalColors.bass,
                            contentColor = colorScheme.background
                        ),
                        contentPadding = PaddingValues(vertical = 15.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("toggle_subwoofer_button")
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRunning) {
                                stringResource(R.string.action_stop_subpulse)
                            } else {
                                stringResource(R.string.action_start_subpulse)
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            // 7. Interactive Studio Sub-Lab Pattern Deck (visible when Sub-Lab source is selected)
            AnimatedVisibility(
                visible = uiState.selectedSource == CaptureSource.STUDIO_SUB_LAB,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SubLabDeckCard(
                    activePattern = uiState.subLabPattern,
                    onSelectPattern = onSelectSubLabPattern,
                    onTriggerKickPad = onTriggerKickPad,
                    onSetBassPadHeld = onSetBassPadHeld
                )
            }

            // 8. Quick Acoustic Presets Row
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.presets_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurfaceVariant
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val selectablePresets = SubwooferPreset.entries.filter { it != SubwooferPreset.CUSTOM }
                    items(selectablePresets, key = { it.name }) { preset ->
                        val selected = uiState.tuning.activePreset == preset
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) signalColors.bassContainer else colorScheme.surfaceContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    width = 1.dp,
                                    color = if (selected) signalColors.bass else colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { onSelectPreset(preset) }
                                .minimumInteractiveComponentSize()
                                .testTag("preset_${preset.name.lowercase()}")
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = preset.displayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) signalColors.bass else colorScheme.onSurface
                                )
                                Text(
                                    text = preset.tagline,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ConsoleHeader(
    isRunning: Boolean,
    hapticsMuted: Boolean,
    engineTier: String,
    onToggleMute: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val signalColors = LocalSubPulseColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colorScheme.onBackground
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isRunning) signalColors.liveGreenContainer else colorScheme.surfaceContainerHigh)
                        .border(
                            1.dp,
                            if (isRunning) signalColors.liveGreen.copy(alpha = 0.5f) else colorScheme.outlineVariant,
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isRunning) signalColors.liveGreen else colorScheme.onSurfaceVariant)
                    )
                    Text(
                        text = if (isRunning) stringResource(R.string.status_active) else stringResource(R.string.status_standby),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isRunning) signalColors.liveGreen else colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = engineTier,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onToggleMute,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (hapticsMuted) colorScheme.errorContainer else colorScheme.surfaceContainer)
                .border(
                    1.dp,
                    if (hapticsMuted) colorScheme.error else colorScheme.outlineVariant,
                    RoundedCornerShape(14.dp)
                )
                .minimumInteractiveComponentSize()
                .testTag("mute_haptics_button")
        ) {
            Icon(
                imageVector = if (hapticsMuted) Icons.Filled.VolumeOff else Icons.Filled.Vibration,
                contentDescription = if (hapticsMuted) {
                    stringResource(R.string.action_haptic_unmute)
                } else {
                    stringResource(R.string.action_haptic_mute)
                },
                tint = if (hapticsMuted) colorScheme.error else signalColors.actuator
            )
        }
    }
}

@Composable
private fun ChannelMeterCard(
    title: String,
    value: Float,
    metricLabel: String,
    accentColor: Color,
    containerColor: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val animatedValue by animateFloatAsState(
        targetValue = value.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "channel_meter"
    )
    val percent = (animatedValue * 100f).toInt()

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colorScheme.surfaceContainer,
        modifier = modifier
            .border(
                width = 1.dp,
                color = if (animatedValue > 0.35f) accentColor.copy(alpha = 0.55f) else colorScheme.outlineVariant,
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(containerColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colorScheme.onSurface
                )
                Text(
                    text = metricLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor
                )
            }

            // Segmented LED-style meter bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colorScheme.background)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedValue.coerceAtLeast(0.02f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(accentColor.copy(alpha = 0.65f), accentColor)
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun ActuatorDriveStrip(
    driveLevel: Float,
    motorFreqHz: Float,
    latencySyncMs: Int,
    isMuted: Boolean,
    isRunning: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme
    val signalColors = LocalSubPulseColors.current
    val animatedDrive by animateFloatAsState(
        targetValue = if (isMuted) 0f else driveLevel.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 460f),
        label = "actuator_drive"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isMuted) "ACTUATOR MUTED" else stringResource(R.string.channel_actuator),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isMuted) colorScheme.error else signalColors.actuator
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (latencySyncMs > 0) {
                        Text(
                            text = "SYNC +${latencySyncMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = signalColors.bass
                        )
                    }
                    Text(
                        text = if (isRunning && motorFreqHz > 0f && !isMuted) {
                            "${motorFreqHz.toInt()} Hz LRA • ${(animatedDrive * 100).toInt()}%"
                        } else {
                            "${(animatedDrive * 100).toInt()}%"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurface
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colorScheme.background)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedDrive.coerceAtLeast(0.01f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(signalColors.bass, signalColors.actuator, signalColors.kick)
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun SourceSelectorPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else colorScheme.background)
            .border(
                width = 1.dp,
                color = if (selected) accent else colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(vertical = 10.dp, horizontal = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) accent else colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colorScheme.onSurface else colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SubLabDeckCard(
    activePattern: SubLabPattern,
    onSelectPattern: (SubLabPattern) -> Unit,
    onTriggerKickPad: () -> Unit,
    onSetBassPadHeld: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val signalColors = LocalSubPulseColors.current
    var bassPressed by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, signalColors.actuator.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sublab_deck_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = signalColors.actuator
                )
                Text(
                    text = activePattern.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SubLabPattern.entries, key = { it.name }) { pattern ->
                    val selected = pattern == activePattern
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) signalColors.actuatorContainer else colorScheme.background)
                            .border(
                                1.dp,
                                if (selected) signalColors.actuator else colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectPattern(pattern) }
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("sublab_pattern_${pattern.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = pattern.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) signalColors.actuator else colorScheme.onSurface
                        )
                    }
                }
            }

            // Live Interactive Kick & Hold 808 Sub Pads
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(signalColors.kickContainer)
                        .border(1.dp, signalColors.kick, RoundedCornerShape(14.dp))
                        .clickable { onTriggerKickPad() }
                        .testTag("manual_kick_pad"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = signalColors.kickBright,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.action_kick_pad),
                            style = MaterialTheme.typography.labelLarge,
                            color = signalColors.kickOnContainer
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (bassPressed) signalColors.bass.copy(alpha = 0.35f) else signalColors.bassContainer)
                        .border(1.dp, signalColors.bass, RoundedCornerShape(14.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    bassPressed = true
                                    onSetBassPadHeld(true)
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        bassPressed = false
                                        onSetBassPadHeld(false)
                                    }
                                }
                            )
                        }
                        .testTag("manual_bass_pad"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Waves,
                            contentDescription = null,
                            tint = signalColors.bass,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.action_bass_pad),
                            style = MaterialTheme.typography.labelLarge,
                            color = signalColors.bassOnContainer
                        )
                    }
                }
            }
        }
    }
}
