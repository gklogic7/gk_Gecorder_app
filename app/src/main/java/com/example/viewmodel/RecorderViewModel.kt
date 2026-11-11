package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.RecorderRepository
import com.example.model.AudioEncoderCodec
import com.example.model.AudioSourceMode
import com.example.model.CaptureFilterTab
import com.example.model.FrameRatePreset
import com.example.model.HudPillMode
import com.example.model.ImportMediaEncoderState
import com.example.model.RecordedClip
import com.example.model.RecorderConfig
import com.example.model.RecorderStatus
import com.example.model.RecorderSubTab
import com.example.model.ResolutionPreset
import com.example.model.SavedRecorderPreset
import com.example.model.VideoEncoderCodec
import com.example.service.LiveRecordingTelemetry
import com.example.service.MediaImportEncoderEngine
import com.example.service.ScreenRecorderService
import com.example.service.ScreenRecorderStateHolder
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecorderUiState(
    val config: RecorderConfig = RecorderConfig(),
    val telemetry: LiveRecordingTelemetry = LiveRecordingTelemetry(),
    val clips: List<RecordedClip> = emptyList(),
    val savedPresets: List<SavedRecorderPreset> = emptyList(),
    val activeSubTab: RecorderSubTab = RecorderSubTab.RECORDER,
    val activeCaptureFilter: CaptureFilterTab = CaptureFilterTab.ALL,
    val saveConfirmationBadge: String? = null,
    val selectedClipForPlayback: RecordedClip? = null,
    val clipToRename: RecordedClip? = null,
    val recentSavedBannerClip: RecordedClip? = null,
    val importEncoderState: ImportMediaEncoderState = ImportMediaEncoderState()
) {
    val filteredClips: List<RecordedClip>
        get() = when (activeCaptureFilter) {
            CaptureFilterTab.ALL -> clips
            CaptureFilterTab.VIDEOS -> clips.filter { !it.isScreenshot }
            CaptureFilterTab.SCREENSHOTS -> clips.filter { it.isScreenshot }
        }
}

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecorderRepository(application.applicationContext)

    private val _activeSubTab = MutableStateFlow(RecorderSubTab.RECORDER)
    private val _activeCaptureFilter = MutableStateFlow(CaptureFilterTab.ALL)
    private val _saveConfirmationBadge = MutableStateFlow<String?>(null)
    private val _selectedClipForPlayback = MutableStateFlow<RecordedClip?>(null)
    private val _clipToRename = MutableStateFlow<RecordedClip?>(null)
    private val _recentSavedBannerClip = MutableStateFlow<RecordedClip?>(null)
    private val _importEncoderState = MutableStateFlow(ImportMediaEncoderState())

    private data class UiOverlayState(
        val subTab: RecorderSubTab,
        val filterTab: CaptureFilterTab,
        val saveBadge: String?,
        val selectedClip: RecordedClip?,
        val renameClip: RecordedClip?,
        val bannerClip: RecordedClip?,
        val importState: ImportMediaEncoderState
    )

    private val overlayStateFlow = combine(
        combine(_activeSubTab, _activeCaptureFilter, _saveConfirmationBadge) { a, b, c ->
            Triple(a, b, c)
        },
        combine(_selectedClipForPlayback, _clipToRename, _recentSavedBannerClip) { d, e, f ->
            Triple(d, e, f)
        },
        _importEncoderState
    ) { tabs, modals, importState ->
        UiOverlayState(
            subTab = tabs.first,
            filterTab = tabs.second,
            saveBadge = tabs.third,
            selectedClip = modals.first,
            renameClip = modals.second,
            bannerClip = modals.third,
            importState = importState
        )
    }

    val uiState: StateFlow<RecorderUiState> = combine(
        repository.configFlow,
        ScreenRecorderStateHolder.telemetry,
        repository.clipsFlow,
        repository.presetsFlow,
        overlayStateFlow
    ) { config, telemetry, clips, presets, overlay ->
        ScreenRecorderStateHolder.updateConfig(config)
        RecorderUiState(
            config = config,
            telemetry = telemetry,
            clips = clips,
            savedPresets = presets,
            activeSubTab = overlay.subTab,
            activeCaptureFilter = overlay.filterTab,
            saveConfirmationBadge = overlay.saveBadge,
            selectedClipForPlayback = overlay.selectedClip,
            clipToRename = overlay.renameClip,
            recentSavedBannerClip = overlay.bannerClip,
            importEncoderState = overlay.importState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RecorderUiState()
    )

    init {
        viewModelScope.launch {
            repository.syncWithMediaStore()
        }
        viewModelScope.launch {
            ScreenRecorderStateHolder.clipSavedEvents.collect { savedClip ->
                _recentSavedBannerClip.value = savedClip
            }
        }
    }

    fun selectSubTab(subTab: RecorderSubTab) {
        _activeSubTab.value = subTab
    }

    fun selectCaptureFilter(filter: CaptureFilterTab) {
        _activeCaptureFilter.value = filter
    }

    fun setResolution(preset: ResolutionPreset) {
        updateConfig { it.copy(resolution = preset) }
    }

    fun setFrameRate(frameRate: FrameRatePreset) {
        viewModelScope.launch {
            val updated = uiState.value.config.copy(frameRate = frameRate)
            ScreenRecorderStateHolder.updateConfig(updated)
            repository.updateConfig(updated)
            refreshAudioServiceConfig()
        }
    }

    fun setVideoCodec(videoCodec: VideoEncoderCodec) {
        viewModelScope.launch {
            val updated = uiState.value.config.copy(videoCodec = videoCodec)
            ScreenRecorderStateHolder.updateConfig(updated)
            repository.updateConfig(updated)
            refreshAudioServiceConfig()
        }
    }

    fun setAudioCodec(audioCodec: AudioEncoderCodec) {
        viewModelScope.launch {
            val updated = uiState.value.config.copy(audioCodec = audioCodec)
            ScreenRecorderStateHolder.updateConfig(updated)
            repository.updateConfig(updated)
            refreshAudioServiceConfig()
        }
    }

    fun setAudioBitrateKbps(kbps: Int) {
        viewModelScope.launch {
            val updated = uiState.value.config.copy(audioBitrateKbps = kbps.coerceIn(64, 320))
            ScreenRecorderStateHolder.updateConfig(updated)
            repository.updateConfig(updated)
            refreshAudioServiceConfig()
        }
    }

    fun setBitrateMbps(mbps: Float) {
        val snapped = ((mbps * 2f).roundToInt() / 2f).coerceIn(1.0f, 24.0f)
        updateConfig { it.copy(bitrateMbps = snapped) }
    }

    fun setAudioSource(mode: AudioSourceMode) {
        viewModelScope.launch {
            val updated = uiState.value.config.copy(audioSource = mode)
            ScreenRecorderStateHolder.updateConfig(updated)
            repository.updateConfig(updated)
            refreshAudioServiceConfig()
        }
    }

    fun cycleAudioSource() {
        val entries = AudioSourceMode.entries
        val currentIdx = entries.indexOf(uiState.value.config.audioSource)
        val next = entries[(currentIdx + 1) % entries.size]
        setAudioSource(next)
    }

    fun setAutoRecordDurationSeconds(seconds: Int) {
        updateConfig { it.copy(autoRecordDurationSeconds = seconds.coerceIn(3, 300)) }
    }

    fun setMultiShotCount(count: Int) {
        updateConfig { it.copy(multiShotCount = count.coerceIn(1, 20)) }
    }

    fun setMultiShotGapSeconds(seconds: Int) {
        updateConfig { it.copy(multiShotGapSeconds = seconds.coerceIn(0, 60)) }
    }

    fun setHudPillMode(mode: HudPillMode) {
        updateConfig { it.copy(hudPillMode = mode) }
    }

    fun saveCurrentOptionsToSystem(presetName: String? = null) {
        viewModelScope.launch {
            val current = uiState.value.config
            repository.updateConfig(current)
            ScreenRecorderStateHolder.updateConfig(current)
            if (!presetName.isNullOrBlank()) {
                val saved = repository.saveCustomPreset(presetName, current)
                showTemporarySaveBadge("Saved Preset: ${saved.name}")
            } else {
                showTemporarySaveBadge("Saved All Options ✓")
            }
        }
    }

    fun applySavedPreset(preset: SavedRecorderPreset) {
        viewModelScope.launch {
            val updated = uiState.value.config.copy(
                resolution = preset.resolution,
                frameRate = preset.frameRate,
                videoCodec = preset.videoCodec,
                audioCodec = preset.audioCodec,
                audioBitrateKbps = preset.audioBitrateKbps,
                bitrateMbps = preset.bitrateMbps,
                audioSource = preset.audioSource,
                autoRecordDurationSeconds = preset.autoRecordDurationSeconds,
                multiShotCount = preset.multiShotCount,
                multiShotGapSeconds = preset.multiShotGapSeconds
            )
            ScreenRecorderStateHolder.updateConfig(updated)
            repository.updateConfig(updated)
            refreshAudioServiceConfig()
            showTemporarySaveBadge("Loaded: ${preset.name}")
        }
    }

    fun deleteSavedPreset(presetId: String) {
        viewModelScope.launch {
            repository.deletePreset(presetId)
        }
    }

    fun selectImportedVideo(uri: Uri) {
        val context = getApplication<Application>().applicationContext
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModelScope.launch {
            val info = MediaImportEncoderEngine.inspectMediaUri(context, uri)
            _importEncoderState.value = _importEncoderState.value.copy(
                selectedVideoUri = uri.toString(),
                selectedVideoName = info.displayName,
                selectedVideoDurationMs = info.durationMillis,
                selectedVideoSizeBytes = info.sizeBytes,
                statusMessage = "Ready to encode video: ${info.displayName}"
            )
        }
    }

    fun selectImportedAudio(uri: Uri) {
        val context = getApplication<Application>().applicationContext
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModelScope.launch {
            val info = MediaImportEncoderEngine.inspectMediaUri(context, uri)
            _importEncoderState.value = _importEncoderState.value.copy(
                selectedAudioUri = uri.toString(),
                selectedAudioName = info.displayName,
                selectedAudioDurationMs = info.durationMillis,
                statusMessage = "Ready to encode audio: ${info.displayName}"
            )
        }
    }

    fun loadExistingClipIntoEncoder(clip: RecordedClip) {
        if (clip.isScreenshot) return
        _activeSubTab.value = RecorderSubTab.SAVED_CAPTURES
        _importEncoderState.value = _importEncoderState.value.copy(
            selectedVideoUri = clip.uriString,
            selectedVideoName = "${clip.title}.mp4",
            selectedVideoDurationMs = clip.durationMillis,
            selectedVideoSizeBytes = clip.sizeBytes,
            statusMessage = "Loaded '${clip.title}' • Choose codec & tap Encode"
        )
    }

    fun clearImportedMedia() {
        if (_importEncoderState.value.isEncoding) return
        _importEncoderState.value = ImportMediaEncoderState()
    }

    fun runImportAudioVideoEncoder() {
        val currentImport = _importEncoderState.value
        if (currentImport.isEncoding) return
        if (currentImport.selectedVideoUri == null && currentImport.selectedAudioUri == null) {
            _importEncoderState.value = currentImport.copy(
                statusMessage = "Select a video or audio file first (or tap Re-Encode on a saved clip)."
            )
            return
        }

        val context = getApplication<Application>().applicationContext
        val config = uiState.value.config

        viewModelScope.launch {
            _importEncoderState.value = currentImport.copy(
                isEncoding = true,
                encodeProgress = 0.05f,
                statusMessage = "Starting ${config.videoCodec.label} + ${config.audioCodec.label} (${config.audioBitrateKbps}k) encoder..."
            )

            val encodedClip = MediaImportEncoderEngine.encodeImportedMedia(
                context = context,
                config = config,
                videoUriString = currentImport.selectedVideoUri,
                audioUriString = currentImport.selectedAudioUri,
                onProgress = { progress, message ->
                    _importEncoderState.value = _importEncoderState.value.copy(
                        isEncoding = progress < 1.0f,
                        encodeProgress = progress,
                        statusMessage = message
                    )
                }
            )

            if (encodedClip != null) {
                repository.addRecordedClip(encodedClip)
                _recentSavedBannerClip.value = encodedClip
                _importEncoderState.value = _importEncoderState.value.copy(
                    isEncoding = false,
                    encodeProgress = 1.0f,
                    statusMessage = "Saved ${encodedClip.title} (${encodedClip.videoCodecLabel} • ${encodedClip.audioCodecLabel})"
                )
            } else {
                _importEncoderState.value = _importEncoderState.value.copy(
                    isEncoding = false,
                    encodeProgress = 0f,
                    statusMessage = "Encoder could not process the selected file. Try another clip."
                )
            }
        }
    }

    private fun showTemporarySaveBadge(message: String) {
        viewModelScope.launch {
            _saveConfirmationBadge.value = message
            delay(2500L)
            if (_saveConfirmationBadge.value == message) {
                _saveConfirmationBadge.value = null
            }
        }
    }

    fun refreshAudioServiceConfig() {
        val context = getApplication<Application>().applicationContext
        if (uiState.value.telemetry.isSessionArmed) {
            val intent = Intent(context, ScreenRecorderService::class.java).apply {
                action = ScreenRecorderService.ACTION_REFRESH_AUDIO_CONFIG
            }
            runCatching { context.startService(intent) }
        }
    }

    fun toggleAudioLiveCheck() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_TOGGLE_AUDIO_CHECK
        }
        runCatching { context.startService(intent) }
    }

    fun armSessionWithProjection(
        resultCode: Int,
        data: Intent,
        autoStartRecording: Boolean = false,
        autoTimedSeconds: Int = 0,
        takeScreenshotImmediately: Boolean = false
    ) {
        val context = getApplication<Application>().applicationContext
        val serviceIntent = Intent(context, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_ARM_OR_START
            putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenRecorderService.EXTRA_AUTO_START_RECORDING, autoStartRecording)
            putExtra(ScreenRecorderService.EXTRA_AUTO_TIMED_SECONDS, autoTimedSeconds)
            putExtra(ScreenRecorderService.EXTRA_TAKE_SCREENSHOT, takeScreenshotImmediately)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    fun toggleSingleTapRecordOrStop() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_TOGGLE_RECORD
        }
        context.startService(intent)
    }

    fun startLongPressTimedAutoRecord() {
        val context = getApplication<Application>().applicationContext
        val seconds = uiState.value.config.autoRecordDurationSeconds
        val intent = Intent(context, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_AUTO_RECORD_TIMED
            putExtra(ScreenRecorderService.EXTRA_AUTO_TIMED_SECONDS, seconds)
        }
        context.startService(intent)
    }

    fun triggerDoubleTapScreenshot() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_TAKE_SCREENSHOT
        }
        context.startService(intent)
    }

    fun pauseOrResumeRecording() {
        val context = getApplication<Application>().applicationContext
        val currentStatus = uiState.value.telemetry.status
        val action = when (currentStatus) {
            RecorderStatus.RECORDING -> ScreenRecorderService.ACTION_PAUSE
            RecorderStatus.PAUSED -> ScreenRecorderService.ACTION_RESUME
            else -> return
        }
        val intent = Intent(context, ScreenRecorderService::class.java).apply {
            this.action = action
        }
        context.startService(intent)
    }

    fun stopRecording() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun closeOverlaySession() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_CLOSE_SESSION
        }
        context.startService(intent)
    }

    fun openClipPlayback(clip: RecordedClip) {
        _selectedClipForPlayback.value = clip
    }

    fun closeClipPlayback() {
        _selectedClipForPlayback.value = null
    }

    fun promptRenameClip(clip: RecordedClip) {
        _clipToRename.value = clip
    }

    fun dismissRenameDialog() {
        _clipToRename.value = null
    }

    fun confirmRenameClip(newTitle: String) {
        val target = _clipToRename.value ?: return
        viewModelScope.launch {
            repository.renameClip(target.id, newTitle)
            if (_selectedClipForPlayback.value?.id == target.id) {
                _selectedClipForPlayback.value = target.copy(title = newTitle.trim())
            }
            _clipToRename.value = null
        }
    }

    fun deleteClip(clip: RecordedClip) {
        viewModelScope.launch {
            if (_selectedClipForPlayback.value?.id == clip.id) {
                _selectedClipForPlayback.value = null
            }
            if (_recentSavedBannerClip.value?.id == clip.id) {
                _recentSavedBannerClip.value = null
            }
            repository.deleteClip(clip)
        }
    }

    fun dismissSavedBanner() {
        _recentSavedBannerClip.value = null
    }

    fun clearError() {
        ScreenRecorderStateHolder.clearError()
    }

    private fun updateConfig(transform: (RecorderConfig) -> RecorderConfig) {
        viewModelScope.launch {
            val updated = transform(uiState.value.config)
            ScreenRecorderStateHolder.updateConfig(updated)
            repository.updateConfig(updated)
        }
    }
}
