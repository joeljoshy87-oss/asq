package com.example.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.model.AcousticFrameTelemetry
import com.example.model.HapticHardwareCapabilities
import com.example.model.TuningConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Multi-tier 2026 Android Haptic Subwoofer Actuator Engine.
 *
 * Tier 1 (Android 16 / API 36 BAKLAVA):
 *   - Piecewise Linear Envelope (PWLE) synthesis via VibrationEffect.WaveformEnvelopeBuilder
 *     (frequency-tracked to VibratorFrequencyProfile) & VibrationEffect.BasicEnvelopeBuilder.
 *   - Kick: Sharp downward pitch-chirp transient envelope (high sharpness -> resonant low, zero ring).
 *   - Bass: Continuous low-sharpness frequency-mapped sub-rumble envelope.
 *
 * Tier 2 (Android 11–15 / API 30–35):
 *   - Rich LRA Composition Primitives (PRIMITIVE_THUD + PRIMITIVE_LOW_TICK) for Kick punch.
 *   - Smooth perceptual-gamma amplitude-modulated waveforms for Sub-Bass.
 *   - Tagged with VibrationAttributes.USAGE_MEDIA (API 33+).
 *
 * Tier 3 (Android 8–10 / API 26–29):
 *   - Amplitude-scaled VibrationEffect.createOneShot pulses.
 *
 * Includes Kick-over-Bass Sidechain Ducking & Bluetooth Audio Latency Delay Sync (0–250ms).
 */
class SubwooferHapticEngine(context: Context) {

    companion object {
        private const val TAG = "SubwooferHapticEngine"
        private const val BASS_FRAME_INTERVAL_MS = 34L
    }

    private val vibrator: Vibrator? = resolveVibrator(context)
    private val delayScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    val capabilities: HapticHardwareCapabilities = probeHardwareCapabilities()

    @Volatile
    private var lastBassDispatchMs = 0L

    @Volatile
    private var sidechainUntilMs = 0L

    @Volatile
    private var currentDriveLevel = 0f

    @Volatile
    private var currentMotorFreqHz = 0f

    data class HapticOutputState(
        val driveLevel: Float,
        val motorFreqHz: Float,
        val isSidechainDucking: Boolean
    )

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun probeHardwareCapabilities(): HapticHardwareCapabilities {
        val v = vibrator ?: return HapticHardwareCapabilities(
            hasVibrator = false,
            activeEngineTier = "No Actuator Detected"
        )
        if (!v.hasVibrator()) {
            return HapticHardwareCapabilities(
                hasVibrator = false,
                activeEngineTier = "No Actuator Detected"
            )
        }

        val hasAmp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl()

        var resonantHz: Float? = null
        var qFactor: Float? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val rf = v.resonantFrequency
            if (!rf.isNaN() && rf > 0f) resonantHz = rf
            val qf = v.qFactor
            if (!qf.isNaN() && qf > 0f) qFactor = qf
        }

        var thudSupported = false
        var lowTickSupported = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val support = v.arePrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            )
            thudSupported = support.getOrNull(0) == true
            lowTickSupported = support.getOrNull(1) == true
        }

        var pwleSupported = false
        var minFreq: Float? = null
        var maxFreq: Float? = null
        var maxPoints = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            try {
                pwleSupported = v.areEnvelopeEffectsSupported()
                if (pwleSupported) {
                    val info = v.envelopeEffectInfo
                    maxPoints = info.maxSize
                    val profile = v.frequencyProfile
                    if (profile != null) {
                        val lo = profile.minFrequencyHz
                        val hi = profile.maxFrequencyHz
                        if (!lo.isNaN() && !hi.isNaN() && hi > lo) {
                            minFreq = lo
                            maxFreq = hi
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "API 36 envelope profile query fallback: ${t.message}")
            }
        }

        val tier = when {
            pwleSupported && minFreq != null -> "Android 16 PWLE Frequency Envelope"
            pwleSupported -> "Android 16 Basic PWLE Envelope"
            thudSupported -> "LRA Rich Composition Primitives"
            hasAmp -> "HD Amplitude Modulation"
            else -> "Standard Pulse Fallback"
        }

        return HapticHardwareCapabilities(
            hasVibrator = true,
            hasAmplitudeControl = hasAmp,
            resonantFrequencyHz = resonantHz,
            qFactor = qFactor,
            supportsThudPrimitive = thudSupported,
            supportsLowTickPrimitive = lowTickSupported,
            supportsPwleEnvelopes = pwleSupported,
            minFrequencyHz = minFreq,
            maxFrequencyHz = maxFreq,
            maxEnvelopePoints = maxPoints,
            activeEngineTier = tier
        )
    }

    /**
     * Evaluates the latest neural classification frame and dispatches synchronized Kick or Sub-Bass
     * haptic envelopes with sidechain ducking and optional Bluetooth latency delay compensation.
     */
    fun processTelemetry(
        telemetry: AcousticFrameTelemetry,
        config: TuningConfig,
        nowMs: Long
    ): HapticOutputState {
        if (config.hapticsMuted || !capabilities.hasVibrator || config.masterDrive <= 0.02f) {
            currentDriveLevel *= 0.75f
            return HapticOutputState(currentDriveLevel, currentMotorFreqHz, false)
        }

        val delayMs = config.latencyOffsetMs.coerceIn(0, 250).toLong()

        // 1. KICK TRANSIENT PRIORITY — crisp high-impact punch with sidechain bass ducking
        if (telemetry.kickTriggered && config.kickGain > 0.04f) {
            val kickIntensity = (telemetry.kickImpactStrength * config.kickGain * config.masterDrive)
                .coerceIn(0.12f, 1.0f)

            // Sidechain ducks continuous sub-bass during kick punch so kick feels razor sharp
            val duckDurationMs = (36L + (config.sidechainDucking * 44f).toLong())
            sidechainUntilMs = nowMs + duckDurationMs

            val targetMotorFreq = computeKickMotorFrequency(config.kickTightness)
            currentDriveLevel = kickIntensity
            currentMotorFreqHz = targetMotorFreq

            dispatchWithDelay(delayMs) {
                fireKickEffect(kickIntensity, config.kickTightness)
            }
            return HapticOutputState(currentDriveLevel, currentMotorFreqHz, true)
        }

        // 2. SUSTAINED SUB-BASS RUMBLE — continuous low-frequency envelope outside sidechain window
        val isDucking = nowMs < sidechainUntilMs
        val duckMultiplier = if (isDucking) (1f - config.sidechainDucking * 0.85f) else 1f
        val effectiveBass = (telemetry.bassDepth * config.bassGain * config.masterDrive * duckMultiplier)
            .coerceIn(0f, 1f)

        if (effectiveBass > 0.09f && (nowMs - lastBassDispatchMs >= BASS_FRAME_INTERVAL_MS) && !isDucking) {
            lastBassDispatchMs = nowMs
            val motorFreq = mapAudioSubToMotorHz(telemetry.dominantLowFreqHz)
            currentDriveLevel = effectiveBass
            currentMotorFreqHz = motorFreq

            dispatchWithDelay(delayMs) {
                fireBassFrameEffect(effectiveBass, telemetry.dominantLowFreqHz, config.bassSmoothness)
            }
        } else {
            currentDriveLevel = max(effectiveBass * 0.85f, currentDriveLevel * 0.86f)
        }

        return HapticOutputState(
            driveLevel = currentDriveLevel.coerceIn(0f, 1f),
            motorFreqHz = currentMotorFreqHz,
            isSidechainDucking = isDucking
        )
    }

    /** Direct calibration impulse: Kick Drum downward pitch-chirp */
    fun playKickCalibrationTest(config: TuningConfig) {
        if (!capabilities.hasVibrator) return
        val strength = (0.92f * config.masterDrive.coerceAtLeast(0.5f)).coerceIn(0.3f, 1f)
        fireKickEffect(strength, config.kickTightness)
    }

    /** Direct calibration impulse: Warm 48Hz Sub-Bass sustain swell */
    fun playBassCalibrationTest(config: TuningConfig) {
        if (!capabilities.hasVibrator) return
        val strength = (0.82f * config.masterDrive.coerceAtLeast(0.5f)).coerceIn(0.3f, 1f)
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && capabilities.supportsPwleEnvelopes) {
            try {
                val effect = buildBassCalibrationEnvelope(v, strength)
                vibrateSafe(v, effect)
                return
            } catch (_: Throwable) {
                // Fall through to legacy waveform
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amp = (strength * 210f).roundToInt().coerceIn(40, 235)
            val timings = longArrayOf(40, 260, 120)
            val amps = intArrayOf((amp * 0.55f).roundToInt(), amp, (amp * 0.35f).roundToInt())
            val effect = if (capabilities.hasAmplitudeControl) {
                VibrationEffect.createWaveform(timings, amps, -1)
            } else {
                VibrationEffect.createOneShot(320L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrateSafe(v, effect)
        }
    }

    /** Direct calibration impulse: Kick punch + sidechained 808 sub-bass swell */
    fun playSidechainComboTest(config: TuningConfig) {
        if (!capabilities.hasVibrator) return
        playKickCalibrationTest(config)
        delayScheduler.schedule({
            playBassCalibrationTest(config)
        }, 95L, TimeUnit.MILLISECONDS)
    }

    fun stopAll() {
        currentDriveLevel = 0f
        currentMotorFreqHz = 0f
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
    }

    fun release() {
        stopAll()
        delayScheduler.shutdownNow()
    }

    private fun dispatchWithDelay(delayMs: Long, action: () -> Unit) {
        if (delayMs <= 2L) {
            action()
        } else {
            try {
                delayScheduler.schedule(action, delayMs, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
        }
    }

    private fun fireKickEffect(intensity: Float, tightness: Float) {
        val v = vibrator ?: return

        // Tier 1: Android 16 (API 36) PWLE Kick Downward Pitch-Chirp Envelope
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && capabilities.supportsPwleEnvelopes) {
            try {
                val minPointMs = max(8L, v.envelopeEffectInfo.minControlPointDurationMillis)
                val profile = v.frequencyProfile
                val resonant = capabilities.resonantFrequencyHz
                val effect = if (profile != null && capabilities.minFrequencyHz != null && capabilities.maxFrequencyHz != null) {
                    val loFreq = capabilities.minFrequencyHz
                    val hiFreq = capabilities.maxFrequencyHz
                    val res = (resonant ?: ((loFreq + hiFreq) * 0.5f)).coerceIn(loFreq, hiFreq)
                    val startFreq = (res * (1.08f + tightness * 0.25f)).coerceIn(loFreq, hiFreq)
                    val endFreq = (res * 0.82f).coerceIn(loFreq, hiFreq)
                    val bodyMs = (28L - (tightness * 14f).toLong()).coerceAtLeast(minPointMs)

                    VibrationEffect.WaveformEnvelopeBuilder()
                        .addControlPoint(intensity, startFreq, minPointMs)
                        .addControlPoint(intensity * 0.82f, endFreq, bodyMs)
                        .addControlPoint(0f, endFreq, minPointMs)
                        .build()
                } else {
                    val sharpStart = (0.65f + tightness * 0.30f).coerceIn(0.4f, 0.98f)
                    val sharpEnd = (0.35f + tightness * 0.15f).coerceIn(0.2f, 0.65f)
                    val decayMs = (36L - (tightness * 16f).toLong()).coerceAtLeast(minPointMs)

                    VibrationEffect.BasicEnvelopeBuilder()
                        .setInitialSharpness(sharpStart)
                        .addControlPoint(intensity, sharpStart, minPointMs)
                        .addControlPoint(0f, sharpEnd, decayMs)
                        .build()
                }
                vibrateSafe(v, effect)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "PWLE kick fallback: ${t.message}")
            }
        }

        // Tier 2: Android 12+ Rich LRA Composition Primitives (THUD + LOW_TICK beater snap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities.supportsThudPrimitive) {
            val comp = VibrationEffect.startComposition()
            if (capabilities.supportsLowTickPrimitive && tightness > 0.55f) {
                comp.addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    (intensity * 0.68f).coerceIn(0.15f, 1f)
                )
            }
            comp.addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_THUD,
                intensity.coerceIn(0.2f, 1f)
            )
            vibrateSafe(v, comp.compose())
            return
        }

        // Tier 3: HD Amplitude OneShot Punch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val durationMs = (38L - (tightness * 16f).toLong()).coerceIn(18L, 42L)
            val amp = if (capabilities.hasAmplitudeControl) {
                (intensity.pow(1.15f) * 255f).roundToInt().coerceIn(28, 255)
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            vibrateSafe(v, VibrationEffect.createOneShot(durationMs, amp))
        }
    }

    @Volatile
    private var lastBassMotorFreqHz = 0f

    private fun fireBassFrameEffect(intensity: Float, audioFreqHz: Float, smoothness: Float) {
        val v = vibrator ?: return
        // Perceptual gamma curve prevents harsh buzzing on quiet bass while preserving deep swells
        val shaped = intensity.pow(1.30f).coerceIn(0.06f, 0.92f)

        // Tier 1: Android 16 (API 36) PWLE Continuous Frequency-Gliding Sub-Bass Envelope
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && capabilities.supportsPwleEnvelopes) {
            try {
                val minPointMs = max(10L, v.envelopeEffectInfo.minControlPointDurationMillis)
                val sustainMs = max(minPointMs, 26L + (smoothness * 16f).toLong())
                val effect = if (capabilities.minFrequencyHz != null && capabilities.maxFrequencyHz != null) {
                    val targetHz = mapAudioSubToMotorHz(audioFreqHz)
                        .coerceIn(capabilities.minFrequencyHz, capabilities.maxFrequencyHz)
                    val startHz = if (lastBassMotorFreqHz in capabilities.minFrequencyHz..capabilities.maxFrequencyHz) {
                        lastBassMotorFreqHz
                    } else {
                        targetHz
                    }
                    lastBassMotorFreqHz = targetHz

                    // Smooth piecewise-linear frequency glide from previous bass pitch -> current pitch
                    VibrationEffect.WaveformEnvelopeBuilder()
                        .addControlPoint(shaped * 0.88f, startHz, minPointMs)
                        .addControlPoint(shaped, targetHz, sustainMs)
                        .addControlPoint(shaped * 0.42f, targetHz, minPointMs)
                        .build()
                } else {
                    // Hardware-agnostic pitch tracking: map 25–130 Hz bass pitch into actuator sharpness
                    val freqNorm = ((audioFreqHz.coerceIn(25f, 130f) - 25f) / 105f).coerceIn(0f, 1f)
                    val targetSharpness = (0.08f + freqNorm * 0.34f + (1f - smoothness) * 0.08f)
                        .coerceIn(0.06f, 0.48f)
                    VibrationEffect.BasicEnvelopeBuilder()
                        .setInitialSharpness(targetSharpness)
                        .addControlPoint(shaped, targetSharpness, minPointMs)
                        .addControlPoint(shaped * 0.55f, targetSharpness, sustainMs)
                        .build()
                }
                vibrateSafe(v, effect)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "PWLE bass fallback: ${t.message}")
            }
        }

        // Tier 2/3: Smooth continuous amplitude-modulated low-latency frame
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val frameDuration = (36L + (smoothness * 16f).toLong()).coerceIn(32L, 54L)
            val amp = if (capabilities.hasAmplitudeControl) {
                (shaped * 225f).roundToInt().coerceIn(18, 228)
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            vibrateSafe(v, VibrationEffect.createOneShot(frameDuration, amp))
        }
    }

    private fun buildBassCalibrationEnvelope(v: Vibrator, strength: Float): VibrationEffect {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return VibrationEffect.createOneShot(300L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        val minPointMs = max(12L, v.envelopeEffectInfo.minControlPointDurationMillis)
        return if (capabilities.minFrequencyHz != null && capabilities.maxFrequencyHz != null) {
            val lo = capabilities.minFrequencyHz
            val res = (capabilities.resonantFrequencyHz ?: ((lo + capabilities.maxFrequencyHz) * 0.5f))
                .coerceIn(lo, capabilities.maxFrequencyHz)
            val targetLow = (lo + (res - lo) * 0.45f).coerceIn(lo, capabilities.maxFrequencyHz)
            VibrationEffect.WaveformEnvelopeBuilder()
                .addControlPoint(strength * 0.5f, targetLow, 45L)
                .addControlPoint(strength, targetLow, 220L)
                .addControlPoint(0f, targetLow, max(minPointMs, 90L))
                .build()
        } else {
            VibrationEffect.BasicEnvelopeBuilder()
                .setInitialSharpness(0.16f)
                .addControlPoint(strength, 0.18f, 50L)
                .addControlPoint(strength * 0.9f, 0.16f, 220L)
                .addControlPoint(0f, 0.12f, max(minPointMs, 90L))
                .build()
        }
    }

    private fun computeKickMotorFrequency(tightness: Float): Float {
        val res = capabilities.resonantFrequencyHz ?: 150f
        return (res * (0.95f + tightness * 0.25f)).coerceIn(80f, 260f)
    }

    private fun mapAudioSubToMotorHz(audioFreqHz: Float): Float {
        val lo = capabilities.minFrequencyHz ?: 65f
        val hi = capabilities.resonantFrequencyHz ?: capabilities.maxFrequencyHz ?: 155f
        val norm = ((audioFreqHz.coerceIn(25f, 130f) - 25f) / 105f).coerceIn(0f, 1f)
        return (lo + norm * max(15f, (hi - lo) * 0.72f))
    }

    private fun vibrateSafe(v: Vibrator, effect: VibrationEffect) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build()
                v.vibrate(effect, attrs)
            } else {
                v.vibrate(effect)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Vibrate dispatch skipped: ${t.message}")
        }
    }
}
