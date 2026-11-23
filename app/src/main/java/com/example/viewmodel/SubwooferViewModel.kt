package com.example.viewmodel

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.TuningPreferencesRepository
import com.example.haptics.SubwooferHapticEngine
import com.example.model.AcousticFrameTelemetry
import com.example.model.CaptureSource
import com.example.model.EngineStatus
import com.example.model.HapticHardwareCapabilities
import com.example.model.SubLabPattern
import com.example.model.SubwooferPreset
import com.example.model.TuningConfig
import com.example.service.SubwooferEngineBus
import com.example.service.SubwooferForegroundService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SubwooferUiState(
    val status: EngineStatus = EngineStatus.IDLE,
    val selectedSource: CaptureSource = CaptureSource.INTERNAL_APP_AUDIO,
    val activeSource: CaptureSource = CaptureSource.INTERNAL_APP_AUDIO,
    val tuning: TuningConfig = TuningConfig(),
    val telemetry: AcousticFrameTelemetry = AcousticFrameTelemetry(),
    val hardware: HapticHardwareCapabilities = HapticHardwareCapabilities(),
    val subLabPattern: SubLabPattern = SubLabPattern.TRAP_808,
    val errorMessage: String? = null
)

class SubwooferViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TuningPreferencesRepository(application.applicationContext)
    private val calibrationEngine = SubwooferHapticEngine(application.applicationContext)

    init {
        SubwooferEngineBus.updateHardwareCapabilities(calibrationEngine.capabilities)
        viewModelScope.launch {
            val savedTuning = repository.tuningConfigFlow.first()
            val savedSource = repository.captureSourceFlow.first()
            SubwooferEngineBus.updateTuningConfig(savedTuning)
            if (SubwooferEngineBus.status.value == EngineStatus.IDLE) {
                SubwooferEngineBus.updateActiveSource(savedSource)
            }
        }
    }

    val uiState: StateFlow<SubwooferUiState> = combine(
        SubwooferEngineBus.status,
        SubwooferEngineBus.activeSource,
        SubwooferEngineBus.tuningConfig,
        SubwooferEngineBus.telemetry,
        SubwooferEngineBus.hardwareCapabilities,
        SubwooferEngineBus.subLabPattern,
        SubwooferEngineBus.errorMessage
    ) { values ->
        val status = values[0] as EngineStatus
        val source = values[1] as CaptureSource
        val tuning = values[2] as TuningConfig
        val telemetry = values[3] as AcousticFrameTelemetry
        val hardware = values[4] as HapticHardwareCapabilities
        val pattern = values[5] as SubLabPattern
        val error = values[6] as String?
        SubwooferUiState(
            status = status,
            selectedSource = source,
            activeSource = source,
            tuning = tuning,
            telemetry = telemetry,
            hardware = hardware,
            subLabPattern = pattern,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SubwooferUiState(hardware = calibrationEngine.capabilities)
    )

    fun selectSource(source: CaptureSource) {
        SubwooferEngineBus.clearError()
        SubwooferEngineBus.updateActiveSource(source)
        viewModelScope.launch {
            repository.saveCaptureSource(source)
        }
    }

    fun applyPreset(preset: SubwooferPreset) {
        val current = SubwooferEngineBus.tuningConfig.value
        val updated = TuningConfig.forPreset(preset, current.latencyOffsetMs)
            .copy(hapticsMuted = current.hapticsMuted)
        commitTuning(updated)
    }

    fun updateTuning(transform: (TuningConfig) -> TuningConfig) {
        val current = SubwooferEngineBus.tuningConfig.value
        val updated = transform(current).copy(activePreset = SubwooferPreset.CUSTOM)
        commitTuning(updated)
    }

    fun toggleHapticMute() {
        val current = SubwooferEngineBus.tuningConfig.value
        val updated = current.copy(hapticsMuted = !current.hapticsMuted)
        SubwooferEngineBus.updateTuningConfig(updated)
        if (updated.hapticsMuted) {
            calibrationEngine.stopAll()
        }
    }

    fun resetTuningToDefaults() {
        val defaults = TuningConfig.forPreset(SubwooferPreset.CLUB_808, currentLatencyMs = 0)
        commitTuning(defaults)
    }

    private fun commitTuning(config: TuningConfig) {
        SubwooferEngineBus.updateTuningConfig(config)
        viewModelScope.launch {
            repository.saveTuningConfig(config)
        }
    }

    fun selectSubLabPattern(pattern: SubLabPattern) {
        SubwooferEngineBus.updateSubLabPattern(pattern)
    }

    fun triggerManualKickPad() {
        SubwooferEngineBus.triggerManualKickPad()
        if (SubwooferEngineBus.status.value != EngineStatus.RUNNING) {
            calibrationEngine.playKickCalibrationTest(SubwooferEngineBus.tuningConfig.value)
        }
    }

    fun setManualBassHeld(held: Boolean) {
        SubwooferEngineBus.manualBassHeld = held
    }

    fun playKickCalibration() {
        calibrationEngine.playKickCalibrationTest(SubwooferEngineBus.tuningConfig.value)
    }

    fun playBassCalibration() {
        calibrationEngine.playBassCalibrationTest(SubwooferEngineBus.tuningConfig.value)
    }

    fun playSidechainCalibration() {
        calibrationEngine.playSidechainComboTest(SubwooferEngineBus.tuningConfig.value)
    }

    fun startPlaybackCaptureService(resultCode: Int, resultData: Intent) {
        SubwooferEngineBus.clearError()
        val intent = Intent(getApplication(), SubwooferForegroundService::class.java).apply {
            action = SubwooferForegroundService.ACTION_START_PLAYBACK_CAPTURE
            putExtra(SubwooferForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(SubwooferForegroundService.EXTRA_RESULT_DATA, resultData)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startLiveMicService() {
        SubwooferEngineBus.clearError()
        val intent = Intent(getApplication(), SubwooferForegroundService::class.java).apply {
            action = SubwooferForegroundService.ACTION_START_MIC_CAPTURE
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startSubLabService() {
        SubwooferEngineBus.clearError()
        val intent = Intent(getApplication(), SubwooferForegroundService::class.java).apply {
            action = SubwooferForegroundService.ACTION_START_SUB_LAB
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun stopSubwooferService() {
        SubwooferEngineBus.manualBassHeld = false
        val intent = Intent(getApplication(), SubwooferForegroundService::class.java).apply {
            action = SubwooferForegroundService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    fun reportError(message: String) {
        SubwooferEngineBus.updateStatus(EngineStatus.ERROR, message)
    }

    fun clearError() {
        SubwooferEngineBus.clearError()
        if (SubwooferEngineBus.status.value == EngineStatus.ERROR) {
            SubwooferEngineBus.updateStatus(EngineStatus.IDLE)
        }
    }

    override fun onCleared() {
        calibrationEngine.release()
        super.onCleared()
    }
}
