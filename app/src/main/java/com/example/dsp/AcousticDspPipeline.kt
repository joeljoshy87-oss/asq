package com.example.dsp

import com.example.model.AcousticFrameTelemetry
import com.example.model.MirFeatureSnapshot
import com.example.model.TuningConfig
import org.jtransforms.fft.FloatFFT_1D
import uk.me.berndporr.iirj.Butterworth
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real-time 48kHz Acoustic DSP & MIR Feature Extraction Pipeline powered by:
 * - uk.me.berndporr:iirj (4th-order Butterworth multi-band digital IIR crossover bank)
 * - org.jtransforms.fft.FloatFFT_1D (2048-point Blackman-Harris windowed STFT @ 256-sample hop = 5.33ms)
 * - SuperFlux onset detection (local-max-filtered positive spectral flux)
 * - Causal real-time Harmonic-Percussive Source Separation (HPSS)
 * - Parabolic sub-200Hz pitch-drop tracker & Adaptive Peak AGC
 */
class AcousticDspPipeline(
    private val sampleRate: Int = 48_000,
    private val classifier: KickBassNeuralClassifier = KickBassNeuralClassifier()
) {
    companion object {
        const val FFT_SIZE = 2048
        const val HOP_SIZE = 256
        private const val LOW_BIN_COUNT = 24 // ~0..562 Hz at 48kHz/2048 (23.4375 Hz/bin)
        private const val HPSS_TIME_FRAMES = 11
        private const val SPECTRUM_DISPLAY_BINS = 32
    }

    private val fft = FloatFFT_1D(FFT_SIZE.toLong())

    // 4th-order Butterworth multi-band IIR crossover bank (uk.me.berndporr.iirj)
    private var subFilter = Butterworth()
    private var kickBodyFilter = Butterworth()
    private var bassHarmonicFilter = Butterworth()
    private var beaterClickFilter = Butterworth()
    private var configuredCrossoverHz = -1f

    // Pre-computed Blackman-Harris analysis window
    private val window = FloatArray(FFT_SIZE) { i ->
        val phase = 2.0 * PI * i / (FFT_SIZE - 1)
        (0.35875 - 0.48829 * cos(phase) + 0.14128 * cos(2.0 * phase) - 0.01168 * cos(3.0 * phase)).toFloat()
    }

    // Ring buffer for overlapping STFT frames (zero allocation)
    private val ringBuffer = FloatArray(FFT_SIZE)
    private var ringWritePos = 0
    private var samplesSinceLastHop = 0

    // FFT working arrays
    private val fftBuffer = FloatArray(FFT_SIZE)
    private val currentMag = FloatArray(LOW_BIN_COUNT)
    private val prevMag = FloatArray(LOW_BIN_COUNT)

    // Causal HPSS circular spectrogram history [HPSS_TIME_FRAMES][LOW_BIN_COUNT]
    private val hpssHistory = Array(HPSS_TIME_FRAMES) { FloatArray(LOW_BIN_COUNT) }
    private var hpssCursor = 0
    private val medianScratch = FloatArray(HPSS_TIME_FRAMES)

    // Sub-band RMS history for sustain stationarity & pitch-drop velocity
    private val subEnergyHistory = FloatArray(18)
    private var subHistoryCursor = 0
    private var prevLowEnergy = 0f
    private var prevDominantFreqHz = 60f
    private var smoothedPitchDrop = 0f

    // Adaptive Peak AGC tracker
    private var agcPeakEnvelope = 0.25f

    // Real-time Kick BPM estimation via inter-onset intervals
    private val kickOnsetTimestamps = LongArray(10)
    private var kickOnsetCount = 0
    private var totalKickCount = 0L
    private var estimatedBpm = 0

    // Smoothed 32-band spectrum display (20 Hz – 380 Hz log-mapped)
    private val displaySpectrum = FloatArray(SPECTRUM_DISPLAY_BINS)

    init {
        configureCrossover(88f)
    }

    fun reset() {
        ringBuffer.fill(0f)
        ringWritePos = 0
        samplesSinceLastHop = 0
        currentMag.fill(0f)
        prevMag.fill(0f)
        hpssHistory.forEach { it.fill(0f) }
        hpssCursor = 0
        subEnergyHistory.fill(0f)
        subHistoryCursor = 0
        prevLowEnergy = 0f
        prevDominantFreqHz = 60f
        smoothedPitchDrop = 0f
        agcPeakEnvelope = 0.25f
        kickOnsetCount = 0
        estimatedBpm = 0
        displaySpectrum.fill(0f)
        classifier.reset()
        configuredCrossoverHz = -1f
        configureCrossover(88f)
    }

    private fun configureCrossover(crossoverHz: Float) {
        val clamped = crossoverHz.coerceIn(45f, 140f)
        if (abs(clamped - configuredCrossoverHz) < 1.5f) return
        configuredCrossoverHz = clamped

        val sr = sampleRate.toDouble()
        // Sub-bass band: 20 Hz to clamped crossover (e.g. 20–88 Hz)
        val subLow = 20.0
        val subHigh = clamped.toDouble().coerceAtLeast(42.0)
        subFilter = Butterworth().apply {
            bandPass(2, sr, (subLow + subHigh) * 0.5, (subHigh - subLow).coerceAtLeast(18.0))
        }

        // Kick punch body: 58 Hz to 155 Hz
        kickBodyFilter = Butterworth().apply {
            bandPass(2, sr, 104.0, 96.0)
        }

        // Bass harmonic upper band: crossover to 320 Hz
        val harmLow = (clamped.toDouble() * 1.15).coerceAtMost(180.0)
        val harmHigh = 320.0
        bassHarmonicFilter = Butterworth().apply {
            bandPass(2, sr, (harmLow + harmHigh) * 0.5, (harmHigh - harmLow).coerceAtLeast(60.0))
        }

        // Percussive beater click transient: 1.8 kHz – 4.6 kHz
        beaterClickFilter = Butterworth().apply {
            bandPass(2, sr, 3200.0, 2800.0)
        }
    }

    /**
     * Ingests a real-time float PCM audio chunk and invokes [onFrame] every HOP_SIZE (256) samples.
     */
    fun processBuffer(
        samples: FloatArray,
        sampleCount: Int,
        config: TuningConfig,
        nowMs: Long,
        onFrame: (AcousticFrameTelemetry) -> Unit
    ) {
        configureCrossover(config.bassCrossoverHz)

        var subSq = 0.0
        var kickSq = 0.0
        var harmSq = 0.0
        var clickSq = 0.0
        var rawPeak = 0f
        var hopSamples = 0

        for (i in 0 until sampleCount) {
            val s = samples[i].coerceIn(-1f, 1f)
            val absS = abs(s)
            if (absS > rawPeak) rawPeak = absS

            // Parallel real-time 4th-order Butterworth IIR filterbank
            val d = s.toDouble()
            val subOut = subFilter.filter(d)
            val kickOut = kickBodyFilter.filter(d)
            val harmOut = bassHarmonicFilter.filter(d)
            val clickOut = beaterClickFilter.filter(d)

            subSq += subOut * subOut
            kickSq += kickOut * kickOut
            harmSq += harmOut * harmOut
            clickSq += clickOut * clickOut

            ringBuffer[ringWritePos] = s
            ringWritePos = (ringWritePos + 1) % FFT_SIZE
            samplesSinceLastHop++
            hopSamples++

            if (samplesSinceLastHop >= HOP_SIZE) {
                samplesSinceLastHop = 0
                val n = max(1, hopSamples).toDouble()
                val telemetry = analyzeHopFrame(
                    subRmsRaw = sqrt(subSq / n).toFloat(),
                    kickRmsRaw = sqrt(kickSq / n).toFloat(),
                    harmRmsRaw = sqrt(harmSq / n).toFloat(),
                    clickRmsRaw = sqrt(clickSq / n).toFloat(),
                    rawPeak = rawPeak,
                    config = config,
                    nowMs = nowMs
                )
                onFrame(telemetry)
                subSq = 0.0
                kickSq = 0.0
                harmSq = 0.0
                clickSq = 0.0
                rawPeak = 0f
                hopSamples = 0
            }
        }
    }

    private fun analyzeHopFrame(
        subRmsRaw: Float,
        kickRmsRaw: Float,
        harmRmsRaw: Float,
        clickRmsRaw: Float,
        rawPeak: Float,
        config: TuningConfig,
        nowMs: Long
    ): AcousticFrameTelemetry {
        val startNs = System.nanoTime()

        // 1. Adaptive Automatic Gain Control (AGC) for consistent sensitivity across quiet/loud tracks
        val combinedLowRaw = max(subRmsRaw, kickRmsRaw)
        if (config.adaptiveAgcEnabled) {
            agcPeakEnvelope = if (combinedLowRaw > agcPeakEnvelope) {
                agcPeakEnvelope + 0.14f * (combinedLowRaw - agcPeakEnvelope)
            } else {
                max(0.09f, agcPeakEnvelope * 0.9965f)
            }
        } else {
            agcPeakEnvelope = 0.35f
        }
        val agcScale = (0.85f / max(0.08f, agcPeakEnvelope)).coerceIn(0.6f, 6.5f)

        val subNorm = (subRmsRaw * agcScale * 1.35f).coerceIn(0f, 1f)
        val kickBodyNorm = (kickRmsRaw * agcScale * 1.45f).coerceIn(0f, 1f)
        val harmNorm = (harmRmsRaw * agcScale * 1.50f).coerceIn(0f, 1f)
        val clickNorm = (clickRmsRaw * agcScale * 2.20f).coerceIn(0f, 1f)

        // 2. Windowed 2048-point real FFT via JTransforms
        var readIdx = ringWritePos
        for (i in 0 until FFT_SIZE) {
            fftBuffer[i] = ringBuffer[readIdx] * window[i]
            readIdx++
            if (readIdx == FFT_SIZE) readIdx = 0
        }
        fft.realForward(fftBuffer)

        // Extract low-frequency magnitude spectrum (bins 1..LOW_BIN_COUNT-1 => ~23Hz..540Hz)
        // JTransforms realForward layout: [Re0, Re(n/2), Re1, Im1, Re2, Im2, ...]
        currentMag[0] = abs(fftBuffer[0]) / FFT_SIZE
        val binHz = sampleRate.toFloat() / FFT_SIZE.toFloat() // 23.4375 Hz
        var peakBin = 1
        var peakMag = 0f

        for (bin in 1 until LOW_BIN_COUNT) {
            val re = fftBuffer[2 * bin]
            val im = fftBuffer[2 * bin + 1]
            val mag = sqrt(re * re + im * im) / (FFT_SIZE * 0.25f)
            currentMag[bin] = mag
            // Track dominant low frequency between ~23 Hz (bin 1) and ~188 Hz (bin 8)
            if (bin in 1..8 && mag > peakMag) {
                peakMag = mag
                peakBin = bin
            }
        }

        // Parabolic interpolation for sub-bin frequency precision (~±1.5 Hz)
        val dominantFreqHz = if (peakMag > 0.004f) {
            val left = currentMag[max(1, peakBin - 1)]
            val center = currentMag[peakBin]
            val right = currentMag[min(LOW_BIN_COUNT - 1, peakBin + 1)]
            val denom = (left - 2f * center + right)
            val delta = if (abs(denom) > 1e-6f) {
                (0.5f * (left - right) / denom).coerceIn(-0.5f, 0.5f)
            } else 0f
            ((peakBin + delta) * binHz).coerceIn(22f, 220f)
        } else {
            0f
        }

        // 3. SuperFlux (Böck & Widmer local-max spectral flux across kick-body & punch bins 2..12 => 46–281 Hz)
        var fluxSum = 0f
        var magSum = 1e-4f
        for (bin in 2..12) {
            val prevLocalMax = max(
                prevMag[bin],
                max(prevMag[max(1, bin - 1)], prevMag[min(LOW_BIN_COUNT - 1, bin + 1)])
            )
            // Log compression enhances dynamic onset clarity even when an 808 sub occupies bin 1–2
            val curLog = ln(1f + 32f * currentMag[bin])
            val prevLog = ln(1f + 32f * prevLocalMax)
            val diff = curLog - prevLog
            if (diff > 0f) fluxSum += diff
            magSum += curLog
        }
        val superFlux = (fluxSum / max(1.15f, magSum * 0.45f)).coerceIn(0f, 1f)

        // Update previous magnitude buffer
        System.arraycopy(currentMag, 0, prevMag, 0, LOW_BIN_COUNT)

        // 4. Causal Real-Time Harmonic-Percussive Source Separation (HPSS)
        System.arraycopy(currentMag, 0, hpssHistory[hpssCursor], 0, LOW_BIN_COUNT)
        hpssCursor = (hpssCursor + 1) % HPSS_TIME_FRAMES

        var percussiveEnergy = 0f
        var harmonicEnergy = 0f
        for (bin in 1..9) {
            // Temporal median across last 11 frames -> Harmonic sustain estimate H(t, k)
            for (t in 0 until HPSS_TIME_FRAMES) {
                medianScratch[t] = hpssHistory[t][bin]
            }
            val hEstimate = medianOf(medianScratch, HPSS_TIME_FRAMES)

            // Frequency median across neighboring bins (k-2..k+2) -> Percussive vertical ridge estimate P(t, k)
            var freqCount = 0
            for (fk in max(1, bin - 2)..min(LOW_BIN_COUNT - 1, bin + 2)) {
                medianScratch[freqCount++] = currentMag[fk]
            }
            val pEstimate = medianOf(medianScratch, freqCount)

            val denom = hEstimate * hEstimate + pEstimate * pEstimate + 1e-8f
            val curE = currentMag[bin]
            harmonicEnergy += curE * (hEstimate * hEstimate / denom)
            percussiveEnergy += curE * (pEstimate * pEstimate / denom)
        }
        val hpssTotal = percussiveEnergy + harmonicEnergy + 1e-6f
        val hpssPercussive = (percussiveEnergy / hpssTotal).coerceIn(0f, 1f)
        val hpssHarmonic = (harmonicEnergy / hpssTotal).coerceIn(0f, 1f)

        // 5. Kick Pitch-Drop Velocity (detects rapid downward sweep e.g. 140Hz -> 55Hz)
        val freqDrop = if (dominantFreqHz > 20f && prevDominantFreqHz > 20f) {
            val deltaHz = prevDominantFreqHz - dominantFreqHz
            if (deltaHz > 3f && kickBodyNorm > 0.15f) {
                (deltaHz / 55f).coerceIn(0f, 1f)
            } else 0f
        } else 0f
        smoothedPitchDrop = max(freqDrop, smoothedPitchDrop * 0.72f)
        if (dominantFreqHz > 20f) {
            prevDominantFreqHz = dominantFreqHz
        }

        // 6. Transient Crest & Kick-Body Attack Slope (tracked on kickBodyNorm so steady 40Hz 808 doesn't mask kick attack)
        val attackDelta = max(0f, kickBodyNorm - prevLowEnergy)
        prevLowEnergy = kickBodyNorm * 0.80f + prevLowEnergy * 0.20f
        val crestRatio = if (attackDelta > 0.035f && combinedLowRaw > 0.005f) {
            (((rawPeak / max(0.01f, combinedLowRaw)) - 1.2f) / 3.2f).coerceIn(0f, 1f)
        } else 0f
        val transientCrest = (attackDelta * 2.8f + crestRatio * 0.45f).coerceIn(0f, 1f)

        // 7. Sustain Stationarity (consistent sub-bass energy across last ~95ms)
        subEnergyHistory[subHistoryCursor] = subNorm
        subHistoryCursor = (subHistoryCursor + 1) % subEnergyHistory.size
        var meanSub = 0f
        for (v in subEnergyHistory) meanSub += v
        meanSub /= subEnergyHistory.size
        var variance = 0f
        for (v in subEnergyHistory) {
            val diff = v - meanSub
            variance += diff * diff
        }
        val stdSub = sqrt(variance / subEnergyHistory.size)
        val sustainStationarity = if (meanSub > 0.08f) {
            (meanSub * (1f - (stdSub / max(0.08f, meanSub)).coerceIn(0f, 1f)) * 1.35f).coerceIn(0f, 1f)
        } else 0f

        val features = MirFeatureSnapshot(
            subRms = subNorm,
            kickBody = kickBodyNorm,
            bassHarmonic = harmNorm,
            beaterClick = clickNorm,
            superFlux = superFlux,
            hpssPercussive = hpssPercussive,
            hpssHarmonic = hpssHarmonic,
            pitchDrop = smoothedPitchDrop,
            transientCrest = transientCrest,
            sustainStationarity = sustainStationarity
        )

        // 8. Run On-Device TinyML Kick vs. Sub-Bass Neural Classifier
        val classification = classifier.classify(features, config, nowMs)

        // 9. Update real-time BPM estimate on kick onset
        if (classification.kickTriggered) {
            totalKickCount++
            recordKickForBpm(nowMs)
        }

        // 10. Build smooth 32-band low-end spectrum (20 Hz – 380 Hz log spaced)
        val minLog = ln(22.0)
        val maxLog = ln(380.0)
        for (b in 0 until SPECTRUM_DISPLAY_BINS) {
            val frac = b.toDouble() / (SPECTRUM_DISPLAY_BINS - 1)
            val freq = Math.E.pow(minLog + frac * (maxLog - minLog)).toFloat()
            val exactBin = (freq / binHz).coerceIn(1f, (LOW_BIN_COUNT - 2).toFloat())
            val lo = exactBin.toInt()
            val alpha = exactBin - lo
            val interpolated = currentMag[lo] * (1f - alpha) + currentMag[lo + 1] * alpha
            val scaled = (ln(1f + interpolated * agcScale * 12f) / 2.2f).coerceIn(0f, 1f)
            displaySpectrum[b] = if (scaled > displaySpectrum[b]) {
                displaySpectrum[b] + 0.55f * (scaled - displaySpectrum[b])
            } else {
                displaySpectrum[b] * 0.84f + scaled * 0.16f
            }
        }

        val peakDb = if (rawPeak > 1e-4f) {
            (20f * log10(rawPeak)).coerceIn(-60f, 0f)
        } else -60f

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000f

        return AcousticFrameTelemetry(
            kickProbability = classification.kickProbability,
            bassProbability = classification.bassProbability,
            kickTriggered = classification.kickTriggered,
            kickImpactStrength = classification.kickImpactStrength,
            bassDepth = classification.bassDepth,
            dominantLowFreqHz = dominantFreqHz,
            estimatedBpm = estimatedBpm,
            peakDb = peakDb,
            dspLatencyMs = elapsedMs,
            spectrumBins = displaySpectrum.toList(),
            features = features,
            kickCount = totalKickCount,
            frameTimestampMs = nowMs
        )
    }

    private fun recordKickForBpm(nowMs: Long) {
        val idx = kickOnsetCount % kickOnsetTimestamps.size
        kickOnsetTimestamps[idx] = nowMs
        kickOnsetCount++

        val valid = min(kickOnsetCount, kickOnsetTimestamps.size)
        if (valid < 3) return

        // Sort recent timestamps and compute median inter-onset interval (IOI)
        val sorted = LongArray(valid) { i -> kickOnsetTimestamps[i] }
        sorted.sort()
        val intervals = mutableListOf<Long>()
        for (i in 1 until sorted.size) {
            val dt = sorted[i] - sorted[i - 1]
            if (dt in 260L..1200L) { // 50..230 BPM range
                intervals.add(dt)
            }
        }
        if (intervals.isEmpty()) return
        intervals.sort()
        val medianIoi = intervals[intervals.size / 2]
        val rawBpm = (60_000.0 / medianIoi).toInt().coerceIn(55, 200)
        estimatedBpm = if (estimatedBpm == 0) rawBpm else ((estimatedBpm * 0.7f) + (rawBpm * 0.3f)).toInt()
    }

    private fun medianOf(arr: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        // Insertion sort on tiny array (<= 11 elements) with zero allocation
        for (i in 1 until count) {
            val key = arr[i]
            var j = i - 1
            while (j >= 0 && arr[j] > key) {
                arr[j + 1] = arr[j]
                j--
            }
            arr[j + 1] = key
        }
        return arr[count / 2]
    }
}
