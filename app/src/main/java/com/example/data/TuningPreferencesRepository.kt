package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.CaptureSource
import com.example.model.SubwooferPreset
import com.example.model.TuningConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.subPulseDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "subpulse_tuning_prefs"
)

class TuningPreferencesRepository(private val context: Context) {

    private object Keys {
        val MASTER_DRIVE = floatPreferencesKey("master_drive")
        val KICK_GAIN = floatPreferencesKey("kick_gain")
        val BASS_GAIN = floatPreferencesKey("bass_gain")
        val KICK_TIGHTNESS = floatPreferencesKey("kick_tightness")
        val KICK_SENSITIVITY = floatPreferencesKey("kick_sensitivity")
        val BASS_CROSSOVER_HZ = floatPreferencesKey("bass_crossover_hz")
        val BASS_SMOOTHNESS = floatPreferencesKey("bass_smoothness")
        val SIDECHAIN_DUCKING = floatPreferencesKey("sidechain_ducking")
        val LATENCY_OFFSET_MS = intPreferencesKey("latency_offset_ms")
        val BEATER_BOOST = booleanPreferencesKey("beater_boost")
        val ADAPTIVE_AGC = booleanPreferencesKey("adaptive_agc")
        val ACTIVE_PRESET = stringPreferencesKey("active_preset")
        val CAPTURE_SOURCE = stringPreferencesKey("capture_source")
    }

    val tuningConfigFlow: Flow<TuningConfig> = context.subPulseDataStore.data.map { prefs ->
        val presetName = prefs[Keys.ACTIVE_PRESET]
        val preset = SubwooferPreset.entries.firstOrNull { it.name == presetName }
            ?: SubwooferPreset.CLUB_808
        TuningConfig(
            masterDrive = prefs[Keys.MASTER_DRIVE] ?: 0.88f,
            kickGain = prefs[Keys.KICK_GAIN] ?: 0.92f,
            bassGain = prefs[Keys.BASS_GAIN] ?: 0.80f,
            kickTightness = prefs[Keys.KICK_TIGHTNESS] ?: 0.72f,
            kickSensitivity = prefs[Keys.KICK_SENSITIVITY] ?: 0.62f,
            bassCrossoverHz = prefs[Keys.BASS_CROSSOVER_HZ] ?: 88f,
            bassSmoothness = prefs[Keys.BASS_SMOOTHNESS] ?: 0.68f,
            sidechainDucking = prefs[Keys.SIDECHAIN_DUCKING] ?: 0.75f,
            latencyOffsetMs = prefs[Keys.LATENCY_OFFSET_MS] ?: 0,
            beaterBoostEnabled = prefs[Keys.BEATER_BOOST] ?: true,
            adaptiveAgcEnabled = prefs[Keys.ADAPTIVE_AGC] ?: true,
            hapticsMuted = false,
            activePreset = preset
        )
    }

    val captureSourceFlow: Flow<CaptureSource> = context.subPulseDataStore.data.map { prefs ->
        val raw = prefs[Keys.CAPTURE_SOURCE]
        CaptureSource.entries.firstOrNull { it.name == raw } ?: CaptureSource.INTERNAL_APP_AUDIO
    }

    suspend fun saveTuningConfig(config: TuningConfig) {
        context.subPulseDataStore.edit { prefs ->
            prefs[Keys.MASTER_DRIVE] = config.masterDrive
            prefs[Keys.KICK_GAIN] = config.kickGain
            prefs[Keys.BASS_GAIN] = config.bassGain
            prefs[Keys.KICK_TIGHTNESS] = config.kickTightness
            prefs[Keys.KICK_SENSITIVITY] = config.kickSensitivity
            prefs[Keys.BASS_CROSSOVER_HZ] = config.bassCrossoverHz
            prefs[Keys.BASS_SMOOTHNESS] = config.bassSmoothness
            prefs[Keys.SIDECHAIN_DUCKING] = config.sidechainDucking
            prefs[Keys.LATENCY_OFFSET_MS] = config.latencyOffsetMs
            prefs[Keys.BEATER_BOOST] = config.beaterBoostEnabled
            prefs[Keys.ADAPTIVE_AGC] = config.adaptiveAgcEnabled
            prefs[Keys.ACTIVE_PRESET] = config.activePreset.name
        }
    }

    suspend fun saveCaptureSource(source: CaptureSource) {
        context.subPulseDataStore.edit { prefs ->
            prefs[Keys.CAPTURE_SOURCE] = source.name
        }
    }
}
