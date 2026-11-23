package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.TuningConfig
import com.example.ui.theme.LocalSubPulseColors
import kotlin.math.roundToInt

@Composable
fun TuningScreen(
    tuning: TuningConfig,
    onUpdateTuning: ((TuningConfig) -> TuningConfig) -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val signalColors = LocalSubPulseColors.current

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.nav_tuning),
                        style = MaterialTheme.typography.headlineMedium,
                        color = colorScheme.onBackground
                    )
                    Text(
                        text = "Active Profile: ${tuning.activePreset.displayName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = signalColors.bass
                    )
                }
                OutlinedButton(
                    onClick = onResetDefaults,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("reset_tuning_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.action_reset_defaults),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // 1. Kick Transient Shaping Card
            TuningSectionCard(
                title = stringResource(R.string.tuning_kick_section),
                icon = Icons.Filled.Bolt,
                accent = signalColors.kick
            ) {
                TuningSliderRow(
                    label = stringResource(R.string.label_kick_gain),
                    valueText = "${(tuning.kickGain * 100).roundToInt()}%",
                    value = tuning.kickGain,
                    range = 0f..1f,
                    accent = signalColors.kick,
                    testTag = "slider_kick_gain",
                    onValueChange = { v -> onUpdateTuning { it.copy(kickGain = v) } }
                )
                TuningSliderRow(
                    label = stringResource(R.string.label_kick_tightness),
                    valueText = "${(tuning.kickTightness * 100).roundToInt()}%",
                    value = tuning.kickTightness,
                    range = 0f..1f,
                    accent = signalColors.kick,
                    testTag = "slider_kick_tightness",
                    onValueChange = { v -> onUpdateTuning { it.copy(kickTightness = v) } }
                )
                TuningSliderRow(
                    label = stringResource(R.string.label_kick_sensitivity),
                    valueText = "${(tuning.kickSensitivity * 100).roundToInt()}%",
                    value = tuning.kickSensitivity,
                    range = 0f..1f,
                    accent = signalColors.kick,
                    testTag = "slider_kick_sensitivity",
                    onValueChange = { v -> onUpdateTuning { it.copy(kickSensitivity = v) } }
                )
                TuningToggleRow(
                    label = stringResource(R.string.label_beater_boost),
                    subtitle = "Weights 1.8–4.8 kHz beater click to isolate kicks from 808 glides",
                    checked = tuning.beaterBoostEnabled,
                    accent = signalColors.kick,
                    testTag = "toggle_beater_boost",
                    onCheckedChange = { checked -> onUpdateTuning { it.copy(beaterBoostEnabled = checked) } }
                )
            }

            // 2. Sub-Bass Harmonic Rumble Card
            TuningSectionCard(
                title = stringResource(R.string.tuning_bass_section),
                icon = Icons.Filled.Waves,
                accent = signalColors.bass
            ) {
                TuningSliderRow(
                    label = stringResource(R.string.label_bass_gain),
                    valueText = "${(tuning.bassGain * 100).roundToInt()}%",
                    value = tuning.bassGain,
                    range = 0f..1f,
                    accent = signalColors.bass,
                    testTag = "slider_bass_gain",
                    onValueChange = { v -> onUpdateTuning { it.copy(bassGain = v) } }
                )
                TuningSliderRow(
                    label = stringResource(R.string.label_bass_crossover),
                    valueText = "${tuning.bassCrossoverHz.roundToInt()} Hz",
                    value = tuning.bassCrossoverHz,
                    range = 45f..140f,
                    accent = signalColors.bass,
                    testTag = "slider_bass_crossover",
                    onValueChange = { v -> onUpdateTuning { it.copy(bassCrossoverHz = v) } }
                )
                TuningSliderRow(
                    label = stringResource(R.string.label_bass_smoothness),
                    valueText = "${(tuning.bassSmoothness * 100).roundToInt()}%",
                    value = tuning.bassSmoothness,
                    range = 0f..1f,
                    accent = signalColors.bass,
                    testTag = "slider_bass_smoothness",
                    onValueChange = { v -> onUpdateTuning { it.copy(bassSmoothness = v) } }
                )
                TuningSliderRow(
                    label = stringResource(R.string.label_sidechain_duck),
                    valueText = "${(tuning.sidechainDucking * 100).roundToInt()}%",
                    value = tuning.sidechainDucking,
                    range = 0f..1f,
                    accent = signalColors.bass,
                    testTag = "slider_sidechain_duck",
                    onValueChange = { v -> onUpdateTuning { it.copy(sidechainDucking = v) } }
                )
            }

            // 3. Master Drive & Bluetooth Latency Sync Card
            TuningSectionCard(
                title = stringResource(R.string.tuning_sync_section),
                icon = Icons.Filled.Sync,
                accent = signalColors.actuator
            ) {
                TuningSliderRow(
                    label = stringResource(R.string.label_master_drive),
                    valueText = "${(tuning.masterDrive * 100).roundToInt()}%",
                    value = tuning.masterDrive,
                    range = 0f..1f,
                    accent = signalColors.actuator,
                    testTag = "slider_master_drive",
                    onValueChange = { v -> onUpdateTuning { it.copy(masterDrive = v) } }
                )

                TuningSliderRow(
                    label = stringResource(R.string.label_latency_sync),
                    valueText = "${tuning.latencyOffsetMs} ms",
                    value = tuning.latencyOffsetMs.toFloat(),
                    range = 0f..250f,
                    accent = signalColors.actuator,
                    testTag = "slider_latency_sync",
                    onValueChange = { v -> onUpdateTuning { it.copy(latencyOffsetMs = v.roundToInt()) } }
                )

                // Quick Bluetooth Audio Sync Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val syncOptions = listOf(0 to "Wired 0ms", 80 to "AptX 80ms", 145 to "BT 145ms", 200 to "Speaker 200ms")
                    syncOptions.forEach { (ms, label) ->
                        val active = kotlin.math.abs(tuning.latencyOffsetMs - ms) <= 6
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) signalColors.actuatorContainer else colorScheme.background)
                                .border(
                                    1.dp,
                                    if (active) signalColors.actuator else colorScheme.outlineVariant,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onUpdateTuning { it.copy(latencyOffsetMs = ms) } }
                                .minimumInteractiveComponentSize()
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) signalColors.actuator else colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                TuningToggleRow(
                    label = stringResource(R.string.label_adaptive_agc),
                    subtitle = "Continuously normalizes quiet acoustic tracks and mastered club drops",
                    checked = tuning.adaptiveAgcEnabled,
                    accent = signalColors.actuator,
                    testTag = "toggle_adaptive_agc",
                    onCheckedChange = { checked -> onUpdateTuning { it.copy(adaptiveAgcEnabled = checked) } }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TuningSectionCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
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
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun TuningSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = colorScheme.background
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

@Composable
private fun TuningToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colorScheme.background,
                checkedTrackColor = accent
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
