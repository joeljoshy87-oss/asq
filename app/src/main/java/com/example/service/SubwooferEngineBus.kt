package com.example.service

import com.example.model.AcousticFrameTelemetry
import com.example.model.CaptureSource
import com.example.model.EngineStatus
import com.example.model.HapticHardwareCapabilities
import com.example.model.SubLabPattern
import com.example.model.TuningConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped real-time telemetry & control bus connecting SubwooferForegroundService
 * and the Compose UI / ViewModel cleanly without holding Activity references.
 */
object SubwooferEngineBus {

    private val _status = MutableStateFlow(EngineStatus.IDLE)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _activeSource = MutableStateFlow(CaptureSource.INTERNAL_APP_AUDIO)
    val activeSource: StateFlow<CaptureSource> = _activeSource.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _telemetry = MutableStateFlow(AcousticFrameTelemetry())
    val telemetry: StateFlow<AcousticFrameTelemetry> = _telemetry.asStateFlow()

    private val _tuningConfig = MutableStateFlow(TuningConfig())
    val tuningConfig: StateFlow<TuningConfig> = _tuningConfig.asStateFlow()

    private val _hardwareCapabilities = MutableStateFlow(HapticHardwareCapabilities())
    val hardwareCapabilities: StateFlow<HapticHardwareCapabilities> = _hardwareCapabilities.asStateFlow()

    private val _subLabPattern = MutableStateFlow(SubLabPattern.TRAP_808)
    val subLabPattern: StateFlow<SubLabPattern> = _subLabPattern.asStateFlow()

    @Volatile
    var manualBassHeld: Boolean = false

    @Volatile
    var manualKickPulseCounter: Long = 0L

    fun updateStatus(status: EngineStatus, error: String? = null) {
        _status.value = status
        _errorMessage.value = error
    }

    fun updateActiveSource(source: CaptureSource) {
        _activeSource.value = source
    }

    fun updateTelemetry(frame: AcousticFrameTelemetry) {
        _telemetry.value = frame
    }

    fun updateTuningConfig(config: TuningConfig) {
        _tuningConfig.value = config
    }

    fun updateHardwareCapabilities(caps: HapticHardwareCapabilities) {
        _hardwareCapabilities.value = caps
    }

    fun updateSubLabPattern(pattern: SubLabPattern) {
        _subLabPattern.value = pattern
    }

    fun triggerManualKickPad() {
        manualKickPulseCounter++
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
