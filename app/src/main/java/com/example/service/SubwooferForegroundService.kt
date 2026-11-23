package com.example.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.dsp.AcousticDspPipeline
import com.example.dsp.SubLabSynthesizer
import com.example.haptics.SubwooferHapticEngine
import com.example.model.AcousticFrameTelemetry
import com.example.model.CaptureSource
import com.example.model.EngineStatus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 14/15/16 compliant ForegroundService powering real-time 48kHz audio capture,
 * neural Kick vs. Sub-Bass DSP analysis, and synchronized haptic subwoofer playback in the background.
 *
 * Supports three live audio sources:
 * 1. INTERNAL_APP_AUDIO: AudioPlaybackCapture (API 29+) via MediaProjection (captures Spotify, YouTube, etc.)
 * 2. LIVE_MICROPHONE: Unprocessed low-latency mic for live speakers, concerts, or DRM-locked playback
 * 3. STUDIO_SUB_LAB: Real 48kHz AudioTrack synthesizer routed through the exact same DSP + ML + Haptic pipeline
 */
class SubwooferForegroundService : Service() {

    companion object {
        private const val TAG = "SubwooferFgService"
        private const val CHANNEL_ID = "subpulse_subwoofer_channel"
        private const val NOTIFICATION_ID = 4208

        const val ACTION_START_PLAYBACK_CAPTURE = "com.example.action.START_PLAYBACK_CAPTURE"
        const val ACTION_START_MIC_CAPTURE = "com.example.action.START_MIC_CAPTURE"
        const val ACTION_START_SUB_LAB = "com.example.action.START_SUB_LAB"
        const val ACTION_STOP = "com.example.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val SAMPLE_RATE = 48_000
        private const val READ_CHUNK = AcousticDspPipeline.HOP_SIZE // 256 samples = 5.33ms
        private const val UI_EMIT_INTERVAL_MS = 16L // ~60fps UI visualizer updates
    }

    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var hapticEngine: SubwooferHapticEngine? = null
    private val dspPipeline = AcousticDspPipeline(sampleRate = SAMPLE_RATE)
    private val synthesizer = SubLabSynthesizer(sampleRate = SAMPLE_RATE)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopEngineAndService()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        hapticEngine = SubwooferHapticEngine(applicationContext).also {
            SubwooferEngineBus.updateHardwareCapabilities(it.capabilities)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngineAndService()
                return START_NOT_STICKY
            }

            ACTION_START_PLAYBACK_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    SubwooferEngineBus.updateStatus(
                        EngineStatus.ERROR,
                        getString(R.string.error_api_playback_capture)
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    SubwooferEngineBus.updateStatus(
                        EngineStatus.ERROR,
                        getString(R.string.error_projection_denied)
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                stopCurrentWorker()
                // CRITICAL Android 14/15/16 order: startForeground with MEDIA_PROJECTION type
                // MUST execute BEFORE calling getMediaProjection()!
                promoteToForeground(
                    sourceLabel = getString(R.string.status_capturing_app),
                    fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    } else 0
                )
                startInternalPlaybackCapture(resultCode, resultData)
            }

            ACTION_START_MIC_CAPTURE -> {
                stopCurrentWorker()
                promoteToForeground(
                    sourceLabel = getString(R.string.status_capturing_mic),
                    fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else 0
                )
                startLiveMicCapture()
            }

            ACTION_START_SUB_LAB -> {
                stopCurrentWorker()
                promoteToForeground(
                    sourceLabel = getString(R.string.status_sub_lab_running),
                    fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else 0
                )
                startStudioSubLab()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startInternalPlaybackCapture(resultCode: Int, resultData: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection token unavailable")
            mediaProjection = projection
            // Mandatory on Android 14+ before capturing audio/video
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(READ_CHUNK * 4 * 4)

            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("AudioPlaybackCapture recorder failed to initialize")
            }

            audioRecord = record
            SubwooferEngineBus.updateActiveSource(CaptureSource.INTERNAL_APP_AUDIO)
            launchAudioRecordLoop(record)
        } catch (t: Throwable) {
            Log.e(TAG, "Internal playback capture failed", t)
            SubwooferEngineBus.updateStatus(
                EngineStatus.ERROR,
                t.localizedMessage ?: "Unable to start internal app audio capture"
            )
            stopEngineAndService()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLiveMicCapture() {
        try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(READ_CHUNK * 4 * 4)

            // Prefer UNPROCESSED audio source for flat low-end bass response without voice high-pass filter
            val candidateSources = listOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )

            var initializedRecord: AudioRecord? = null
            for (src in candidateSources) {
                try {
                    val rec = AudioRecord.Builder()
                        .setAudioSource(src)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(minBuf * 2)
                        .build()
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        initializedRecord = rec
                        break
                    } else {
                        rec.release()
                    }
                } catch (_: Throwable) {
                }
            }

            val record = initializedRecord
                ?: throw IllegalStateException("Microphone AudioRecord failed to initialize")

            audioRecord = record
            SubwooferEngineBus.updateActiveSource(CaptureSource.LIVE_MICROPHONE)
            launchAudioRecordLoop(record)
        } catch (t: Throwable) {
            Log.e(TAG, "Live mic capture failed", t)
            SubwooferEngineBus.updateStatus(
                EngineStatus.ERROR,
                t.localizedMessage ?: "Unable to start live microphone capture"
            )
            stopEngineAndService()
        }
    }

    private fun launchAudioRecordLoop(record: AudioRecord) {
        dspPipeline.reset()
        isRunning.set(true)
        SubwooferEngineBus.updateStatus(EngineStatus.RUNNING)

        workerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = FloatArray(READ_CHUNK)
            var lastUiEmitMs = 0L

            try {
                record.startRecording()
                while (isRunning.get()) {
                    val read = record.read(buffer, 0, READ_CHUNK, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        val nowMs = SystemClock.elapsedRealtime()
                        val config = SubwooferEngineBus.tuningConfig.value
                        dspPipeline.processBuffer(buffer, read, config, nowMs) { frame ->
                            val hapticState = hapticEngine?.processTelemetry(frame, config, nowMs)
                            val enriched = if (hapticState != null) {
                                frame.copy(
                                    hapticDriveLevel = hapticState.driveLevel,
                                    hapticMotorFreqHz = hapticState.motorFreqHz,
                                    isSidechainDucking = hapticState.isSidechainDucking
                                )
                            } else frame

                            if (enriched.kickTriggered || nowMs - lastUiEmitMs >= UI_EMIT_INTERVAL_MS) {
                                lastUiEmitMs = nowMs
                                SubwooferEngineBus.updateTelemetry(enriched)
                            }
                        }
                    } else if (read < 0) {
                        Thread.sleep(10L)
                    }
                }
            } catch (t: Throwable) {
                if (isRunning.get()) {
                    Log.e(TAG, "Audio capture loop terminated", t)
                    SubwooferEngineBus.updateStatus(
                        EngineStatus.ERROR,
                        t.localizedMessage ?: "Audio capture interrupted"
                    )
                }
            }
        }, "SubPulse-AudioDSP").apply { start() }
    }

    private fun startStudioSubLab() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(READ_CHUNK * 4 * 4)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            dspPipeline.reset()
            synthesizer.reset()
            SubwooferEngineBus.updateActiveSource(CaptureSource.STUDIO_SUB_LAB)
            isRunning.set(true)
            SubwooferEngineBus.updateStatus(EngineStatus.RUNNING)

            workerThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val pcmBlock = FloatArray(READ_CHUNK)
                var lastUiEmitMs = 0L
                var seenKickCounter = SubwooferEngineBus.manualKickPulseCounter

                try {
                    track.play()
                    while (isRunning.get()) {
                        synthesizer.activePattern = SubwooferEngineBus.subLabPattern.value
                        synthesizer.manualBassHeld = SubwooferEngineBus.manualBassHeld

                        val curKickCounter = SubwooferEngineBus.manualKickPulseCounter
                        if (curKickCounter != seenKickCounter) {
                            seenKickCounter = curKickCounter
                            synthesizer.triggerManualKick()
                        }

                        // Generate real 48kHz audio block
                        synthesizer.renderBlock(pcmBlock, READ_CHUNK)

                        // Play audible audio through phone speakers/headphones
                        track.write(pcmBlock, 0, READ_CHUNK, AudioTrack.WRITE_BLOCKING)

                        // Analyze the exact same live PCM through the real DSP + Neural Classifier + Haptic Engine
                        val nowMs = SystemClock.elapsedRealtime()
                        val config = SubwooferEngineBus.tuningConfig.value
                        dspPipeline.processBuffer(pcmBlock, READ_CHUNK, config, nowMs) { frame ->
                            val hapticState = hapticEngine?.processTelemetry(frame, config, nowMs)
                            val enriched = if (hapticState != null) {
                                frame.copy(
                                    hapticDriveLevel = hapticState.driveLevel,
                                    hapticMotorFreqHz = hapticState.motorFreqHz,
                                    isSidechainDucking = hapticState.isSidechainDucking
                                )
                            } else frame

                            if (enriched.kickTriggered || nowMs - lastUiEmitMs >= UI_EMIT_INTERVAL_MS) {
                                lastUiEmitMs = nowMs
                                SubwooferEngineBus.updateTelemetry(enriched)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Sub-Lab synth loop error", t)
                        SubwooferEngineBus.updateStatus(
                            EngineStatus.ERROR,
                            t.localizedMessage ?: "Sub-Lab audio output interrupted"
                        )
                    }
                }
            }, "SubPulse-SubLab").apply { start() }
        } catch (t: Throwable) {
            Log.e(TAG, "Sub-Lab startup error", t)
            SubwooferEngineBus.updateStatus(
                EngineStatus.ERROR,
                t.localizedMessage ?: "Unable to start Studio Sub-Lab"
            )
            stopEngineAndService()
        }
    }

    private fun stopCurrentWorker() {
        isRunning.set(false)
        workerThread?.interrupt()
        try {
            workerThread?.join(300L)
        } catch (_: InterruptedException) {
        }
        workerThread = null

        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Throwable) {
        }
        audioTrack = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null

        hapticEngine?.stopAll()
    }

    private fun stopEngineAndService() {
        stopCurrentWorker()
        SubwooferEngineBus.updateTelemetry(AcousticFrameTelemetry())
        if (SubwooferEngineBus.status.value != EngineStatus.ERROR) {
            SubwooferEngineBus.updateStatus(EngineStatus.IDLE)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCurrentWorker()
        hapticEngine?.release()
        hapticEngine = null
        super.onDestroy()
    }

    private fun promoteToForeground(sourceLabel: String, fgsType: Int) {
        val notification = buildNotification(sourceLabel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fgsType != 0) {
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(subtitle: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SubwooferForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(getString(R.string.notif_title_running))
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notif_action_stop),
                stopIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }
}
