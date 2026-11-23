package com.example.model

enum class CaptureSource {
    INTERNAL_APP_AUDIO,
    LIVE_MICROPHONE,
    STUDIO_SUB_LAB
}

enum class SubLabPattern(val label: String, val subtitle: String, val bpm: Int) {
    TRAP_808("808 Trap", "55Hz Kick + Gliding 38–52Hz 808", 136),
    DEEP_HOUSE("Deep House", "4-on-Floor Kick + Rolling 68Hz Bass", 124),
    SUB_SWEEP("Sub Sweep", "24Hz → 130Hz Pure LRA Resonance Sweep", 0),
    KICK_ISOLATION("Kick Pulse", "Dry Transient Kick + Beater Click", 118),
    MANUAL_PADS("Live Pads", "Trigger Kick & Hold 808 Manually", 0)
}

enum class SubwooferPreset(
    val displayName: String,
    val tagline: String
) {
    CLUB_808("Club 808", "Deep sub sustain + sharp trap kick"),
    TIGHT_KICK("Tight Kick", "Maximum percussive snap, minimal bleed"),
    DEEP_CINEMA("Deep Cinema", "Warm 30–75Hz LFE rumble floor"),
    EDM_PUNCH("EDM Punch", "Heavy sidechained kick & bass drive"),
    CONCERT_LIVE("Concert Live", "Balanced AGC for live room acoustics"),
    CUSTOM("Custom", "User-sculpted crossover & haptics")
}

data class TuningConfig(
    val masterDrive: Float = 0.88f,
    val kickGain: Float = 0.92f,
    val bassGain: Float = 0.80f,
    val kickTightness: Float = 0.72f,
    val kickSensitivity: Float = 0.62f,
    val bassCrossoverHz: Float = 88f,
    val bassSmoothness: Float = 0.68f,
    val sidechainDucking: Float = 0.75f,
    val latencyOffsetMs: Int = 0,
    val beaterBoostEnabled: Boolean = true,
    val adaptiveAgcEnabled: Boolean = true,
    val hapticsMuted: Boolean = false,
    val activePreset: SubwooferPreset = SubwooferPreset.CLUB_808
) {
    companion object {
        fun forPreset(preset: SubwooferPreset, currentLatencyMs: Int = 0): TuningConfig = when (preset) {
            SubwooferPreset.CLUB_808 -> TuningConfig(
                masterDrive = 0.90f,
                kickGain = 0.94f,
                bassGain = 0.86f,
                kickTightness = 0.76f,
                kickSensitivity = 0.64f,
                bassCrossoverHz = 84f,
                bassSmoothness = 0.72f,
                sidechainDucking = 0.80f,
                latencyOffsetMs = currentLatencyMs,
                beaterBoostEnabled = true,
                adaptiveAgcEnabled = true,
                activePreset = preset
            )
            SubwooferPreset.TIGHT_KICK -> TuningConfig(
                masterDrive = 0.86f,
                kickGain = 1.00f,
                bassGain = 0.35f,
                kickTightness = 0.92f,
                kickSensitivity = 0.72f,
                bassCrossoverHz = 65f,
                bassSmoothness = 0.45f,
                sidechainDucking = 0.95f,
                latencyOffsetMs = currentLatencyMs,
                beaterBoostEnabled = true,
                adaptiveAgcEnabled = true,
                activePreset = preset
            )
            SubwooferPreset.DEEP_CINEMA -> TuningConfig(
                masterDrive = 0.84f,
                kickGain = 0.65f,
                bassGain = 1.00f,
                kickTightness = 0.48f,
                kickSensitivity = 0.50f,
                bassCrossoverHz = 72f,
                bassSmoothness = 0.88f,
                sidechainDucking = 0.40f,
                latencyOffsetMs = currentLatencyMs,
                beaterBoostEnabled = false,
                adaptiveAgcEnabled = true,
                activePreset = preset
            )
            SubwooferPreset.EDM_PUNCH -> TuningConfig(
                masterDrive = 0.96f,
                kickGain = 0.98f,
                bassGain = 0.90f,
                kickTightness = 0.85f,
                kickSensitivity = 0.68f,
                bassCrossoverHz = 105f,
                bassSmoothness = 0.60f,
                sidechainDucking = 0.90f,
                latencyOffsetMs = currentLatencyMs,
                beaterBoostEnabled = true,
                adaptiveAgcEnabled = true,
                activePreset = preset
            )
            SubwooferPreset.CONCERT_LIVE -> TuningConfig(
                masterDrive = 0.82f,
                kickGain = 0.84f,
                bassGain = 0.76f,
                kickTightness = 0.64f,
                kickSensitivity = 0.54f,
                bassCrossoverHz = 95f,
                bassSmoothness = 0.74f,
                sidechainDucking = 0.65f,
                latencyOffsetMs = currentLatencyMs,
                beaterBoostEnabled = false,
                adaptiveAgcEnabled = true,
                activePreset = preset
            )
            SubwooferPreset.CUSTOM -> TuningConfig(
                latencyOffsetMs = currentLatencyMs,
                activePreset = SubwooferPreset.CUSTOM
            )
        }
    }
}

data class HapticHardwareCapabilities(
    val hasVibrator: Boolean = false,
    val hasAmplitudeControl: Boolean = false,
    val resonantFrequencyHz: Float? = null,
    val qFactor: Float? = null,
    val supportsThudPrimitive: Boolean = false,
    val supportsLowTickPrimitive: Boolean = false,
    val supportsPwleEnvelopes: Boolean = false,
    val minFrequencyHz: Float? = null,
    val maxFrequencyHz: Float? = null,
    val maxEnvelopePoints: Int = 0,
    val activeEngineTier: String = "Probing Actuator..."
)

data class MirFeatureSnapshot(
    val subRms: Float = 0f,
    val kickBody: Float = 0f,
    val bassHarmonic: Float = 0f,
    val beaterClick: Float = 0f,
    val superFlux: Float = 0f,
    val hpssPercussive: Float = 0f,
    val hpssHarmonic: Float = 0f,
    val pitchDrop: Float = 0f,
    val transientCrest: Float = 0f,
    val sustainStationarity: Float = 0f
)

data class AcousticFrameTelemetry(
    val kickProbability: Float = 0f,
    val bassProbability: Float = 0f,
    val kickTriggered: Boolean = false,
    val kickImpactStrength: Float = 0f,
    val bassDepth: Float = 0f,
    val dominantLowFreqHz: Float = 0f,
    val estimatedBpm: Int = 0,
    val hapticDriveLevel: Float = 0f,
    val hapticMotorFreqHz: Float = 0f,
    val isSidechainDucking: Boolean = false,
    val peakDb: Float = -60f,
    val dspLatencyMs: Float = 0f,
    val spectrumBins: List<Float> = List(32) { 0f },
    val features: MirFeatureSnapshot = MirFeatureSnapshot(),
    val kickCount: Long = 0L,
    val frameTimestampMs: Long = 0L
)

enum class EngineStatus {
    IDLE,
    RUNNING,
    ERROR
}
