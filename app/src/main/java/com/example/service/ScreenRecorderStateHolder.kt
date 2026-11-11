package com.example.service

import com.example.model.GestureIconState
import com.example.model.RecordedClip
import com.example.model.RecorderConfig
import com.example.model.RecorderStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LiveRecordingTelemetry(
    val isSessionArmed: Boolean = false,
    val isSystemOverlayShowing: Boolean = false,
    val status: RecorderStatus = RecorderStatus.STANDBY,
    val gestureIconState: GestureIconState = GestureIconState.SINGLE_TAP_IDLE,
    val autoStopTargetMillis: Long = 0L,
    val isMultiShotGap: Boolean = false,
    val currentShotIndex: Int = 1,
    val totalShots: Int = 1,
    val gapRemainingMillis: Long = 0L,
    val lastGestureLabel: String = "1x Tap: Rec/Stop • 2x Tap: SS • Hold: Multi-Shot",
    val countdownRemaining: Int = 0,
    val elapsedMillis: Long = 0L,
    val estimatedSizeBytes: Long = 0L,
    val audioPeakLevel: Float = 0f,
    val isAudioMonitorActive: Boolean = false,
    val internalAudioReady: Boolean = false,
    val micAudioReady: Boolean = false,
    val audioStatusDetail: String = "Ready",
    val liveFps: Float = 60.0f,
    val errorMessage: String? = null
)

object ScreenRecorderStateHolder {
    private val _telemetry = MutableStateFlow(LiveRecordingTelemetry())
    val telemetry: StateFlow<LiveRecordingTelemetry> = _telemetry.asStateFlow()

    private val _activeConfig = MutableStateFlow(RecorderConfig())
    val activeConfig: StateFlow<RecorderConfig> = _activeConfig.asStateFlow()

    private val _clipSavedEvents = MutableSharedFlow<RecordedClip>(extraBufferCapacity = 4)
    val clipSavedEvents: SharedFlow<RecordedClip> = _clipSavedEvents.asSharedFlow()

    private val _closeAppEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val closeAppEvents: SharedFlow<Unit> = _closeAppEvents.asSharedFlow()

    fun updateConfig(config: RecorderConfig) {
        _activeConfig.value = config
    }

    fun updateTelemetry(transform: (LiveRecordingTelemetry) -> LiveRecordingTelemetry) {
        _telemetry.update(transform)
    }

    fun clearError() {
        _telemetry.update { it.copy(errorMessage = null) }
    }

    fun emitSavedClip(clip: RecordedClip) {
        _clipSavedEvents.tryEmit(clip)
    }

    fun emitCloseApp() {
        _closeAppEvents.tryEmit(Unit)
    }
}
