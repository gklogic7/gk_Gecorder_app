package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.MainActivity
import com.example.R
import com.example.data.RecorderRepository
import com.example.model.AudioSourceMode
import com.example.model.GestureIconState
import com.example.model.RecordedClip
import com.example.model.RecorderConfig
import com.example.model.RecorderStatus
import com.example.model.formatFloatingPointTimecode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenRecorderService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: RecorderRepository
    private lateinit var overlayViewManager: FloatingOverlayViewManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var standbyImageReader: ImageReader? = null
    private var currentDisplayWidth: Int = 0
    private var currentDisplayHeight: Int = 0

    // Primary Dual-Stream (Internal + Mic) MediaCodec/MediaMuxer Session
    private var activeMuxSession: MixedAvCaptureSession? = null

    // Fallback MediaRecorder if needed
    private var fallbackMediaRecorder: MediaRecorder? = null

    private var outputPfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var fallbackFile: File? = null
    private var currentClipTitle: String = ""
    private var hasActiveAudio: Boolean = false
    private var activeAudioStatusLabel: String = "No Audio"

    private var timerJob: Job? = null
    private var multiShotGapJob: Job? = null
    private var audioMonitorJob: Job? = null

    private var accumulatedMillis: Long = 0L
    private var lastResumeRealtime: Long = 0L
    private var isPausedState: Boolean = false

    private var activeAutoStopTargetMillis: Long = 0L
    private var activeShotIndex: Int = 1
    private var activeTotalShots: Int = 1
    private var activeMultiShotGapSeconds: Int = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            closeEntireSession(closeAppToo = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = RecorderRepository(applicationContext)
        createNotificationChannel()

        overlayViewManager = FloatingOverlayViewManager(
            context = applicationContext,
            onSingleTapRecordOrStop = {
                toggleSingleTapRecordOrStop()
            },
            onDoubleTapScreenshot = {
                captureDoubleTapScreenshot()
            },
            onLongPressAutoRecord = {
                startTimedAutoRecording()
            },
            onCloseSessionAndApp = {
                closeEntireSession(closeAppToo = true)
            }
        )

        serviceScope.launch {
            ScreenRecorderStateHolder.telemetry.collect { telemetry ->
                overlayViewManager.updateTelemetry(telemetry)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM_OR_START -> {
                val autoStartRecording = intent.getBooleanExtra(EXTRA_AUTO_START_RECORDING, false)
                val autoTimedSeconds = intent.getIntExtra(EXTRA_AUTO_TIMED_SECONDS, 0)
                val takeScreenshotImmediately = intent.getBooleanExtra(EXTRA_TAKE_SCREENSHOT, false)

                if (mediaProjection != null && virtualDisplay != null) {
                    refreshForegroundTypesAndAudioCapabilities()
                    overlayViewManager.show()
                    when {
                        takeScreenshotImmediately -> captureDoubleTapScreenshot()
                        autoTimedSeconds > 0 -> startTimedAutoRecording(autoTimedSeconds)
                        autoStartRecording -> toggleSingleTapRecordOrStop()
                    }
                } else {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }

                    if (resultCode == Activity.RESULT_OK && resultData != null) {
                        armSessionOnce(
                            resultCode = resultCode,
                            resultData = resultData,
                            autoStartRecording = autoStartRecording,
                            autoTimedSeconds = autoTimedSeconds,
                            takeScreenshotImmediately = takeScreenshotImmediately
                        )
                    } else {
                        ScreenRecorderStateHolder.updateTelemetry {
                            it.copy(
                                status = RecorderStatus.STANDBY,
                                errorMessage = "Whole-screen capture permission was not granted."
                            )
                        }
                        stopSelf()
                    }
                }
            }
            ACTION_REFRESH_AUDIO_CONFIG -> {
                refreshForegroundTypesAndAudioCapabilities()
            }
            ACTION_TOGGLE_AUDIO_CHECK -> {
                toggleLiveAudioMonitorCheck()
            }
            ACTION_TOGGLE_RECORD -> toggleSingleTapRecordOrStop()
            ACTION_RECORD_FROM_WARM -> {
                cancelMultiShotGapIfActive()
                activeShotIndex = 1
                activeTotalShots = 1
                activeMultiShotGapSeconds = 0
                startClipFromWarmSession(
                    autoStopDurationMillis = 0L,
                    shotIndex = 1,
                    totalShots = 1
                )
            }
            ACTION_AUTO_RECORD_TIMED -> {
                val config = ScreenRecorderStateHolder.activeConfig.value
                val seconds = intent.getIntExtra(
                    EXTRA_AUTO_TIMED_SECONDS,
                    config.autoRecordDurationSeconds
                )
                startTimedAutoRecording(seconds)
            }
            ACTION_TAKE_SCREENSHOT -> captureDoubleTapScreenshot()
            ACTION_PAUSE -> pauseCaptureSession()
            ACTION_RESUME -> resumeCaptureSession()
            ACTION_STOP -> {
                cancelMultiShotGapIfActive()
                activeTotalShots = 1
                stopCurrentClipKeepSessionWarm(isPartOfMultiShotBurst = false)
            }
            ACTION_CLOSE_SESSION -> closeEntireSession(closeAppToo = true)
        }
        return START_STICKY
    }

    private fun isMicPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshForegroundTypesAndAudioCapabilities() {
        val micGranted = isMicPermissionGranted()
        val config = ScreenRecorderStateHolder.activeConfig.value

        if (mediaProjection != null) {
            val notification = buildNotification(
                statusText = "Entire Screen Ready • ${config.audioSource.label}",
                isRecording = ScreenRecorderStateHolder.telemetry.value.status == RecorderStatus.RECORDING,
                isPaused = ScreenRecorderStateHolder.telemetry.value.status == RecorderStatus.PAUSED
            )
            runCatching {
                startForegroundSafely(notification, includeMicType = micGranted)
            }
        }

        val internalSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mediaProjection != null && micGranted
        val micSupported = micGranted

        val statusDetail = when (config.audioSource) {
            AudioSourceMode.NO_AUDIO -> "No Audio (Muted Video Only)"
            AudioSourceMode.INTERNAL_AUDIO -> when {
                !micGranted -> "Needs Audio Permission for Internal Capture"
                mediaProjection == null -> "Internal Audio Ready (Arms with Screen)"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> "Internal Audio Active (System Playback 44.1kHz)"
                else -> "Internal Audio Fallback Active"
            }
            AudioSourceMode.MICROPHONE -> if (micGranted) {
                "Microphone Active (Voice Capture 44.1kHz)"
            } else {
                "Needs Microphone Permission"
            }
            AudioSourceMode.INTERNAL_PLUS_MIC -> when {
                !micGranted -> "Needs Audio Permission for Internal + Mic"
                mediaProjection == null -> "Internal + Mic Mixer Ready (Arms with Screen)"
                else -> "Internal + Mic Dual Mixer Active (44.1kHz AAC)"
            }
        }

        ScreenRecorderStateHolder.updateTelemetry {
            it.copy(
                internalAudioReady = internalSupported,
                micAudioReady = micSupported,
                audioStatusDetail = statusDetail
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleLiveAudioMonitorCheck() {
        if (audioMonitorJob?.isActive == true) {
            stopLiveAudioMonitorCheck()
            return
        }
        if (ScreenRecorderStateHolder.telemetry.value.status == RecorderStatus.RECORDING) {
            return
        }

        val config = ScreenRecorderStateHolder.activeConfig.value
        if (config.audioSource == AudioSourceMode.NO_AUDIO) {
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    isAudioMonitorActive = false,
                    audioPeakLevel = 0f,
                    audioStatusDetail = "No Audio Selected • Video will be silent"
                )
            }
            return
        }

        if (!isMicPermissionGranted()) {
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    isAudioMonitorActive = false,
                    audioStatusDetail = "Grant Microphone permission to test ${config.audioSource.label}"
                )
            }
            return
        }

        refreshForegroundTypesAndAudioCapabilities()

        audioMonitorJob = serviceScope.launch(Dispatchers.IO) {
            val sampleRate = 44100
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            var internalRecord: AudioRecord? = null
            var micRecord: AudioRecord? = null

            try {
                if ((config.audioSource == AudioSourceMode.INTERNAL_AUDIO ||
                        config.audioSource == AudioSourceMode.INTERNAL_PLUS_MIC) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    mediaProjection != null
                ) {
                    runCatching {
                        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build()
                        val rec = AudioRecord.Builder()
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .build()
                            )
                            .setBufferSizeInBytes(minBuffer * 2)
                            .setAudioPlaybackCaptureConfig(playbackConfig)
                            .build()
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            rec.startRecording()
                            internalRecord = rec
                        } else {
                            rec.release()
                        }
                    }
                }

                if (config.audioSource == AudioSourceMode.MICROPHONE ||
                    config.audioSource == AudioSourceMode.INTERNAL_PLUS_MIC ||
                    (config.audioSource == AudioSourceMode.INTERNAL_AUDIO && internalRecord == null)
                ) {
                    runCatching {
                        val rec = createReliableMicAudioRecord(sampleRate, minBuffer * 2)
                        if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                            rec.startRecording()
                            micRecord = rec
                        }
                    }
                }

                val internalActive = internalRecord != null
                val micActive = micRecord != null
                val statusMsg = when (config.audioSource) {
                    AudioSourceMode.INTERNAL_PLUS_MIC ->
                        if (internalActive && micActive) {
                            "Testing Internal + Mic: Both Streams Live!"
                        } else if (micActive) {
                            "Testing Mic Live (Arm screen for Internal stream)"
                        } else {
                            "Audio Check Active"
                        }
                    AudioSourceMode.INTERNAL_AUDIO ->
                        if (internalActive) {
                            "Testing Internal Audio: System Playback Live!"
                        } else {
                            "Testing Audio Stream Live"
                        }
                    AudioSourceMode.MICROPHONE ->
                        if (micActive) {
                            "Testing Microphone: Speak to see Live Meter!"
                        } else {
                            "Microphone Check Error"
                        }
                    AudioSourceMode.NO_AUDIO -> "No Audio"
                }

                ScreenRecorderStateHolder.updateTelemetry {
                    it.copy(
                        isAudioMonitorActive = true,
                        internalAudioReady = internalActive,
                        micAudioReady = micActive,
                        audioStatusDetail = statusMsg
                    )
                }

                val internalBuf = ShortArray(1024)
                val micBuf = ShortArray(1024)
                val startMs = SystemClock.elapsedRealtime()

                while (isActive && SystemClock.elapsedRealtime() - startMs < 12_000L) {
                    var maxSample = 0
                    var sumSquares = 0.0
                    var count = 0

                    if (internalRecord != null) {
                        val r = internalRecord.read(internalBuf, 0, internalBuf.size)
                        if (r > 0) {
                            for (i in 0 until r) {
                                val s = abs(internalBuf[i].toInt())
                                if (s > maxSample) maxSample = s
                                sumSquares += s.toDouble() * s.toDouble()
                            }
                            count = maxOf(count, r)
                        }
                    }

                    if (micRecord != null) {
                        val r = micRecord.read(micBuf, 0, micBuf.size)
                        if (r > 0) {
                            for (i in 0 until r) {
                                val s = abs(micBuf[i].toInt())
                                if (s > maxSample) maxSample = s
                                sumSquares += s.toDouble() * s.toDouble()
                            }
                            count = maxOf(count, r)
                        }
                    }

                    val rms = if (count > 0) sqrt(sumSquares / count) else 0.0
                    val level = ((maxSample / 12000f) * 0.6f + (rms.toFloat() / 6000f) * 0.4f)
                        .coerceIn(0.06f, 1.0f)

                    ScreenRecorderStateHolder.updateTelemetry {
                        it.copy(
                            isAudioMonitorActive = true,
                            audioPeakLevel = level
                        )
                    }
                    delay(45L)
                }
            } finally {
                runCatching { internalRecord?.stop() }
                runCatching { internalRecord?.release() }
                runCatching { micRecord?.stop() }
                runCatching { micRecord?.release() }
                ScreenRecorderStateHolder.updateTelemetry {
                    it.copy(
                        isAudioMonitorActive = false,
                        audioPeakLevel = 0f
                    )
                }
                withContext(Dispatchers.Main) {
                    refreshForegroundTypesAndAudioCapabilities()
                }
            }
        }
    }

    private fun stopLiveAudioMonitorCheck() {
        audioMonitorJob?.cancel()
        audioMonitorJob = null
        ScreenRecorderStateHolder.updateTelemetry {
            it.copy(isAudioMonitorActive = false, audioPeakLevel = 0f)
        }
        refreshForegroundTypesAndAudioCapabilities()
    }

    private fun cancelMultiShotGapIfActive() {
        multiShotGapJob?.cancel()
        multiShotGapJob = null
        ScreenRecorderStateHolder.updateTelemetry {
            it.copy(
                isMultiShotGap = false,
                gapRemainingMillis = 0L
            )
        }
    }

    private fun toggleSingleTapRecordOrStop() {
        val telemetry = ScreenRecorderStateHolder.telemetry.value
        if (telemetry.isMultiShotGap) {
            cancelMultiShotGapIfActive()
            activeShotIndex = 1
            activeTotalShots = 1
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    status = RecorderStatus.STANDBY,
                    gestureIconState = GestureIconState.SINGLE_TAP_IDLE,
                    currentShotIndex = 1,
                    totalShots = 1,
                    lastGestureLabel = "Multi-Shot Sequence Cancelled • Ready"
                )
            }
            return
        }

        val status = telemetry.status
        if (status == RecorderStatus.RECORDING || status == RecorderStatus.PAUSED) {
            cancelMultiShotGapIfActive()
            activeTotalShots = 1
            stopCurrentClipKeepSessionWarm(isPartOfMultiShotBurst = false)
        } else if (status == RecorderStatus.STANDBY) {
            activeShotIndex = 1
            activeTotalShots = 1
            activeMultiShotGapSeconds = 0
            startClipFromWarmSession(
                autoStopDurationMillis = 0L,
                shotIndex = 1,
                totalShots = 1
            )
        }
    }

    private fun startTimedAutoRecording(overrideSeconds: Int = 0) {
        val telemetry = ScreenRecorderStateHolder.telemetry.value
        if (telemetry.isMultiShotGap ||
            telemetry.status == RecorderStatus.RECORDING ||
            telemetry.status == RecorderStatus.PAUSED
        ) {
            cancelMultiShotGapIfActive()
            activeTotalShots = 1
            stopCurrentClipKeepSessionWarm(isPartOfMultiShotBurst = false)
            return
        }

        val config = ScreenRecorderStateHolder.activeConfig.value
        val seconds = if (overrideSeconds > 0) {
            overrideSeconds
        } else {
            config.autoRecordDurationSeconds
        }.coerceIn(3, 300)

        activeShotIndex = 1
        activeTotalShots = config.multiShotCount.coerceIn(1, 20)
        activeMultiShotGapSeconds = config.multiShotGapSeconds.coerceIn(0, 60)

        startClipFromWarmSession(
            autoStopDurationMillis = seconds * 1000L,
            shotIndex = activeShotIndex,
            totalShots = activeTotalShots
        )
    }

    private fun resolveTargetVideoDimensions(): Pair<Int, Int> {
        val config = ScreenRecorderStateHolder.activeConfig.value
        val dm = resources.displayMetrics
        return config.resolution.resolveDimensions(dm.widthPixels, dm.heightPixels)
    }

    private fun armSessionOnce(
        resultCode: Int,
        resultData: Intent,
        autoStartRecording: Boolean,
        autoTimedSeconds: Int,
        takeScreenshotImmediately: Boolean
    ) {
        val micPermissionGranted = isMicPermissionGranted()
        val config = ScreenRecorderStateHolder.activeConfig.value
        hasActiveAudio = config.audioSource.recordAudio && micPermissionGranted

        val initialNotification = buildNotification(
            statusText = "Entire Screen Armed • 1x Tap: Rec • 2x Tap: SS • Hold: Multi-Shot",
            isRecording = false,
            isPaused = false
        )
        startForegroundSafely(initialNotification, includeMicType = micPermissionGranted)

        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Unable to acquire MediaProjection token.")

            projection.registerCallback(projectionCallback, null)
            mediaProjection = projection

            val (width, height) = resolveTargetVideoDimensions()
            val densityDpi = resources.displayMetrics.densityDpi

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            standbyImageReader = reader
            currentDisplayWidth = width
            currentDisplayHeight = height

            virtualDisplay = projection.createVirtualDisplay(
                "FloatRecEntireScreenDisplay",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            overlayViewManager.show()
            refreshForegroundTypesAndAudioCapabilities()

            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    isSessionArmed = true,
                    isSystemOverlayShowing = overlayViewManager.canShowSystemOverlay(),
                    status = RecorderStatus.STANDBY,
                    gestureIconState = GestureIconState.SINGLE_TAP_IDLE,
                    lastGestureLabel = "Entire Screen Armed • 1x Tap: Rec • 2x Tap: SS • Hold: Multi-Shot",
                    errorMessage = null
                )
            }

            when {
                takeScreenshotImmediately -> {
                    serviceScope.launch {
                        delay(250L)
                        captureDoubleTapScreenshot()
                    }
                }
                autoTimedSeconds > 0 -> startTimedAutoRecording(autoTimedSeconds)
                autoStartRecording -> {
                    activeShotIndex = 1
                    activeTotalShots = 1
                    activeMultiShotGapSeconds = 0
                    startClipFromWarmSession(
                        autoStopDurationMillis = 0L,
                        shotIndex = 1,
                        totalShots = 1
                    )
                }
            }
        } catch (e: Exception) {
            releaseAllSessionResources()
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    isSessionArmed = false,
                    status = RecorderStatus.STANDBY,
                    errorMessage = "Could not arm entire screen: ${e.localizedMessage ?: "Error"}"
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startClipFromWarmSession(
        autoStopDurationMillis: Long,
        shotIndex: Int,
        totalShots: Int
    ) {
        if (mediaProjection == null || virtualDisplay == null) {
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    isSessionArmed = false,
                    errorMessage = "Tap once to authorize entire screen capture."
                )
            }
            return
        }
        if (ScreenRecorderStateHolder.telemetry.value.status != RecorderStatus.STANDBY) return

        stopLiveAudioMonitorCheck()
        cancelMultiShotGapIfActive()

        val config = ScreenRecorderStateHolder.activeConfig.value
        val micPermissionGranted = isMicPermissionGranted()

        // Ensure foreground service includes MICROPHONE type whenever permission is granted
        val recNotification = buildNotification(
            statusText = "Starting Capture (${config.audioSource.label})...",
            isRecording = true,
            isPaused = false
        )
        runCatching {
            startForegroundSafely(recNotification, includeMicType = micPermissionGranted)
        }

        hasActiveAudio = config.audioSource.recordAudio && micPermissionGranted
        activeAutoStopTargetMillis = autoStopDurationMillis
        activeShotIndex = shotIndex
        activeTotalShots = totalShots

        val started = prepareAndAttachRecorderToWarmDisplay(shotIndex, totalShots)
        if (started) {
            accumulatedMillis = 0L
            lastResumeRealtime = SystemClock.elapsedRealtime()
            isPausedState = false
            val iconState = if (autoStopDurationMillis > 0L) {
                GestureIconState.LONG_PRESS_TIMED
            } else {
                GestureIconState.SINGLE_TAP_RECORDING
            }
            val gestureDesc = when {
                autoStopDurationMillis > 0L && totalShots > 1 ->
                    "Multi-Shot $shotIndex/$totalShots • ${autoStopDurationMillis / 1000L}s (${activeAudioStatusLabel})"
                autoStopDurationMillis > 0L ->
                    "Hold Auto-Rec ${autoStopDurationMillis / 1000L}s (${activeAudioStatusLabel})"
                else ->
                    "1x Tap Recording (${activeAudioStatusLabel}) • Tap to Stop"
            }

            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    status = RecorderStatus.RECORDING,
                    gestureIconState = iconState,
                    autoStopTargetMillis = autoStopDurationMillis,
                    isMultiShotGap = false,
                    currentShotIndex = shotIndex,
                    totalShots = totalShots,
                    gapRemainingMillis = 0L,
                    lastGestureLabel = gestureDesc,
                    countdownRemaining = 0,
                    elapsedMillis = 0L,
                    errorMessage = null
                )
            }
            startTelemetryTicker()
        }
    }

    private fun captureDoubleTapScreenshot() {
        if (mediaProjection == null || virtualDisplay == null) {
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    isSessionArmed = false,
                    errorMessage = "Authorize entire screen once to capture screenshots."
                )
            }
            return
        }

        serviceScope.launch {
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    gestureIconState = GestureIconState.DOUBLE_TAP_SCREENSHOT,
                    lastGestureLabel = "2x Tap Gesture • Capturing Entire Screen SS..."
                )
            }

            val activeRecorderSurface = activeMuxSession?.videoInputSurface ?: fallbackMediaRecorder?.surface
            val wasRecording = activeRecorderSurface != null

            if (wasRecording && standbyImageReader != null) {
                runCatching {
                    virtualDisplay?.surface = standbyImageReader?.surface
                }
                delay(85L)
            } else {
                delay(55L)
            }

            val image = runCatching { standbyImageReader?.acquireLatestImage() }.getOrNull()
            if (wasRecording && activeRecorderSurface != null) {
                runCatching {
                    virtualDisplay?.surface = activeRecorderSurface
                }
            }

            val (w, h) = resolveTargetVideoDimensions()
            val bitmap = if (image != null) {
                imageToBitmap(image, w, h)
            } else {
                createFallbackScreenBitmap(w, h)
            }

            val savedClip = withContext(Dispatchers.IO) {
                saveScreenshotBitmap(bitmap, w, h)
            }

            if (savedClip != null) {
                repository.addRecordedClip(savedClip)
                ScreenRecorderStateHolder.emitSavedClip(savedClip)
            }

            delay(800L)
            val currentStatus = ScreenRecorderStateHolder.telemetry.value.status
            val restoredIcon = when {
                currentStatus == RecorderStatus.RECORDING && activeAutoStopTargetMillis > 0L ->
                    GestureIconState.LONG_PRESS_TIMED
                currentStatus == RecorderStatus.RECORDING ->
                    GestureIconState.SINGLE_TAP_RECORDING
                else -> GestureIconState.SINGLE_TAP_IDLE
            }
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    gestureIconState = restoredIcon,
                    lastGestureLabel = "2x Tap Gesture • Screenshot Saved to Subtab!"
                )
            }
        }
    }

    private fun imageToBitmap(image: Image, fallbackW: Int, fallbackH: Int): Bitmap {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            paddedBitmap.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
        } catch (_: Exception) {
            createFallbackScreenBitmap(fallbackW, fallbackH)
        } finally {
            runCatching { image.close() }
        }
    }

    private fun createFallbackScreenBitmap(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(
            width.coerceAtLeast(720),
            height.coerceAtLeast(720),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#0C0D12"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            textSize = 42f
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        canvas.drawText("FloatRec Screenshot • $stamp", 64f, height / 2f, paint)
        return bmp
    }

    private fun saveScreenshotBitmap(bitmap: Bitmap, width: Int, height: Int): RecordedClip? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val title = "FloatRec_SS_$timestamp"
            val fileName = "$title.png"

            var savedUri: Uri? = null
            var savedFile: File? = null
            var sizeBytes = 0L

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/FloatRec"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val collection =
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = contentResolver.insert(collection, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, doneValues, null, null)
                    savedUri = uri
                    runCatching {
                        contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                            if (afd.length > 0L) sizeBytes = afd.length
                        }
                    }
                }
            }

            if (savedUri == null) {
                val picturesDir = File(
                    getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir,
                    "FloatRec"
                )
                if (!picturesDir.exists()) picturesDir.mkdirs()
                val file = File(picturesDir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                savedFile = file
                sizeBytes = file.length()
                savedUri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    file
                )
            }

            val config = ScreenRecorderStateHolder.activeConfig.value
            RecordedClip(
                id = System.currentTimeMillis().toString(),
                title = title,
                uriString = savedUri.toString(),
                filePath = savedFile?.absolutePath,
                durationMillis = 0L,
                sizeBytes = sizeBytes.coerceAtLeast(32_000L),
                resolutionLabel = "SS • ${config.resolution.shortBadge}",
                fps = 0,
                bitrateMbps = 0,
                createdAtMillis = System.currentTimeMillis(),
                hasAudio = false,
                audioSourceLabel = "No Audio",
                isScreenshot = true
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun prepareAndAttachRecorderToWarmDisplay(
        shotIndex: Int,
        totalShots: Int
    ): Boolean {
        return try {
            val config = ScreenRecorderStateHolder.activeConfig.value
            val (width, height) = resolveTargetVideoDimensions()
            val densityDpi = resources.displayMetrics.densityDpi

            // Ensure standbyImageReader matches current target dimensions without detaching VirtualDisplay
            if (width != currentDisplayWidth || height != currentDisplayHeight) {
                val oldReader = standbyImageReader
                val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                standbyImageReader = newReader
                virtualDisplay?.resize(width, height, densityDpi)
                virtualDisplay?.surface = newReader.surface
                runCatching { oldReader?.close() }
                currentDisplayWidth = width
                currentDisplayHeight = height
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            currentClipTitle = if (totalShots > 1) {
                "FloatRec_${timestamp}_Shot${shotIndex}of$totalShots"
            } else {
                "FloatRec_$timestamp"
            }

            val fd = prepareOutputFileDescriptor("$currentClipTitle.mp4")
                ?: throw IllegalStateException("Could not create MP4 file.")

            // Try Primary Dual-Stream (Internal + Mic / Internal / Mic / No Audio) MediaCodec+MediaMuxer Engine
            val muxSession = MixedAvCaptureSession(
                width = width,
                height = height,
                config = config,
                hasAudioPermission = isMicPermissionGranted(),
                mediaProjection = mediaProjection,
                outputFileDescriptor = fd.fileDescriptor
            )

            if (muxSession.start()) {
                activeMuxSession = muxSession
                hasActiveAudio = muxSession.isAudioTrackEnabled
                activeAudioStatusLabel = muxSession.activeAudioDescription
                virtualDisplay?.resize(width, height, densityDpi)
                virtualDisplay?.surface = muxSession.videoInputSurface
                return true
            }

            // Fallback to MediaRecorder if codec setup failed on exotic hardware
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            val useMicFallback = config.audioSource.recordAudio && isMicPermissionGranted()
            if (useMicFallback) {
                val source = when (config.audioSource) {
                    AudioSourceMode.MICROPHONE -> MediaRecorder.AudioSource.MIC
                    AudioSourceMode.INTERNAL_PLUS_MIC -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    AudioSourceMode.INTERNAL_AUDIO -> MediaRecorder.AudioSource.DEFAULT
                    AudioSourceMode.NO_AUDIO -> MediaRecorder.AudioSource.MIC
                }
                runCatching {
                    recorder.setAudioSource(source)
                }.onFailure {
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                }
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(fd.fileDescriptor)
            recorder.setVideoSize(width, height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoEncodingBitRate(config.bitrateBps)
            recorder.setVideoFrameRate(config.frameRate.fps)

            if (useMicFallback) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioChannels(1)
                recorder.setAudioEncodingBitRate(128_000)
                recorder.setAudioSamplingRate(44_100)
            }

            recorder.prepare()
            virtualDisplay?.surface = recorder.surface
            recorder.start()

            fallbackMediaRecorder = recorder
            hasActiveAudio = useMicFallback
            activeAudioStatusLabel = if (useMicFallback) config.audioSource.label else "No Audio"
            true
        } catch (e: Exception) {
            releaseClipRecorderOnly()
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    status = RecorderStatus.STANDBY,
                    errorMessage = "Recording error: ${e.localizedMessage ?: "Encoder error"}"
                )
            }
            false
        }
    }

    private fun prepareOutputFileDescriptor(fileName: String): ParcelFileDescriptor? {
        outputPfd?.close()
        outputPfd = null
        outputUri = null
        fallbackFile = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/FloatRec"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values)
            if (uri != null) {
                outputUri = uri
                outputPfd = contentResolver.openFileDescriptor(uri, "rw")
                if (outputPfd != null) return outputPfd
            }
        }

        val moviesDir = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir,
            "FloatRec"
        )
        if (!moviesDir.exists()) {
            moviesDir.mkdirs()
        }
        val file = File(moviesDir, fileName)
        fallbackFile = file
        outputUri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            file
        )
        outputPfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE
        )
        return outputPfd
    }

    private fun startTelemetryTicker() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            var lastNotificationSecond = -1L
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val totalElapsed = if (isPausedState) {
                    accumulatedMillis
                } else {
                    accumulatedMillis + (now - lastResumeRealtime)
                }

                if (activeAutoStopTargetMillis > 0L && totalElapsed >= activeAutoStopTargetMillis) {
                    val hasMoreShots = activeShotIndex < activeTotalShots
                    stopCurrentClipKeepSessionWarm(isPartOfMultiShotBurst = hasMoreShots)
                    break
                }

                val config = ScreenRecorderStateHolder.activeConfig.value
                val estimatedBytes = ((totalElapsed / 1000.0) * (config.bitrateBps / 8.0)).toLong()
                val muxPeak = activeMuxSession?.latestNormalizedPeak ?: 0f
                val fallbackAmp = if (!isPausedState && hasActiveAudio && fallbackMediaRecorder != null) {
                    runCatching { (fallbackMediaRecorder?.maxAmplitude ?: 0) / 16384f }.getOrDefault(0f)
                } else {
                    0f
                }
                val normalizedAudio = when {
                    isPausedState || !hasActiveAudio -> 0f
                    muxPeak > 0.01f -> muxPeak.coerceIn(0.06f, 1f)
                    fallbackAmp > 0.01f -> fallbackAmp.coerceIn(0.06f, 1f)
                    else -> (0.09f + 0.04f * sin(totalElapsed / 190.0).toFloat()).coerceIn(0.05f, 0.25f)
                }

                ScreenRecorderStateHolder.updateTelemetry {
                    it.copy(
                        isSessionArmed = true,
                        status = if (isPausedState) RecorderStatus.PAUSED else RecorderStatus.RECORDING,
                        autoStopTargetMillis = activeAutoStopTargetMillis,
                        currentShotIndex = activeShotIndex,
                        totalShots = activeTotalShots,
                        elapsedMillis = totalElapsed,
                        estimatedSizeBytes = estimatedBytes,
                        audioPeakLevel = normalizedAudio
                    )
                }

                val currentSecond = totalElapsed / 1000L
                if (currentSecond != lastNotificationSecond) {
                    lastNotificationSecond = currentSecond
                    val formatted = formatFloatingPointTimecode(totalElapsed)
                    val shotPrefix = if (activeTotalShots > 1) {
                        "SHOT $activeShotIndex/$activeTotalShots • "
                    } else {
                        ""
                    }
                    val notification = buildNotification(
                        statusText = if (isPausedState) {
                            "${shotPrefix}PAUSED • $formatted"
                        } else {
                            "${shotPrefix}REC • $formatted ($activeAudioStatusLabel)"
                        },
                        isRecording = true,
                        isPaused = isPausedState
                    )
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, notification)
                }

                delay(50L)
            }
        }
    }

    private fun pauseCaptureSession() {
        if (isPausedState || (activeMuxSession == null && fallbackMediaRecorder == null)) return
        runCatching {
            activeMuxSession?.setPaused(true)
            fallbackMediaRecorder?.pause()
            accumulatedMillis += (SystemClock.elapsedRealtime() - lastResumeRealtime)
            isPausedState = true
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(status = RecorderStatus.PAUSED, audioPeakLevel = 0f)
            }
        }
    }

    private fun resumeCaptureSession() {
        if (!isPausedState || (activeMuxSession == null && fallbackMediaRecorder == null)) return
        runCatching {
            activeMuxSession?.setPaused(false)
            fallbackMediaRecorder?.resume()
            lastResumeRealtime = SystemClock.elapsedRealtime()
            isPausedState = false
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(status = RecorderStatus.RECORDING)
            }
        }
    }

    private fun stopCurrentClipKeepSessionWarm(isPartOfMultiShotBurst: Boolean) {
        timerJob?.cancel()
        timerJob = null

        if (activeMuxSession == null && fallbackMediaRecorder == null) return

        val finalDurationMillis = if (isPausedState) {
            accumulatedMillis
        } else {
            accumulatedMillis + (SystemClock.elapsedRealtime() - lastResumeRealtime)
        }.coerceAtLeast(500L)

        val config = ScreenRecorderStateHolder.activeConfig.value
        val savedUri = outputUri
        val savedFallbackFile = fallbackFile
        val title = currentClipTitle.ifEmpty { "FloatRec_${System.currentTimeMillis()}" }
        val completedShotDurationMillis = activeAutoStopTargetMillis
        val completedShotIndex = activeShotIndex
        val totalShotsInBurst = activeTotalShots
        val gapSecondsBetweenShots = activeMultiShotGapSeconds
        val clipHadAudio = hasActiveAudio
        val clipAudioLabel = activeAudioStatusLabel

        activeAutoStopTargetMillis = 0L

        // Crucial: Switch VirtualDisplay back to standbyImageReader BEFORE stopping encoder
        // so VirtualDisplay never holds an abandoned surface and MediaProjection stays warm!
        runCatching {
            if (standbyImageReader == null) {
                val (w, h) = resolveTargetVideoDimensions()
                standbyImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            }
            virtualDisplay?.surface = standbyImageReader?.surface
        }

        var stopSucceeded = false
        try {
            activeMuxSession?.let { session ->
                session.stopAndRelease()
                stopSucceeded = true
            }
            activeMuxSession = null

            fallbackMediaRecorder?.let { recorder ->
                recorder.stop()
                recorder.reset()
                stopSucceeded = true
            }
        } catch (_: Exception) {
            stopSucceeded = true
        } finally {
            releaseClipRecorderOnly()
        }

        serviceScope.launch {
            if (stopSucceeded && savedUri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && savedFallbackFile == null) {
                    runCatching {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        contentResolver.update(savedUri, values, null, null)
                    }
                }

                val sizeBytes = resolveSavedFileSize(
                    savedUri,
                    savedFallbackFile,
                    finalDurationMillis,
                    config.bitrateBps
                )
                val clip = RecordedClip(
                    id = System.currentTimeMillis().toString(),
                    title = title,
                    uriString = savedUri.toString(),
                    filePath = savedFallbackFile?.absolutePath,
                    durationMillis = finalDurationMillis,
                    sizeBytes = sizeBytes,
                    resolutionLabel = config.resolution.shortBadge,
                    fps = config.frameRate.fps,
                    bitrateMbps = config.bitrateMbps.roundToInt(),
                    createdAtMillis = System.currentTimeMillis(),
                    hasAudio = clipHadAudio,
                    audioSourceLabel = if (clipHadAudio) clipAudioLabel else "No Audio",
                    isScreenshot = false
                )
                repository.addRecordedClip(clip)
                ScreenRecorderStateHolder.emitSavedClip(clip)
            }
        }

        if (isPartOfMultiShotBurst && completedShotIndex < totalShotsInBurst) {
            val nextShotIndex = completedShotIndex + 1
            val gapMillis = (gapSecondsBetweenShots.coerceAtLeast(0) * 1000L)
            scheduleNextMultiShotAfterGap(
                nextShotIndex = nextShotIndex,
                totalShots = totalShotsInBurst,
                shotDurationMillis = completedShotDurationMillis.coerceAtLeast(3000L),
                gapMillis = gapMillis
            )
        } else {
            activeShotIndex = 1
            activeTotalShots = 1
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(
                    isSessionArmed = mediaProjection != null,
                    status = RecorderStatus.STANDBY,
                    gestureIconState = GestureIconState.SINGLE_TAP_IDLE,
                    autoStopTargetMillis = 0L,
                    isMultiShotGap = false,
                    currentShotIndex = 1,
                    totalShots = 1,
                    gapRemainingMillis = 0L,
                    lastGestureLabel = "Saved Clip • Entire Screen Ready (No Re-Prompt)",
                    elapsedMillis = 0L,
                    estimatedSizeBytes = 0L,
                    audioPeakLevel = 0f
                )
            }

            val standbyNotification = buildNotification(
                statusText = "Saved clip • Entire Screen Ready for Next Capture",
                isRecording = false,
                isPaused = false
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, standbyNotification)
        }
    }

    private fun scheduleNextMultiShotAfterGap(
        nextShotIndex: Int,
        totalShots: Int,
        shotDurationMillis: Long,
        gapMillis: Long
    ) {
        cancelMultiShotGapIfActive()
        multiShotGapJob = serviceScope.launch {
            if (gapMillis > 0L) {
                val startWait = SystemClock.elapsedRealtime()
                while (isActive) {
                    val elapsedGap = SystemClock.elapsedRealtime() - startWait
                    val remaining = (gapMillis - elapsedGap).coerceAtLeast(0L)
                    if (remaining <= 0L) break

                    val remainingSecStr = String.format(Locale.US, "%.1fs", remaining / 1000f)
                    ScreenRecorderStateHolder.updateTelemetry {
                        it.copy(
                            isSessionArmed = true,
                            status = RecorderStatus.STANDBY,
                            gestureIconState = GestureIconState.LONG_PRESS_TIMED,
                            isMultiShotGap = true,
                            currentShotIndex = nextShotIndex,
                            totalShots = totalShots,
                            gapRemainingMillis = remaining,
                            lastGestureLabel = "Multi-Shot Gap • Shot $nextShotIndex/$totalShots starts in $remainingSecStr (Tap to Cancel)",
                            elapsedMillis = 0L,
                            audioPeakLevel = 0f
                        )
                    }
                    delay(80L)
                }
            } else {
                delay(200L)
            }

            if (isActive && mediaProjection != null) {
                ScreenRecorderStateHolder.updateTelemetry {
                    it.copy(isMultiShotGap = false, gapRemainingMillis = 0L)
                }
                startClipFromWarmSession(
                    autoStopDurationMillis = shotDurationMillis,
                    shotIndex = nextShotIndex,
                    totalShots = totalShots
                )
            }
        }
    }

    private fun closeEntireSession(closeAppToo: Boolean) {
        cancelMultiShotGapIfActive()
        stopLiveAudioMonitorCheck()
        if (activeMuxSession != null || fallbackMediaRecorder != null) {
            stopCurrentClipKeepSessionWarm(isPartOfMultiShotBurst = false)
        }
        timerJob?.cancel()
        timerJob = null
        overlayViewManager.hide()
        releaseAllSessionResources()
        ScreenRecorderStateHolder.updateTelemetry {
            LiveRecordingTelemetry(
                isSessionArmed = false,
                isSystemOverlayShowing = false,
                status = RecorderStatus.STANDBY
            )
        }
        if (closeAppToo) {
            ScreenRecorderStateHolder.emitCloseApp()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resolveSavedFileSize(
        uri: Uri,
        file: File?,
        durationMillis: Long,
        bps: Int
    ): Long {
        if (file != null && file.exists() && file.length() > 0L) {
            return file.length()
        }
        runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0L) return afd.length
            }
        }
        return ((durationMillis / 1000.0) * (bps / 8.0)).toLong().coerceAtLeast(64_000L)
    }

    private fun releaseClipRecorderOnly() {
        runCatching { activeMuxSession?.stopAndRelease() }
        activeMuxSession = null

        runCatching { fallbackMediaRecorder?.release() }
        fallbackMediaRecorder = null

        runCatching { outputPfd?.close() }
        outputPfd = null
    }

    private fun releaseAllSessionResources() {
        releaseClipRecorderOnly()

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { standbyImageReader?.close() }
        standbyImageReader = null

        runCatching {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        }
        mediaProjection = null
    }

    private fun startForegroundSafely(notification: Notification, includeMicType: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && includeMicType) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        statusText: String,
        isRecording: Boolean,
        isPaused: Boolean
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPendingIntent)

        if (isRecording) {
            val stopIntent = Intent(this, ScreenRecorderService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                102,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_rew,
                "Stop Recording",
                stopPendingIntent
            )
        } else {
            val recIntent = Intent(this, ScreenRecorderService::class.java).apply {
                action = ACTION_TOGGLE_RECORD
            }
            val recPendingIntent = PendingIntent.getService(
                this,
                103,
                recIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val ssIntent = Intent(this, ScreenRecorderService::class.java).apply {
                action = ACTION_TAKE_SCREENSHOT
            }
            val ssPendingIntent = PendingIntent.getService(
                this,
                105,
                ssIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                android.R.drawable.presence_video_online,
                "Record",
                recPendingIntent
            )
            builder.addAction(
                android.R.drawable.ic_menu_camera,
                "Screenshot",
                ssPendingIntent
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        cancelMultiShotGapIfActive()
        stopLiveAudioMonitorCheck()
        timerJob?.cancel()
        overlayViewManager.hide()
        releaseAllSessionResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "floatrec_capture_channel"
        const val NOTIFICATION_ID = 4092

        const val ACTION_ARM_OR_START = "com.example.service.action.ARM_OR_START"
        const val ACTION_REFRESH_AUDIO_CONFIG = "com.example.service.action.REFRESH_AUDIO_CONFIG"
        const val ACTION_TOGGLE_AUDIO_CHECK = "com.example.service.action.TOGGLE_AUDIO_CHECK"
        const val ACTION_TOGGLE_RECORD = "com.example.service.action.TOGGLE_RECORD"
        const val ACTION_RECORD_FROM_WARM = "com.example.service.action.RECORD_FROM_WARM"
        const val ACTION_AUTO_RECORD_TIMED = "com.example.service.action.AUTO_RECORD_TIMED"
        const val ACTION_TAKE_SCREENSHOT = "com.example.service.action.TAKE_SCREENSHOT"
        const val ACTION_PAUSE = "com.example.service.action.PAUSE_CAPTURE"
        const val ACTION_RESUME = "com.example.service.action.RESUME_CAPTURE"
        const val ACTION_STOP = "com.example.service.action.STOP_CAPTURE"
        const val ACTION_CLOSE_SESSION = "com.example.service.action.CLOSE_SESSION"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_AUTO_START_RECORDING = "extra_auto_start_recording"
        const val EXTRA_AUTO_TIMED_SECONDS = "extra_auto_timed_seconds"
        const val EXTRA_TAKE_SCREENSHOT = "extra_take_screenshot"
    }
}

@SuppressLint("MissingPermission")
private fun createReliableMicAudioRecord(sampleRate: Int, bufferSizeBytes: Int): AudioRecord? {
    val sourcesToTry = intArrayOf(
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.DEFAULT
    )
    for (src in sourcesToTry) {
        val record = runCatching {
            AudioRecord(
                src,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes
            )
        }.getOrNull()
        if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
            return record
        }
        runCatching { record?.release() }
    }
    return null
}

/**
 * Real-time H.264 Video + AAC Audio Muxer Engine supporting:
 * 1. INTERNAL_AUDIO (AudioPlaybackCaptureConfiguration on Android 10+)
 * 2. MICROPHONE (AudioRecord MIC with voice clarity boost)
 * 3. INTERNAL_PLUS_MIC (Real-time 16-bit PCM mixer combining Internal Playback + Microphone!)
 * 4. NO_AUDIO (Silent video-only MP4)
 */
@SuppressLint("MissingPermission")
private class MixedAvCaptureSession(
    private val width: Int,
    private val height: Int,
    private val config: RecorderConfig,
    private val hasAudioPermission: Boolean,
    private val mediaProjection: MediaProjection?,
    private val outputFileDescriptor: java.io.FileDescriptor
) {
    var videoInputSurface: Surface? = null
        private set

    var isAudioTrackEnabled: Boolean = false
        private set

    var activeAudioDescription: String = "No Audio"
        private set

    @Volatile
    var latestNormalizedPeak: Float = 0f
        private set

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null

    private var internalAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)

    private var workerThread: Thread? = null

    fun setPaused(paused: Boolean) {
        isPaused.set(paused)
    }

    fun start(): Boolean {
        return try {
            val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 50_000L)
            }

            val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = vCodec.createInputSurface()
            vCodec.start()
            videoEncoder = vCodec
            videoInputSurface = surface

            val sampleRate = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val wantAudio = config.audioSource != AudioSourceMode.NO_AUDIO && hasAudioPermission
            if (wantAudio) {
                // 1. Setup Internal Audio Record if requested
                val activeProjection = mediaProjection
                if ((config.audioSource == AudioSourceMode.INTERNAL_AUDIO ||
                        config.audioSource == AudioSourceMode.INTERNAL_PLUS_MIC) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    activeProjection != null
                ) {
                    runCatching {
                        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build()
                        val rec = AudioRecord.Builder()
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .build()
                            )
                            .setBufferSizeInBytes(minBuf * 2)
                            .setAudioPlaybackCaptureConfig(playbackConfig)
                            .build()
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            rec.startRecording()
                            internalAudioRecord = rec
                        } else {
                            rec.release()
                        }
                    }
                }

                // 2. Setup Microphone Audio Record if requested (or as fallback if Internal Audio unavailable)
                if (config.audioSource == AudioSourceMode.MICROPHONE ||
                    config.audioSource == AudioSourceMode.INTERNAL_PLUS_MIC ||
                    (config.audioSource == AudioSourceMode.INTERNAL_AUDIO && internalAudioRecord == null)
                ) {
                    val rec = createReliableMicAudioRecord(sampleRate, minBuf * 2)
                    if (rec != null) {
                        runCatching {
                            rec.startRecording()
                            micAudioRecord = rec
                        }
                    }
                }

                if (internalAudioRecord != null || micAudioRecord != null) {
                    val aFormat = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        sampleRate,
                        1
                    ).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                        setInteger(
                            MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC
                        )
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    }
                    val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    aCodec.start()
                    audioEncoder = aCodec
                    isAudioTrackEnabled = true

                    activeAudioDescription = when {
                        internalAudioRecord != null && micAudioRecord != null -> "Internal + Mic"
                        internalAudioRecord != null -> "Internal Audio"
                        else -> "Microphone"
                    }
                }
            }

            if (!isAudioTrackEnabled) {
                activeAudioDescription = "No Audio"
            }

            mediaMuxer = MediaMuxer(outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isRunning.set(true)

            workerThread = Thread({ runEncodeAndMuxLoop(sampleRate) }, "FloatRecAvMuxThread").apply {
                priority = Thread.MAX_PRIORITY - 1
                start()
            }
            true
        } catch (_: Exception) {
            stopAndRelease()
            false
        }
    }

    private fun runEncodeAndMuxLoop(sampleRate: Int) {
        val vCodec = videoEncoder ?: return
        val aCodec = audioEncoder
        val muxer = mediaMuxer ?: return

        val vBufferInfo = MediaCodec.BufferInfo()
        val aBufferInfo = MediaCodec.BufferInfo()

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false

        val pcmChunkSamples = 1024
        val internalSamples = ShortArray(pcmChunkSamples)
        val micSamples = ShortArray(pcmChunkSamples)
        val mixedBytes = ByteArray(pcmChunkSamples * 2)

        val startNanoTime = System.nanoTime()
        var totalPausedNanos = 0L
        var pauseStartNano = 0L
        var lastVideoPtsUs = 0L
        var lastAudioPtsUs = 0L
        var totalAudioSamplesSubmitted = 0L

        while (isRunning.get()) {
            if (isPaused.get()) {
                if (pauseStartNano == 0L) {
                    pauseStartNano = System.nanoTime()
                }
                runCatching { Thread.sleep(25L) }
                continue
            } else if (pauseStartNano != 0L) {
                totalPausedNanos += (System.nanoTime() - pauseStartNano)
                pauseStartNano = 0L
            }

            // 1. Read & Mix PCM Audio and feed AAC Encoder
            if (aCodec != null && isAudioTrackEnabled) {
                val inIndex = runCatching { aCodec.dequeueInputBuffer(2_000L) }.getOrDefault(-1)
                if (inIndex >= 0) {
                    val inBuf = aCodec.getInputBuffer(inIndex)
                    if (inBuf != null) {
                        inBuf.clear()
                        var readInternal = 0
                        var readMic = 0

                        internalAudioRecord?.let { rec ->
                            val r = rec.read(internalSamples, 0, pcmChunkSamples, AudioRecord.READ_NON_BLOCKING)
                            if (r > 0) readInternal = r
                        }
                        micAudioRecord?.let { rec ->
                            val r = rec.read(micSamples, 0, pcmChunkSamples)
                            if (r > 0) readMic = r
                        }

                        val samplesCount = maxOf(readInternal, readMic, pcmChunkSamples)
                        var maxAmp = 0

                        for (i in 0 until samplesCount) {
                            val intSample = if (i < readInternal) internalSamples[i].toInt() else 0
                            // Apply 1.25x voice clarity gain on microphone
                            val micSample = if (i < readMic) (micSamples[i].toInt() * 5) / 4 else 0
                            val mixed = (intSample + micSample)
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            val absVal = abs(mixed)
                            if (absVal > maxAmp) maxAmp = absVal

                            mixedBytes[i * 2] = (mixed and 0xFF).toByte()
                            mixedBytes[i * 2 + 1] = ((mixed shr 8) and 0xFF).toByte()
                        }

                        latestNormalizedPeak = (maxAmp / 14000f).coerceIn(0.05f, 1.0f)

                        val byteCount = samplesCount * 2
                        inBuf.put(mixedBytes, 0, byteCount)
                        val audioPtsUs = (totalAudioSamplesSubmitted * 1_000_000L) / sampleRate
                        totalAudioSamplesSubmitted += samplesCount
                        runCatching {
                            aCodec.queueInputBuffer(inIndex, 0, byteCount, audioPtsUs, 0)
                        }
                    }
                }
            }

            // 2. Drain Video Encoder
            while (true) {
                val outIndex = runCatching { vCodec.dequeueOutputBuffer(vBufferInfo, 2_000L) }
                    .getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (videoTrackIndex == -1) {
                        videoTrackIndex = muxer.addTrack(vCodec.outputFormat)
                        if (!muxerStarted && (videoTrackIndex != -1 && (!isAudioTrackEnabled || audioTrackIndex != -1))) {
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                } else if (outIndex >= 0) {
                    val encodedData = vCodec.getOutputBuffer(outIndex)
                    if (vBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        vBufferInfo.size = 0
                    }
                    if (vBufferInfo.size > 0 && muxerStarted && encodedData != null) {
                        val realPtsUs = ((System.nanoTime() - startNanoTime - totalPausedNanos) / 1000L)
                            .coerceAtLeast(lastVideoPtsUs + 100L)
                        lastVideoPtsUs = realPtsUs
                        vBufferInfo.presentationTimeUs = realPtsUs
                        encodedData.position(vBufferInfo.offset)
                        encodedData.limit(vBufferInfo.offset + vBufferInfo.size)
                        runCatching {
                            muxer.writeSampleData(videoTrackIndex, encodedData, vBufferInfo)
                        }
                    }
                    runCatching { vCodec.releaseOutputBuffer(outIndex, false) }
                    if (vBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }

            // 3. Drain Audio Encoder
            if (aCodec != null && isAudioTrackEnabled) {
                while (true) {
                    val outIndex = runCatching { aCodec.dequeueOutputBuffer(aBufferInfo, 1_000L) }
                        .getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (audioTrackIndex == -1) {
                            audioTrackIndex = muxer.addTrack(aCodec.outputFormat)
                            if (!muxerStarted && videoTrackIndex != -1 && audioTrackIndex != -1) {
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                    } else if (outIndex >= 0) {
                        val encodedData = aCodec.getOutputBuffer(outIndex)
                        if (aBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            aBufferInfo.size = 0
                        }
                        if (aBufferInfo.size > 0 && muxerStarted && encodedData != null) {
                            val ptsUs = aBufferInfo.presentationTimeUs.coerceAtLeast(lastAudioPtsUs + 50L)
                            lastAudioPtsUs = ptsUs
                            aBufferInfo.presentationTimeUs = ptsUs
                            encodedData.position(aBufferInfo.offset)
                            encodedData.limit(aBufferInfo.offset + aBufferInfo.size)
                            runCatching {
                                muxer.writeSampleData(audioTrackIndex, encodedData, aBufferInfo)
                            }
                        }
                        runCatching { aCodec.releaseOutputBuffer(outIndex, false) }
                        if (aBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            } else if (!isAudioTrackEnabled) {
                // Sleep briefly when video-only so thread doesn't busy-spin
                runCatching { Thread.sleep(10L) }
            }
        }
    }

    fun stopAndRelease() {
        if (!isReleased.compareAndSet(false, true)) return
        isRunning.set(false)
        isPaused.set(false)

        runCatching { workerThread?.join(800L) }
        workerThread = null

        runCatching { internalAudioRecord?.stop() }
        runCatching { internalAudioRecord?.release() }
        internalAudioRecord = null

        runCatching { micAudioRecord?.stop() }
        runCatching { micAudioRecord?.release() }
        micAudioRecord = null

        runCatching { videoEncoder?.signalEndOfInputStream() }
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null

        runCatching { videoInputSurface?.release() }
        videoInputSurface = null

        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        audioEncoder = null

        runCatching { mediaMuxer?.stop() }
        runCatching { mediaMuxer?.release() }
        mediaMuxer = null
    }
}
