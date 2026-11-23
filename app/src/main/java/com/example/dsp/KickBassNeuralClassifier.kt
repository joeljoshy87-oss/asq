package com.example.dsp

import com.example.model.MirFeatureSnapshot
import com.example.model.TuningConfig
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * On-device TinyML 2-layer Multi-Layer Perceptron (10 -> 16 -> 8 -> 2, SiLU hidden activations,
 * Sigmoid multi-label heads) for real-time sub-millisecond Kick Drum vs. Sustained Sub-Bass
 * classification from 10 Music Information Retrieval (MIR) acoustic features.
 *
 * Feature vector order (normalized [0, 1]):
 *  0: subRms              (20–65 Hz sub-bass energy)
 *  1: kickBody            (65–145 Hz kick punch energy)
 *  2: bassHarmonic        (145–320 Hz bass harmonic warmth)
 *  3: beaterClick         (1.8–4.8 kHz percussive beater click)
 *  4: superFlux           (Böck & Widmer local-max positive spectral flux)
 *  5: hpssPercussive      (Causal HPSS vertical percussive ratio)
 *  6: hpssHarmonic        (Causal HPSS horizontal harmonic ratio)
 *  7: pitchDrop           (Downward sub-180Hz pitch-sweep velocity)
 *  8: transientCrest      (Instantaneous attack crest & slope)
 *  9: sustainStationarity (Temporal sub-energy stability over 100ms window)
 */
class KickBassNeuralClassifier {

    data class ClassificationResult(
        val kickProbability: Float,
        val bassProbability: Float,
        val kickTriggered: Boolean,
        val kickImpactStrength: Float,
        val bassDepth: Float
    )

    // Layer 1: 16 hidden units x 10 input features
    // Neurons 0..7 specialize in percussive kick signatures (transient onset, pitch drop,
    // beater click, vertical HPSS, punch body) while remaining robust during simultaneous 808 bass.
    // Neurons 8..15 specialize in sustained sub-bass / 808 signatures (sub RMS, horizontal
    // HPSS, stationarity, harmonic continuity, low flux).
    private val w1 = arrayOf(
        // Kick-specialized neurons (0..7) — multi-label safe so co-occurring 808 sub doesn't mask kicks
        floatArrayOf(0.35f, 1.45f, -0.15f, 0.85f, 1.85f, 1.10f, -0.25f, 1.25f, 1.35f, -0.28f),
        floatArrayOf(0.55f, 1.25f, -0.25f, 0.45f, 1.65f, 0.95f, -0.20f, 1.45f, 1.15f, -0.22f),
        floatArrayOf(0.25f, 1.35f, 0.05f, 1.35f, 1.50f, 0.85f, -0.20f, 0.90f, 1.25f, -0.18f),
        floatArrayOf(0.70f, 1.15f, -0.10f, 0.40f, 1.75f, 0.90f, -0.15f, 1.05f, 1.40f, -0.15f),
        floatArrayOf(0.15f, 1.00f, -0.25f, 1.05f, 1.45f, 1.20f, -0.35f, 1.25f, 1.05f, -0.35f),
        floatArrayOf(0.45f, 1.20f, -0.05f, 0.60f, 1.55f, 0.80f, -0.10f, 1.10f, 1.20f, -0.12f),
        floatArrayOf(0.65f, 0.95f, -0.20f, 0.30f, 1.40f, 0.70f, -0.10f, 0.95f, 1.10f, -0.10f),
        floatArrayOf(0.20f, 1.30f, 0.05f, 0.95f, 1.50f, 1.05f, -0.20f, 0.85f, 1.20f, -0.20f),
        // Bass-specialized neurons (8..15)
        floatArrayOf(1.65f, 0.30f, 0.75f, -0.75f, -0.90f, -0.85f, 1.45f, -0.80f, -0.65f, 1.55f),
        floatArrayOf(1.45f, 0.45f, 0.90f, -0.60f, -0.75f, -0.70f, 1.35f, -0.65f, -0.55f, 1.40f),
        floatArrayOf(1.80f, 0.15f, 0.40f, -0.80f, -0.60f, -0.60f, 1.20f, -0.75f, -0.50f, 1.30f),
        floatArrayOf(1.25f, 0.55f, 1.10f, -0.50f, -0.80f, -0.65f, 1.25f, -0.55f, -0.45f, 1.20f),
        floatArrayOf(1.50f, 0.35f, 0.50f, -0.40f, -0.35f, -0.40f, 1.15f, -0.45f, -0.35f, 1.15f),
        floatArrayOf(1.30f, 0.25f, 0.85f, -0.70f, -0.95f, -0.90f, 1.50f, -0.70f, -0.70f, 1.45f),
        floatArrayOf(1.55f, 0.60f, 0.65f, -0.35f, -0.20f, -0.25f, 1.05f, -0.30f, -0.25f, 1.05f),
        floatArrayOf(1.20f, 0.40f, 0.95f, -0.65f, -0.85f, -0.75f, 1.30f, -0.60f, -0.50f, 1.35f)
    )

    private val b1 = floatArrayOf(
        -1.65f, -1.55f, -1.60f, -1.65f, -1.45f, -1.50f, -1.40f, -1.55f,
        -1.50f, -1.45f, -1.35f, -1.40f, -1.25f, -1.45f, -1.30f, -1.35f
    )

    // Layer 2: 8 hidden units x 16 inputs
    private val w2 = arrayOf(
        floatArrayOf(0.85f, 0.80f, 0.70f, 0.80f, 0.75f, 0.70f, 0.60f, 0.75f, -0.10f, -0.08f, -0.05f, -0.08f, -0.05f, -0.10f, -0.02f, -0.08f),
        floatArrayOf(0.75f, 0.90f, 0.60f, 0.85f, 0.70f, 0.75f, 0.65f, 0.65f, -0.08f, -0.05f, -0.02f, -0.05f, -0.02f, -0.08f, 0.00f, -0.05f),
        floatArrayOf(0.70f, 0.65f, 0.90f, 0.65f, 0.85f, 0.60f, 0.55f, 0.85f, -0.12f, -0.10f, -0.08f, -0.10f, -0.05f, -0.12f, -0.05f, -0.10f),
        floatArrayOf(0.80f, 0.75f, 0.65f, 0.90f, 0.65f, 0.80f, 0.75f, 0.60f, -0.05f, -0.05f, 0.00f, -0.05f, 0.02f, -0.05f, 0.02f, -0.05f),
        floatArrayOf(-0.20f, -0.15f, -0.20f, -0.10f, -0.25f, -0.15f, -0.05f, -0.20f, 0.90f, 0.85f, 0.95f, 0.75f, 0.80f, 0.85f, 0.75f, 0.80f),
        floatArrayOf(-0.15f, -0.10f, -0.15f, -0.05f, -0.20f, -0.10f, 0.00f, -0.15f, 0.80f, 0.90f, 0.85f, 0.85f, 0.75f, 0.80f, 0.80f, 0.85f),
        floatArrayOf(-0.10f, -0.05f, -0.15f, 0.00f, -0.15f, -0.05f, 0.05f, -0.10f, 0.85f, 0.80f, 0.90f, 0.70f, 0.85f, 0.75f, 0.85f, 0.75f),
        floatArrayOf(-0.25f, -0.20f, -0.25f, -0.15f, -0.25f, -0.20f, -0.10f, -0.20f, 0.85f, 0.85f, 0.80f, 0.90f, 0.70f, 0.90f, 0.70f, 0.85f)
    )

    private val b2 = floatArrayOf(-0.35f, -0.35f, -0.40f, -0.30f, -0.35f, -0.35f, -0.30f, -0.35f)

    // Output layer: 2 heads (Kick, Bass) x 8 inputs
    private val wOutKick = floatArrayOf(1.15f, 1.10f, 1.05f, 1.00f, -0.08f, -0.06f, -0.04f, -0.08f)
    private val bOutKick = -1.65f

    private val wOutBass = floatArrayOf(-0.16f, -0.12f, -0.20f, -0.08f, 1.15f, 1.10f, 1.05f, 1.05f)
    private val bOutBass = -1.55f

    // Reusable buffers to avoid GC allocations on the real-time audio thread
    private val inputVec = FloatArray(10)
    private val hidden1 = FloatArray(16)
    private val hidden2 = FloatArray(8)

    // Temporal post-processing state
    private var prevKickProb = 0f
    private var lastKickTriggerMs = 0L
    private var smoothedBassDepth = 0f
    private var smoothedKickVisual = 0f

    fun reset() {
        prevKickProb = 0f
        lastKickTriggerMs = 0L
        smoothedBassDepth = 0f
        smoothedKickVisual = 0f
    }

    fun classify(
        features: MirFeatureSnapshot,
        config: TuningConfig,
        nowMs: Long
    ): ClassificationResult {
        // Hard low-frequency acoustic energy gate:
        // Vocals, snares, or hi-hats without genuine sub/kick-body energy must never trigger subwoofer output.
        val lowEndEnergy = max(features.subRms, features.kickBody)
        if (lowEndEnergy < 0.045f) {
            smoothedBassDepth *= 0.78f
            smoothedKickVisual *= 0.72f
            prevKickProb = 0f
            return ClassificationResult(
                kickProbability = smoothedKickVisual,
                bassProbability = smoothedBassDepth,
                kickTriggered = false,
                kickImpactStrength = 0f,
                bassDepth = smoothedBassDepth
            )
        }

        // Populate feature vector (apply optional beater-click boost)
        inputVec[0] = features.subRms
        inputVec[1] = features.kickBody
        inputVec[2] = features.bassHarmonic
        inputVec[3] = if (config.beaterBoostEnabled) {
            min(1f, features.beaterClick * 1.25f)
        } else {
            features.beaterClick * 0.55f
        }
        inputVec[4] = features.superFlux
        inputVec[5] = features.hpssPercussive
        inputVec[6] = features.hpssHarmonic
        inputVec[7] = features.pitchDrop
        inputVec[8] = features.transientCrest
        inputVec[9] = features.sustainStationarity

        // Forward pass Layer 1: Dense(10 -> 16) + SiLU
        for (i in 0 until 16) {
            var sum = b1[i]
            val row = w1[i]
            for (j in 0 until 10) {
                sum += row[j] * inputVec[j]
            }
            hidden1[i] = silu(sum)
        }

        // Forward pass Layer 2: Dense(16 -> 8) + SiLU
        for (i in 0 until 8) {
            var sum = b2[i]
            val row = w2[i]
            for (j in 0 until 16) {
                sum += row[j] * hidden1[j]
            }
            hidden2[i] = silu(sum)
        }

        // Forward pass Output: Multi-label Sigmoid heads
        var kickLogit = bOutKick
        var bassLogit = bOutBass
        for (j in 0 until 8) {
            kickLogit += wOutKick[j] * hidden2[j]
            bassLogit += wOutBass[j] * hidden2[j]
        }

        // Scale probabilities softly by physical low-end energy envelope
        val energyGate = ((lowEndEnergy - 0.045f) / 0.16f).coerceIn(0f, 1f)
        val rawKickProb = (sigmoid(kickLogit) * energyGate).coerceIn(0f, 1f)
        val rawBassProb = (sigmoid(bassLogit) * ((features.subRms * 1.2f + features.bassHarmonic * 0.4f).coerceIn(0f, 1f))).coerceIn(0f, 1f)

        // Dynamic trigger threshold mapped from user Kick Sensitivity [0..1]
        // Higher sensitivity -> lower threshold (0.36..0.72)
        val triggerThreshold = 0.72f - (config.kickSensitivity.coerceIn(0f, 1f) * 0.36f)
        val refractoryMs = 88L // Prevents double-triggering on kick tail bounce

        val hasTransientImpulse = features.superFlux > 0.16f ||
            (features.transientCrest > 0.25f && features.superFlux > 0.08f)
        val isRisingEdge = rawKickProb >= triggerThreshold &&
            (rawKickProb > prevKickProb - 0.04f) &&
            hasTransientImpulse

        val kickTriggered = isRisingEdge && (nowMs - lastKickTriggerMs >= refractoryMs)

        val kickImpact = if (kickTriggered) {
            lastKickTriggerMs = nowMs
            val bodyImpact = (0.45f * features.kickBody + 0.35f * features.subRms + 0.20f * features.superFlux)
            (0.45f + 0.55f * bodyImpact).coerceIn(0.25f, 1f) * rawKickProb.coerceAtLeast(0.65f)
        } else {
            0f
        }

        // Smooth bass envelope follower according to user smoothness slider
        val attackCoeff = 0.36f - (config.bassSmoothness * 0.18f) // 0.18..0.36
        val releaseCoeff = 0.24f - (config.bassSmoothness * 0.16f) // 0.08..0.24
        val targetBassDepth = (rawBassProb * (0.65f * features.subRms + 0.35f * max(features.subRms, features.bassHarmonic)))
            .coerceIn(0f, 1f)

        smoothedBassDepth = if (targetBassDepth > smoothedBassDepth) {
            smoothedBassDepth + attackCoeff * (targetBassDepth - smoothedBassDepth)
        } else {
            smoothedBassDepth + releaseCoeff * (targetBassDepth - smoothedBassDepth)
        }

        smoothedKickVisual = if (kickTriggered) {
            max(rawKickProb, kickImpact)
        } else {
            max(rawKickProb * 0.85f, smoothedKickVisual * 0.80f)
        }

        prevKickProb = rawKickProb

        return ClassificationResult(
            kickProbability = smoothedKickVisual.coerceIn(0f, 1f),
            bassProbability = rawBassProb.coerceIn(0f, 1f),
            kickTriggered = kickTriggered,
            kickImpactStrength = kickImpact.coerceIn(0f, 1f),
            bassDepth = smoothedBassDepth.coerceIn(0f, 1f)
        )
    }

    private fun sigmoid(x: Float): Float {
        val clamped = x.coerceIn(-12f, 12f)
        return (1.0 / (1.0 + exp(-clamped.toDouble()))).toFloat()
    }

    private fun silu(x: Float): Float = x * sigmoid(x)
}
