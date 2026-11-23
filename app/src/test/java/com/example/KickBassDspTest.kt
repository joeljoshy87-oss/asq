package com.example

import com.example.dsp.AcousticDspPipeline
import com.example.dsp.SubLabSynthesizer
import com.example.model.SubLabPattern
import com.example.model.TuningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class KickBassDspTest {

    private val sampleRate = 48_000

    @Test
    fun sustainedSubBass_activatesBassWithoutSpuriousKicks() {
        val pipeline = AcousticDspPipeline(sampleRate = sampleRate)
        val config = TuningConfig()
        val block = FloatArray(AcousticDspPipeline.HOP_SIZE)

        var maxBassProb = 0f
        var maxBassDepth = 0f
        var settledKickTriggers = 0
        var lastDominantHz = 0f

        var sampleIdx = 0L
        var simTimeMs = 1000L

        // Feed 1.2 seconds (~225 hops) of sustained 46 Hz 808-style sub-bass
        for (hop in 0 until 225) {
            for (i in block.indices) {
                val t = sampleIdx.toDouble() / sampleRate
                sampleIdx++
                // Smooth fade-in over first 80ms so startup isn't an artificial click
                val env = (t / 0.08).coerceIn(0.0, 1.0)
                block[i] = ((sin(2.0 * PI * 46.0 * t) + 0.18 * sin(2.0 * PI * 92.0 * t)) * 0.72 * env).toFloat()
            }
            simTimeMs += 5L
            pipeline.processBuffer(block, block.size, config, simTimeMs) { frame ->
                if (hop > 30) { // After initial fade-in settles
                    if (frame.bassProbability > maxBassProb) maxBassProb = frame.bassProbability
                    if (frame.bassDepth > maxBassDepth) maxBassDepth = frame.bassDepth
                    if (frame.kickTriggered) settledKickTriggers++
                    lastDominantHz = frame.dominantLowFreqHz
                }
            }
        }

        assertTrue("Expected high sub-bass probability, got $maxBassProb", maxBassProb > 0.65f)
        assertTrue("Expected strong sustained bass depth, got $maxBassDepth", maxBassDepth > 0.45f)
        assertTrue("Expected dominant low frequency near 46Hz, got $lastDominantHz", lastDominantHz in 34f..58f)
        assertEquals("Sustained sub-bass should not trigger spurious kick hits", 0, settledKickTriggers)
    }

    @Test
    fun kickIsolationPattern_triggersDistinctKickOnsets() {
        val pipeline = AcousticDspPipeline(sampleRate = sampleRate)
        val synth = SubLabSynthesizer(sampleRate = sampleRate).apply {
            activePattern = SubLabPattern.KICK_ISOLATION
        }
        val config = TuningConfig()
        val block = FloatArray(AcousticDspPipeline.HOP_SIZE)

        var kickTriggers = 0
        var maxKickProb = 0f
        var simTimeMs = 1000L

        // Feed 2.5 seconds of 118 BPM kick drum pulses (~4-5 kicks expected)
        val totalHops = (2.5 * sampleRate / AcousticDspPipeline.HOP_SIZE).toInt()
        for (hop in 0 until totalHops) {
            synth.renderBlock(block, block.size)
            simTimeMs += 5L
            pipeline.processBuffer(block, block.size, config, simTimeMs) { frame ->
                if (frame.kickTriggered) kickTriggers++
                if (frame.kickProbability > maxKickProb) maxKickProb = frame.kickProbability
            }
        }

        assertTrue("Expected at least 3 distinct kick triggers in 2.5s, got $kickTriggers", kickTriggers >= 3)
        assertTrue("Expected high kick classification probability, got $maxKickProb", maxKickProb > 0.65f)
    }

    @Test
    fun highFrequencySignal_isGatedOutOfSubwoofer() {
        val pipeline = AcousticDspPipeline(sampleRate = sampleRate)
        val config = TuningConfig()
        val block = FloatArray(AcousticDspPipeline.HOP_SIZE)

        var kickTriggers = 0
        var maxBassDepth = 0f
        var sampleIdx = 0L
        var simTimeMs = 1000L

        // Feed 0.8 seconds of pure 2600 Hz vocal/hi-hat frequency (zero sub-bass)
        for (hop in 0 until 150) {
            for (i in block.indices) {
                val t = sampleIdx.toDouble() / sampleRate
                sampleIdx++
                block[i] = (sin(2.0 * PI * 2600.0 * t) * 0.75).toFloat()
            }
            simTimeMs += 5L
            pipeline.processBuffer(block, block.size, config, simTimeMs) { frame ->
                if (frame.kickTriggered) kickTriggers++
                if (frame.bassDepth > maxBassDepth) maxBassDepth = frame.bassDepth
            }
        }

        assertEquals("High-frequency audio must never trigger kick haptics", 0, kickTriggers)
        assertTrue("High-frequency audio must produce negligible sub-bass depth, got $maxBassDepth", maxBassDepth < 0.08f)
    }

    @Test
    fun trap808Groove_separatesBothKicksAndGlidingSubBass() {
        val pipeline = AcousticDspPipeline(sampleRate = sampleRate)
        val synth = SubLabSynthesizer(sampleRate = sampleRate).apply {
            activePattern = SubLabPattern.TRAP_808
        }
        val config = TuningConfig()
        val block = FloatArray(AcousticDspPipeline.HOP_SIZE)

        var kickTriggers = 0
        var maxBassDepth = 0f
        var simTimeMs = 1000L

        val totalHops = (3.5 * sampleRate / AcousticDspPipeline.HOP_SIZE).toInt()
        for (hop in 0 until totalHops) {
            synth.renderBlock(block, block.size)
            simTimeMs += 5L
            pipeline.processBuffer(block, block.size, config, simTimeMs) { frame ->
                if (frame.kickTriggered) kickTriggers++
                if (frame.bassDepth > maxBassDepth) maxBassDepth = frame.bassDepth
            }
        }

        assertTrue("Expected trap kicks to trigger (>=3), got $kickTriggers", kickTriggers >= 3)
        assertTrue("Expected gliding 808 sub-bass depth (>0.35), got $maxBassDepth", maxBassDepth > 0.35f)
    }
}
