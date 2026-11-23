package com.example.dsp

import com.example.model.SubLabPattern
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Real-time 48kHz studio-grade Sub-Bass & Kick Drum PCM synthesizer.
 * Generates genuine audible float PCM audio played through AudioTrack AND streamed directly
 * into AcousticDspPipeline + KickBassNeuralClassifier + SubwooferHapticEngine so users can
 * immediately calibrate and feel Kick vs. Sub-Bass separation.
 */
class SubLabSynthesizer(private val sampleRate: Int = 48_000) {

    private var sampleClock: Long = 0L
    private var subPhase = 0.0
    private var kickPhase = 0.0
    private var kickEnvTime = 10.0 // seconds since last kick
    private var manualBassEnv = 0.0
    private var noiseSeed = 2463534242L

    @Volatile
    var activePattern: SubLabPattern = SubLabPattern.TRAP_808

    @Volatile
    var manualBassHeld: Boolean = false

    @Volatile
    private var manualKickQueued: Boolean = false

    fun triggerManualKick() {
        manualKickQueued = true
    }

    fun reset() {
        sampleClock = 0L
        subPhase = 0.0
        kickPhase = 0.0
        kickEnvTime = 10.0
        manualBassEnv = 0.0
        manualKickQueued = false
    }

    fun renderBlock(outBuffer: FloatArray, count: Int) {
        val dt = 1.0 / sampleRate
        val pattern = activePattern

        for (i in 0 until count) {
            val t = sampleClock * dt
            sampleClock++

            if (manualKickQueued) {
                manualKickQueued = false
                kickEnvTime = 0.0
                kickPhase = 0.0
            }

            val sample = when (pattern) {
                SubLabPattern.TRAP_808 -> renderTrap808(t, dt)
                SubLabPattern.DEEP_HOUSE -> renderDeepHouse(t, dt)
                SubLabPattern.SUB_SWEEP -> renderSubSweep(t, dt)
                SubLabPattern.KICK_ISOLATION -> renderKickIsolation(t, dt)
                SubLabPattern.MANUAL_PADS -> renderManualPads(dt)
            }

            // Soft studio saturation limiter to prevent digital clipping
            outBuffer[i] = tanh(sample * 1.15).toFloat().coerceIn(-0.98f, 0.98f)
        }
    }

    private fun renderTrap808(t: Double, dt: Double): Double {
        // 136 BPM -> 16th note = 0.11029s, 1 bar (16 steps) = 1.7647s
        val stepDur = 60.0 / 136.0 / 4.0
        val barPos = t % (stepDur * 16.0)
        val stepIdx = (barPos / stepDur).toInt()
        val stepLocal = barPos - stepIdx * stepDur

        // Kick hits on steps 0, 6, 10 (classic trap bounce)
        val isKickStep = (stepIdx == 0 || stepIdx == 6 || stepIdx == 10)
        if (isKickStep && stepLocal < dt * 1.5) {
            kickEnvTime = 0.0
            kickPhase = 0.0
        }
        val kick = synthKickDrum(dt, startFreq = 165.0, endFreq = 50.0, decaySec = 0.135, clickAmt = 0.24)

        // Sustained gliding 808 sub-bass between kicks (steps 1..5 at 41Hz, steps 7..9 at 49Hz, steps 11..15 at 36.7Hz)
        val (bassFreq, bassGate) = when (stepIdx) {
            in 1..5 -> 41.2 to 1.0 // E1
            in 7..9 -> 49.0 to 0.95 // G1
            in 11..14 -> 36.7 to 1.0 // D1 deep sub
            else -> 41.2 to 0.0
        }
        manualBassEnv += ((bassGate - manualBassEnv) * (if (bassGate > manualBassEnv) 0.004 else 0.0025))
        subPhase += 2.0 * PI * bassFreq * dt
        val bass808 = (sin(subPhase) + 0.18 * sin(2.0 * subPhase)) * manualBassEnv * 0.72

        return kick * 0.85 + bass808
    }

    private fun renderDeepHouse(t: Double, dt: Double): Double {
        // 124 BPM 4-on-the-floor kick + offbeat rolling warm 62-73Hz bassline
        val beatDur = 60.0 / 124.0
        val beatLocal = t % beatDur
        if (beatLocal < dt * 1.5) {
            kickEnvTime = 0.0
            kickPhase = 0.0
        }
        val kick = synthKickDrum(dt, startFreq = 150.0, endFreq = 54.0, decaySec = 0.11, clickAmt = 0.28)

        // Off-beat rolling bass (opens after kick transient at 30%..92% of beat)
        val beatIdx = ((t / beatDur).toInt()) % 4
        val bassFreq = when (beatIdx) {
            0 -> 55.0 // A1
            1 -> 55.0
            2 -> 65.4 // C2
            else -> 61.7 // B1
        }
        val offbeatGate = if (beatLocal > beatDur * 0.28 && beatLocal < beatDur * 0.90) 1.0 else 0.0
        manualBassEnv += (offbeatGate - manualBassEnv) * 0.005
        subPhase += 2.0 * PI * bassFreq * dt
        val bass = (sin(subPhase) + 0.25 * sin(2.0 * subPhase) + 0.08 * sin(3.0 * subPhase)) * manualBassEnv * 0.65

        return kick * 0.82 + bass
    }

    private fun renderSubSweep(t: Double, dt: Double): Double {
        // Smooth 6-second continuous logarithmic sweep from 24 Hz up to 125 Hz and back
        val cycle = (t % 6.0) / 6.0
        val tri = if (cycle < 0.5) cycle * 2.0 else (1.0 - cycle) * 2.0
        val freq = 24.0 + tri * 101.0
        subPhase += 2.0 * PI * freq * dt
        return sin(subPhase) * 0.76
    }

    private fun renderKickIsolation(t: Double, dt: Double): Double {
        // 118 BPM pure acoustic kick drum with crisp beater click & zero bassline
        val beatDur = 60.0 / 118.0
        val beatLocal = t % beatDur
        if (beatLocal < dt * 1.5) {
            kickEnvTime = 0.0
            kickPhase = 0.0
        }
        return synthKickDrum(dt, startFreq = 185.0, endFreq = 52.0, decaySec = 0.105, clickAmt = 0.35) * 0.90
    }

    private fun renderManualPads(dt: Double): Double {
        val kick = synthKickDrum(dt, startFreq = 175.0, endFreq = 48.0, decaySec = 0.13, clickAmt = 0.30)
        val targetBass = if (manualBassHeld) 1.0 else 0.0
        manualBassEnv += (targetBass - manualBassEnv) * (if (manualBassHeld) 0.008 else 0.003)
        subPhase += 2.0 * PI * 46.0 * dt
        val bass = (sin(subPhase) + 0.16 * sin(2.0 * subPhase)) * manualBassEnv * 0.78
        return kick * 0.88 + bass
    }

    private fun synthKickDrum(
        dt: Double,
        startFreq: Double,
        endFreq: Double,
        decaySec: Double,
        clickAmt: Double
    ): Double {
        if (kickEnvTime > 0.35) return 0.0
        val t = kickEnvTime
        kickEnvTime += dt

        // Characteristic exponential pitch drop of a real kick drum (175Hz -> 50Hz in ~28ms)
        val instFreq = endFreq + (startFreq - endFreq) * exp(-t / 0.022)
        kickPhase += 2.0 * PI * instFreq * dt

        val bodyEnv = exp(-t / decaySec) * (1.0 - exp(-t / 0.0018))
        val body = sin(kickPhase) * bodyEnv

        // Short 6ms beater-click noise burst (2–4 kHz feel)
        val clickEnv = if (t < 0.008) exp(-t / 0.0022) else 0.0
        val click = nextWhiteNoise() * clickEnv * clickAmt

        return body + click
    }

    private fun nextWhiteNoise(): Double {
        // Fast xorshift32 PRNG (allocation-free)
        noiseSeed = noiseSeed xor (noiseSeed shl 13)
        noiseSeed = noiseSeed xor (noiseSeed shr 17)
        noiseSeed = noiseSeed xor (noiseSeed shl 5)
        return ((noiseSeed and 0xFFFF).toDouble() / 32768.0) - 1.0
    }
}
