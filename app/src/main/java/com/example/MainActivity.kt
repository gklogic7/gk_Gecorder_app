package com.example

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AudioSourceMode
import com.example.model.CaptureFilterTab
import com.example.model.GestureIconState
import com.example.model.RecorderConfig
import com.example.model.RecorderStatus
import com.example.model.RecorderSubTab
import com.example.model.ResolutionPreset
import com.example.model.SavedRecorderPreset
import com.example.model.formatFileSize
import com.example.model.formatFloatingPointTimecode
import com.example.service.ScreenRecorderStateHolder
import com.example.ui.components.ClipPlaybackDialog
import com.example.ui.components.DraggableFloatingHudPill
import com.example.ui.components.PipCompactHudView
import com.example.ui.components.RecordedClipCard
import com.example.ui.components.RenameClipDialog
import com.example.ui.components.shareRecordedClip
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.AnodizedSlateSurface
import com.example.ui.theme.CrispStudioWhite
import com.example.ui.theme.ElevatedHudSurface
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.GlassBorderSubtle
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MutedAluminum
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.PrecisionCyan
import com.example.ui.theme.TallyRecordingRed
import com.example.viewmodel.RecorderUiState
import com.example.viewmodel.RecorderViewModel
import java.util.Locale

private const val PENDING_ACTION_ARM_ONLY = 0
private const val PENDING_ACTION_SINGLE_TAP_REC = 1
private const val PENDING_ACTION_DOUBLE_TAP_SS = 2
private const val PENDING_ACTION_LONG_PRESS_TIMED = 3

class MainActivity : ComponentActivity() {

    private val viewModel: RecorderViewModel by viewModels()
    private var isInPipModeState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val snackbarHostState = remember { SnackbarHostState() }

                var pendingGestureAction by rememberSaveable { mutableIntStateOf(PENDING_ACTION_ARM_ONLY) }
                var pendingAudioSource by remember { mutableStateOf<AudioSourceMode?>(null) }
                var hasAutoArmedOnStartup by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    ScreenRecorderStateHolder.closeAppEvents.collect {
                        finishAndRemoveTask()
                    }
                }

                // Single One-Time Entire-Screen Capture Authorization Launcher
                // Uses MediaProjectionConfig.createConfigForDefaultDisplay() so Android
                // ALWAYS captures the entire screen without asking which app/screen to record.
                val wholeScreenProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val data = result.data
                    if (result.resultCode == Activity.RESULT_OK && data != null) {
                        val action = pendingGestureAction
                        pendingGestureAction = PENDING_ACTION_ARM_ONLY
                        viewModel.armSessionWithProjection(
                            resultCode = result.resultCode,
                            data = data,
                            autoStartRecording = (action == PENDING_ACTION_SINGLE_TAP_REC),
                            autoTimedSeconds = if (action == PENDING_ACTION_LONG_PRESS_TIMED) {
                                uiState.config.autoRecordDurationSeconds
                            } else {
                                0
                            },
                            takeScreenshotImmediately = (action == PENDING_ACTION_DOUBLE_TAP_SS)
                        )
                    } else {
                        pendingGestureAction = PENDING_ACTION_ARM_ONLY
                    }
                }

                // Combined runtime permission launcher (RECORD_AUDIO + POST_NOTIFICATIONS)
                // that chains directly into the one-time Entire Screen session arming
                val runtimePermissionsBeforeArmLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    viewModel.refreshAudioServiceConfig()
                    if (!uiState.telemetry.isSessionArmed) {
                        wholeScreenProjectionLauncher.launch(
                            createWholeScreenCaptureIntent(context)
                        )
                    } else {
                        val action = pendingGestureAction
                        pendingGestureAction = PENDING_ACTION_ARM_ONLY
                        when (action) {
                            PENDING_ACTION_SINGLE_TAP_REC -> viewModel.toggleSingleTapRecordOrStop()
                            PENDING_ACTION_DOUBLE_TAP_SS -> viewModel.triggerDoubleTapScreenshot()
                            PENDING_ACTION_LONG_PRESS_TIMED -> viewModel.startLongPressTimedAutoRecord()
                        }
                    }
                }

                val micPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) {
                    pendingAudioSource?.let { mode ->
                        viewModel.setAudioSource(mode)
                        pendingAudioSource = null
                    }
                    viewModel.refreshAudioServiceConfig()
                }

                val ensureWholeScreenArmedAndRun: (Int) -> Unit = { gestureAction ->
                    val needsMicPerm = uiState.config.audioSource.recordAudio &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED

                    val needsNotifPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED

                    if (needsMicPerm || needsNotifPerm) {
                        pendingGestureAction = gestureAction
                        val perms = buildList {
                            if (needsMicPerm) add(Manifest.permission.RECORD_AUDIO)
                            if (needsNotifPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        runtimePermissionsBeforeArmLauncher.launch(perms)
                    } else if (uiState.telemetry.isSessionArmed) {
                        when (gestureAction) {
                            PENDING_ACTION_SINGLE_TAP_REC -> viewModel.toggleSingleTapRecordOrStop()
                            PENDING_ACTION_DOUBLE_TAP_SS -> viewModel.triggerDoubleTapScreenshot()
                            PENDING_ACTION_LONG_PRESS_TIMED -> viewModel.startLongPressTimedAutoRecord()
                        }
                    } else {
                        pendingGestureAction = gestureAction
                        wholeScreenProjectionLauncher.launch(
                            createWholeScreenCaptureIntent(context)
                        )
                    }
                }

                // Auto-arm once when opening app so user authorizes Entire Screen once
                // and is never prompted again during subsequent recordings/screenshots
                LaunchedEffect(uiState.telemetry.isSessionArmed) {
                    if (!uiState.telemetry.isSessionArmed && !hasAutoArmedOnStartup) {
                        hasAutoArmedOnStartup = true
                        ensureWholeScreenArmedAndRun(PENDING_ACTION_ARM_ONLY)
                    }
                }

                val handleSelectAudioSource: (AudioSourceMode) -> Unit = { mode ->
                    if (mode.recordAudio &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingAudioSource = mode
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.setAudioSource(mode)
                    }
                }

                val handleCycleAudioSource: () -> Unit = {
                    val entries = AudioSourceMode.entries
                    val currentIdx = entries.indexOf(uiState.config.audioSource)
                    val nextMode = entries[(currentIdx + 1) % entries.size]
                    handleSelectAudioSource(nextMode)
                }

                val handleCheckAudioClick: () -> Unit = {
                    if (uiState.config.audioSource.recordAudio &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingAudioSource = uiState.config.audioSource
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.toggleAudioLiveCheck()
                    }
                }

                val closeSessionAndExitApp: () -> Unit = {
                    viewModel.closeOverlaySession()
                    finishAndRemoveTask()
                }

                LaunchedEffect(uiState.telemetry.errorMessage) {
                    uiState.telemetry.errorMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearError()
                    }
                }

                if (isInPipModeState) {
                    PipCompactHudView(
                        telemetry = uiState.telemetry,
                        onStartCaptureClick = {
                            ensureWholeScreenArmedAndRun(PENDING_ACTION_SINGLE_TAP_REC)
                        },
                        onPauseResumeClick = viewModel::pauseOrResumeRecording,
                        onStopCaptureClick = viewModel::stopRecording
                    )
                } else {
                    SimpleSingleScreenRecorderApp(
                        uiState = uiState,
                        snackbarHostState = snackbarHostState,
                        onSingleTapGesture = {
                            ensureWholeScreenArmedAndRun(PENDING_ACTION_SINGLE_TAP_REC)
                        },
                        onDoubleTapScreenshotGesture = {
                            ensureWholeScreenArmedAndRun(PENDING_ACTION_DOUBLE_TAP_SS)
                        },
                        onLongPressTimedGesture = {
                            ensureWholeScreenArmedAndRun(PENDING_ACTION_LONG_PRESS_TIMED)
                        },
                        onCycleAudioSource = handleCycleAudioSource,
                        onSelectAudioSource = handleSelectAudioSource,
                        onCheckAudioClick = handleCheckAudioClick,
                        onMinimizeToHome = { minimizeToHomeScreen(context) },
                        onOpenOverlaySettings = { openOverlayPermissionSettings(context) },
                        onCloseSessionAndApp = closeSessionAndExitApp,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState = isInPictureInPictureMode
    }

    /**
     * Always requests Entire Screen (`createConfigForDefaultDisplay`) on Android 14+
     * so Android never asks "Record a single app or Entire screen".
     */
    private fun createWholeScreenCaptureIntent(context: Context): Intent {
        val manager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    private fun openOverlayPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                startActivity(intent)
            }
        }
    }

    private fun minimizeToHomeScreen(context: Context) {
        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        if (!canDraw && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
                return
            }
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }
}

@Composable
fun SimpleSingleScreenRecorderApp(
    uiState: RecorderUiState,
    snackbarHostState: SnackbarHostState,
    onSingleTapGesture: () -> Unit,
    onDoubleTapScreenshotGesture: () -> Unit,
    onLongPressTimedGesture: () -> Unit,
    onCycleAudioSource: () -> Unit,
    onSelectAudioSource: (AudioSourceMode) -> Unit,
    onCheckAudioClick: () -> Unit,
    onMinimizeToHome: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onCloseSessionAndApp: () -> Unit,
    viewModel: RecorderViewModel
) {
    val context = LocalContext.current
    val isRecordingOrPaused =
        uiState.telemetry.status == RecorderStatus.RECORDING ||
            uiState.telemetry.status == RecorderStatus.PAUSED ||
            uiState.telemetry.isMultiShotGap

    var showSavePresetDialog by remember { mutableStateOf(false) }

    // Return to Recorder subtab when pressing Back from Saved Captures subtab
    BackHandler(enabled = uiState.activeSubTab == RecorderSubTab.SAVED_CAPTURES) {
        viewModel.selectSubTab(RecorderSubTab.RECORDER)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = ObsidianBackground,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    MinimalTopBarWithDragToClose(
                        uiState = uiState,
                        onCycleAudioSource = onCycleAudioSource,
                        onDragDownToCloseApp = onCloseSessionAndApp
                    )
                    RecorderSubTabSwitcher(
                        activeSubTab = uiState.activeSubTab,
                        savedCount = uiState.clips.size,
                        onSelectSubTab = viewModel::selectSubTab
                    )
                }
            }
        ) { innerPadding ->
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 10.dp,
                        bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 560.dp)
                        .testTag("single_screen_list")
                ) {
                    // Instant Saved Clip / Screenshot Toast Banner
                    item {
                        AnimatedVisibility(
                            visible = uiState.recentSavedBannerClip != null,
                            enter = slideInVertically() + fadeIn(),
                            exit = slideOutVertically() + fadeOut()
                        ) {
                            uiState.recentSavedBannerClip?.let { clip ->
                                SavedClipBanner(
                                    clipTitle = clip.title,
                                    clipMeta = if (clip.isScreenshot) {
                                        "Screenshot PNG • ${formatFileSize(clip.sizeBytes)}"
                                    } else {
                                        "${formatFloatingPointTimecode(clip.durationMillis)} • ${formatFileSize(clip.sizeBytes)} • ${clip.audioSourceLabel}"
                                    },
                                    onPlayClick = {
                                        viewModel.dismissSavedBanner()
                                        viewModel.selectSubTab(RecorderSubTab.SAVED_CAPTURES)
                                        viewModel.openClipPlayback(clip)
                                    },
                                    onDismiss = viewModel::dismissSavedBanner
                                )
                            }
                        }
                    }

                    if (uiState.activeSubTab == RecorderSubTab.RECORDER) {
                        // 1. Primary Entire-Screen Gesture Hub + Multi-Shot with Gap Card
                        item {
                            WholeScreenGestureHubCard(
                                uiState = uiState,
                                onSingleTapGesture = onSingleTapGesture,
                                onDoubleTapScreenshotGesture = onDoubleTapScreenshotGesture,
                                onLongPressTimedGesture = onLongPressTimedGesture,
                                onSelectAutoTimerSeconds = viewModel::setAutoRecordDurationSeconds,
                                onSelectMultiShotCount = viewModel::setMultiShotCount,
                                onSelectMultiShotGapSeconds = viewModel::setMultiShotGapSeconds,
                                onMinimizeToHome = onMinimizeToHome,
                                onOpenOverlaySettings = onOpenOverlaySettings
                            )
                        }

                        // 2. Audio Source (with Live Check & Meter), Resolution & Mbps Slider Card
                        item {
                            CaptureSettingsCard(
                                config = uiState.config,
                                telemetry = uiState.telemetry,
                                isLocked = isRecordingOrPaused,
                                onSelectAudioSource = onSelectAudioSource,
                                onCheckAudioClick = onCheckAudioClick,
                                onSelectResolution = viewModel::setResolution,
                                onBitrateMbpsChange = viewModel::setBitrateMbps
                            )
                        }

                        // 3. Save System Card (Save Current Options + Reusable Saved Presets)
                        item {
                            SaveOptionsSystemCard(
                                config = uiState.config,
                                savedPresets = uiState.savedPresets,
                                saveConfirmationBadge = uiState.saveConfirmationBadge,
                                isLocked = isRecordingOrPaused,
                                onSaveCurrentOptions = { viewModel.saveCurrentOptionsToSystem(null) },
                                onOpenSavePresetDialog = { showSavePresetDialog = true },
                                onApplyPreset = viewModel::applySavedPreset,
                                onDeletePreset = viewModel::deleteSavedPreset
                            )
                        }
                    } else {
                        // SUBTAB 2: SAVED CAPTURES & SCREENSHOTS
                        item {
                            SavedCapturesSubTabHeader(
                                activeFilter = uiState.activeCaptureFilter,
                                totalCount = uiState.clips.size,
                                videoCount = uiState.clips.count { !it.isScreenshot },
                                screenshotCount = uiState.clips.count { it.isScreenshot },
                                config = uiState.config,
                                onSelectFilter = viewModel::selectCaptureFilter
                            )
                        }

                        val visibleClips = uiState.filteredClips
                        if (visibleClips.isEmpty()) {
                            item {
                                SimpleEmptyClipsCard(filter = uiState.activeCaptureFilter)
                            }
                        } else {
                            items(visibleClips, key = { it.id }) { clip ->
                                RecordedClipCard(
                                    clip = clip,
                                    onPlay = { viewModel.openClipPlayback(clip) },
                                    onRename = { viewModel.promptRenameClip(clip) },
                                    onShare = { shareRecordedClip(context, clip) },
                                    onDelete = { viewModel.deleteClip(clip) }
                                )
                            }
                        }
                    }
                }

                // In-app Minimal Floating Circular Gesture Button (with SVG icons & Drag Down to Close)
                DraggableFloatingHudPill(
                    config = uiState.config,
                    telemetry = uiState.telemetry,
                    onSingleTapRecordOrStop = onSingleTapGesture,
                    onDoubleTapScreenshot = onDoubleTapScreenshotGesture,
                    onLongPressAutoRecord = onLongPressTimedGesture,
                    onDragDownToCloseApp = onCloseSessionAndApp
                )
            }
        }

        // Save Custom Preset Dialog
        if (showSavePresetDialog) {
            SavePresetDialog(
                config = uiState.config,
                onDismiss = { showSavePresetDialog = false },
                onConfirmSave = { presetName ->
                    viewModel.saveCurrentOptionsToSystem(presetName)
                    showSavePresetDialog = false
                }
            )
        }

        // Video / Screenshot Preview Modal
        uiState.selectedClipForPlayback?.let { selectedClip ->
            ClipPlaybackDialog(
                clip = selectedClip,
                onDismiss = viewModel::closeClipPlayback,
                onShare = { shareRecordedClip(context, selectedClip) },
                onDelete = { viewModel.deleteClip(selectedClip) }
            )
        }

        // Rename Clip Dialog
        uiState.clipToRename?.let { renameTarget ->
            RenameClipDialog(
                clip = renameTarget,
                onDismiss = viewModel::dismissRenameDialog,
                onConfirm = viewModel::confirmRenameClip
            )
        }
    }
}

@Composable
private fun RecorderSubTabSwitcher(
    activeSubTab: RecorderSubTab,
    savedCount: Int,
    onSelectSubTab: (RecorderSubTab) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .testTag("subtab_switcher_row")
    ) {
        RecorderSubTab.entries.forEach { tab ->
            val isSelected = activeSubTab == tab
            val tabBg by animateColorAsState(
                targetValue = if (isSelected) TallyRecordingRed else ElevatedHudSurface,
                label = "subtab_bg"
            )
            val labelText = if (tab == RecorderSubTab.SAVED_CAPTURES) {
                "Saved Captures ($savedCount)"
            } else {
                tab.label
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tabBg)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) TallyRecordingRed else GlassBorderSubtle,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelectSubTab(tab) }
                    .padding(vertical = 10.dp, horizontal = 8.dp)
                    .testTag("subtab_${tab.name.lowercase(Locale.US)}")
            ) {
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) CrispStudioWhite else MutedAluminum
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WholeScreenGestureHubCard(
    uiState: RecorderUiState,
    onSingleTapGesture: () -> Unit,
    onDoubleTapScreenshotGesture: () -> Unit,
    onLongPressTimedGesture: () -> Unit,
    onSelectAutoTimerSeconds: (Int) -> Unit,
    onSelectMultiShotCount: (Int) -> Unit,
    onSelectMultiShotGapSeconds: (Int) -> Unit,
    onMinimizeToHome: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val context = LocalContext.current
    val canDrawOverlays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }

    val isRecording = uiState.telemetry.status == RecorderStatus.RECORDING
    val isMultiShotGap = uiState.telemetry.isMultiShotGap
    val isTimedAuto = uiState.telemetry.autoStopTargetMillis > 0L && isRecording

    val displayMillis = when {
        isMultiShotGap -> uiState.telemetry.gapRemainingMillis
        isTimedAuto -> (uiState.telemetry.autoStopTargetMillis - uiState.telemetry.elapsedMillis).coerceAtLeast(0L)
        else -> uiState.telemetry.elapsedMillis
    }

    val activeSvgIconRes = when (uiState.telemetry.gestureIconState) {
        GestureIconState.DOUBLE_TAP_SCREENSHOT -> R.drawable.ic_gesture_double_tap_ss
        GestureIconState.LONG_PRESS_TIMED -> R.drawable.ic_gesture_long_press_timer
        GestureIconState.SINGLE_TAP_RECORDING,
        GestureIconState.SINGLE_TAP_IDLE -> R.drawable.ic_gesture_single_tap
    }

    val timerPresets = listOf(5, 10, 15, 30, 60)
    val multiShotPresets = listOf(1, 2, 3, 5, 10)
    val gapPresets = listOf(0, 1, 2, 3, 5, 10)

    Surface(
        color = AnodizedSlateSurface,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = when {
                    isMultiShotGap -> AmberSignal.copy(alpha = 0.85f)
                    isRecording -> TallyRecordingRed.copy(alpha = 0.75f)
                    else -> GlassBorderSubtle
                },
                shape = RoundedCornerShape(24.dp)
            )
            .testTag("whole_screen_gesture_hub_card")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = when {
                    isMultiShotGap ->
                        "MULTI-SHOT GAP • SHOT ${uiState.telemetry.currentShotIndex}/${uiState.telemetry.totalShots} STARTING SOON"
                    isRecording && uiState.telemetry.totalShots > 1 ->
                        "RECORDING SHOT ${uiState.telemetry.currentShotIndex} OF ${uiState.telemetry.totalShots} • ENTIRE SCREEN"
                    uiState.telemetry.isSessionArmed ->
                        "ALWAYS ENTIRE SCREEN ARMED • NO REPEATED PROMPTS"
                    else ->
                        "ONE-TIME ENTIRE SCREEN SETUP • TAP ONCE TO ARM"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    isMultiShotGap -> AmberSignal
                    uiState.telemetry.isSessionArmed -> EmeraldReady
                    else -> PrecisionCyan
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = formatFloatingPointTimecode(displayMillis),
                style = MaterialTheme.typography.displayLarge,
                color = if (isTimedAuto || isMultiShotGap) AmberSignal else CrispStudioWhite,
                modifier = Modifier.testTag("main_timecode_display")
            )

            Text(
                text = uiState.telemetry.lastGestureLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MutedAluminum,
                fontSize = 11.5.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Main Multi-Gesture Circular Button (1x Tap = Rec/Stop, 2x Tap = SS, Hold = Multi-Shot Auto-Record)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(ObsidianBackground)
                    .border(
                        width = 3.dp,
                        color = when {
                            isTimedAuto || isMultiShotGap -> AmberSignal
                            isRecording -> TallyRecordingRed
                            else -> CrispStudioWhite
                        },
                        shape = CircleShape
                    )
                    .combinedClickable(
                        onClick = onSingleTapGesture,
                        onDoubleClick = onDoubleTapScreenshotGesture,
                        onLongClick = onLongPressTimedGesture
                    )
                    .testTag("primary_shutter_button")
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = activeSvgIconRes),
                        contentDescription = "Gesture Shutter SVG Icon",
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when {
                            isMultiShotGap -> "CANCEL"
                            isRecording -> "STOP"
                            else -> "REC"
                        },
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = CrispStudioWhite
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3 Gesture Action Tiles with Custom SVG Icons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                GestureSvgBadgeTile(
                    svgResId = R.drawable.ic_gesture_single_tap,
                    title = "1x Tap",
                    subtitle = if (isRecording || isMultiShotGap) "Tap to Stop" else "Start Rec",
                    accentColor = TallyRecordingRed,
                    onClick = onSingleTapGesture,
                    modifier = Modifier.weight(1f)
                )

                GestureSvgBadgeTile(
                    svgResId = R.drawable.ic_gesture_double_tap_ss,
                    title = "2x Tap",
                    subtitle = "Screenshot",
                    accentColor = PrecisionCyan,
                    onClick = onDoubleTapScreenshotGesture,
                    modifier = Modifier.weight(1f)
                )

                val holdSubtitle = if (uiState.config.multiShotCount > 1) {
                    "${uiState.config.multiShotCount}x ${uiState.config.autoRecordDurationSeconds}s (${uiState.config.multiShotGapSeconds}s gap)"
                } else {
                    "Auto ${uiState.config.autoRecordDurationSeconds}s"
                }
                GestureSvgBadgeTile(
                    svgResId = R.drawable.ic_gesture_long_press_timer,
                    title = "Hold",
                    subtitle = holdSubtitle,
                    accentColor = AmberSignal,
                    onClick = onLongPressTimedGesture,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Multi-Shot & Auto-Record Duration Configuration Box
            Surface(
                color = ObsidianBackground.copy(alpha = 0.55f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GlassBorderSubtle, RoundedCornerShape(16.dp))
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(12.dp)
                ) {
                    // Row 1: Hold Auto-Record Clip Time
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_gesture_long_press_timer),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CLIP TIME:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MutedAluminum
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            timerPresets.forEach { sec ->
                                val selected = uiState.config.autoRecordDurationSeconds == sec
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) AmberSignal else ElevatedHudSurface)
                                        .clickable(enabled = !isRecording && !isMultiShotGap) {
                                            onSelectAutoTimerSeconds(sec)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .testTag("auto_timer_${sec}s_chip")
                                ) {
                                    Text(
                                        text = "${sec}s",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.5.sp,
                                        color = if (selected) ObsidianBackground else CrispStudioWhite
                                    )
                                }
                            }
                        }
                    }

                    // Row 2: Multi-Shot Count (1x, 2x, 3x, 5x, 10x shots)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "MULTI-SHOTS:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MutedAluminum
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            multiShotPresets.forEach { shots ->
                                val selected = uiState.config.multiShotCount == shots
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) PrecisionCyan else ElevatedHudSurface)
                                        .clickable(enabled = !isRecording && !isMultiShotGap) {
                                            onSelectMultiShotCount(shots)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .testTag("multishot_count_${shots}_chip")
                                ) {
                                    Text(
                                        text = "${shots}x",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.5.sp,
                                        color = if (selected) ObsidianBackground else CrispStudioWhite
                                    )
                                }
                            }
                        }
                    }

                    // Row 3: Gap Between Shots (0s, 1s, 2s, 3s, 5s, 10s)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "SHOT GAP TIME:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MutedAluminum
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            gapPresets.forEach { gapSec ->
                                val selected = uiState.config.multiShotGapSeconds == gapSec
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) EmeraldReady else ElevatedHudSurface)
                                        .clickable(enabled = !isRecording && !isMultiShotGap) {
                                            onSelectMultiShotGapSeconds(gapSec)
                                        }
                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                        .testTag("multishot_gap_${gapSec}s_chip")
                                ) {
                                    Text(
                                        text = "${gapSec}s",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.5.sp,
                                        color = if (selected) ObsidianBackground else CrispStudioWhite
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onMinimizeToHome,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("minimize_to_home_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = PrecisionCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Float Over Other Apps",
                        color = CrispStudioWhite,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!canDrawOverlays) {
                    TextButton(
                        onClick = onOpenOverlaySettings,
                        modifier = Modifier.testTag("enable_system_overlay_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = AmberSignal,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Overlay Perm",
                            color = AmberSignal,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureSvgBadgeTile(
    svgResId: Int,
    title: String,
    subtitle: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ElevatedHudSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp)
        ) {
            Image(
                painter = painterResource(id = svgResId),
                contentDescription = title,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = CrispStudioWhite
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 10.sp,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MinimalTopBarWithDragToClose(
    uiState: RecorderUiState,
    onCycleAudioSource: () -> Unit,
    onDragDownToCloseApp: () -> Unit
) {
    val isRecording = uiState.telemetry.status == RecorderStatus.RECORDING
    val isPaused = uiState.telemetry.status == RecorderStatus.PAUSED
    val isGap = uiState.telemetry.isMultiShotGap
    val statusColor = when {
        isRecording -> TallyRecordingRed
        isPaused || isGap -> AmberSignal
        uiState.telemetry.isSessionArmed -> EmeraldReady
        else -> MutedAluminum
    }
    val statusLabel = when {
        isRecording -> if (uiState.telemetry.totalShots > 1) {
            "SHOT ${uiState.telemetry.currentShotIndex}/${uiState.telemetry.totalShots}"
        } else {
            "REC"
        }
        isGap -> "GAP"
        isPaused -> "PAUSED"
        uiState.telemetry.isSessionArmed -> "ARMED"
        else -> "STANDBY"
    }

    var dragAccumulatorY by remember { mutableFloatStateOf(0f) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulatorY = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulatorY += dragAmount
                        if (dragAccumulatorY > 110f) {
                            dragAccumulatorY = 0f
                            onDragDownToCloseApp()
                        }
                    }
                )
            }
            .testTag("top_bar_drag_close_area")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ElevatedHudSurface.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_gesture_drag_close),
                contentDescription = "Drag down to close app",
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Drag down to close app",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = MutedAluminum
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "FloatRec",
                    style = MaterialTheme.typography.titleLarge,
                    color = CrispStudioWhite
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = ElevatedHudSurface,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = statusLabel,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Surface(
                color = ElevatedHudSurface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(
                        enabled = !isRecording && !isPaused && !isGap,
                        onClick = onCycleAudioSource
                    )
                    .border(1.dp, GlassBorderSubtle, RoundedCornerShape(14.dp))
                    .testTag("audio_source_header_chip")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    val audioIcon = when (uiState.config.audioSource) {
                        AudioSourceMode.INTERNAL_AUDIO -> Icons.AutoMirrored.Filled.VolumeUp
                        AudioSourceMode.MICROPHONE -> Icons.Default.Mic
                        AudioSourceMode.INTERNAL_PLUS_MIC -> Icons.Default.GraphicEq
                        AudioSourceMode.NO_AUDIO -> Icons.AutoMirrored.Filled.VolumeOff
                    }
                    Icon(
                        imageVector = audioIcon,
                        contentDescription = "Cycle Audio Source",
                        tint = if (uiState.config.audioSource.recordAudio) EmeraldReady else MutedAluminum,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiState.config.audioSource.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = CrispStudioWhite
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureSettingsCard(
    config: RecorderConfig,
    telemetry: com.example.service.LiveRecordingTelemetry,
    isLocked: Boolean,
    onSelectAudioSource: (AudioSourceMode) -> Unit,
    onCheckAudioClick: () -> Unit,
    onSelectResolution: (ResolutionPreset) -> Unit,
    onBitrateMbpsChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val hasMicPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    val estimatedMbPerMinute = (config.bitrateMbps * 60f) / 8f
    val animatedPeak by animateFloatAsState(
        targetValue = telemetry.audioPeakLevel.coerceIn(0f, 1f),
        label = "audio_peak_anim"
    )

    Surface(
        color = AnodizedSlateSurface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorderSubtle, RoundedCornerShape(22.dp))
            .testTag("resolution_and_mbps_card")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            // 1. Audio Source Selector: Internal Audio, Microphone, Internal + Mic, No Audio
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (config.audioSource.recordAudio) {
                            Icons.Default.GraphicEq
                        } else {
                            Icons.Default.MicOff
                        },
                        contentDescription = null,
                        tint = EmeraldReady,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "AUDIO SOURCE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedAluminum
                    )
                }
                Text(
                    text = config.audioSource.label.uppercase(Locale.US),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CrispStudioWhite
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AudioSourceMode.entries.forEach { mode ->
                    val isSelected = config.audioSource == mode
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) EmeraldReady.copy(alpha = 0.24f) else ElevatedHudSurface,
                        label = "audio_chip_bg"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(chipBg.copy(alpha = if (isLocked) 0.5f else 1f))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) EmeraldReady else GlassBorderSubtle,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(enabled = !isLocked) { onSelectAudioSource(mode) }
                            .padding(vertical = 9.dp, horizontal = 4.dp)
                            .testTag("audio_option_${mode.name.lowercase(Locale.US)}")
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (isSelected) EmeraldReady else MutedAluminum,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Live Audio Status & Check Bar
            Surface(
                color = ObsidianBackground.copy(alpha = 0.6f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GlassBorderSubtle, RoundedCornerShape(14.dp))
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val diagText = when {
                            config.audioSource == AudioSourceMode.NO_AUDIO ->
                                "No Audio • Silent Video Mode"
                            !hasMicPermission ->
                                "Tap 'Check Audio' to grant Audio Permission"
                            telemetry.isAudioMonitorActive ->
                                telemetry.audioStatusDetail
                            else ->
                                "${config.audioSource.label} Ready (44.1 kHz AAC)"
                        }
                        Text(
                            text = diagText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 11.sp,
                            color = if (!hasMicPermission && config.audioSource.recordAudio) {
                                AmberSignal
                            } else {
                                EmeraldReady
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        if (config.audioSource != AudioSourceMode.NO_AUDIO) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (telemetry.isAudioMonitorActive) TallyRecordingRed else ElevatedHudSurface
                                    )
                                    .clickable(enabled = !isLocked) { onCheckAudioClick() }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .testTag("check_audio_button")
                            ) {
                                Text(
                                    text = if (telemetry.isAudioMonitorActive) "STOP TEST" else "CHECK AUDIO",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = CrispStudioWhite
                                )
                            }
                        }
                    }

                    // Live Audio Signal Level Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(ElevatedHudSurface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(
                                    fraction = if (config.audioSource == AudioSourceMode.NO_AUDIO) {
                                        0f
                                    } else {
                                        animatedPeak.coerceAtLeast(if (hasMicPermission) 0.06f else 0f)
                                    }
                                )
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (animatedPeak > 0.75f) AmberSignal else EmeraldReady
                                )
                        )
                    }
                }
            }

            // 2. Resolution Ratio Selector: Full Screen, 16:9, 9:16, 1:1
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AspectRatio,
                        contentDescription = null,
                        tint = PrecisionCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "RESOLUTION RATIO",
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedAluminum
                    )
                }
                Text(
                    text = config.resolution.label.uppercase(Locale.US),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CrispStudioWhite
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ResolutionPreset.entries.forEach { preset ->
                    val isSelected = config.resolution == preset
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) TallyRecordingRed else ElevatedHudSurface,
                        label = "res_chip_bg"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(chipBg.copy(alpha = if (isLocked) 0.5f else 1f))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) TallyRecordingRed else GlassBorderSubtle,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(enabled = !isLocked) { onSelectResolution(preset) }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                            .testTag("resolution_option_${preset.name.lowercase(Locale.US)}")
                    ) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) CrispStudioWhite else MutedAluminum,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 3. Floating-Point Mbps Bitrate Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = TallyRecordingRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "VIDEO BITRATE (MBPS)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MutedAluminum
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = String.format(Locale.US, "%.1f Mbps", config.bitrateMbps),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = PrecisionCyan,
                            modifier = Modifier.testTag("bitrate_mbps_value")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = String.format(Locale.US, "(~%.0f MB/min)", estimatedMbPerMinute),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.5.sp,
                            color = MutedAluminum
                        )
                    }
                }

                Slider(
                    value = config.bitrateMbps,
                    onValueChange = onBitrateMbpsChange,
                    valueRange = 1.0f..24.0f,
                    enabled = !isLocked,
                    colors = SliderDefaults.colors(
                        thumbColor = TallyRecordingRed,
                        activeTrackColor = TallyRecordingRed,
                        inactiveTrackColor = ElevatedHudSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bitrate_mbps_slider")
                )
            }
        }
    }
}

@Composable
private fun SaveOptionsSystemCard(
    config: RecorderConfig,
    savedPresets: List<SavedRecorderPreset>,
    saveConfirmationBadge: String?,
    isLocked: Boolean,
    onSaveCurrentOptions: () -> Unit,
    onOpenSavePresetDialog: () -> Unit,
    onApplyPreset: (SavedRecorderPreset) -> Unit,
    onDeletePreset: (String) -> Unit
) {
    Surface(
        color = AnodizedSlateSurface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorderSubtle, RoundedCornerShape(22.dp))
            .testTag("save_options_system_card")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdded,
                        contentDescription = null,
                        tint = PrecisionCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SAVE SYSTEM & PRESETS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedAluminum
                    )
                }

                Text(
                    text = saveConfirmationBadge ?: "AUTO-SAVED TO STORAGE",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (saveConfirmationBadge != null) EmeraldReady else PrecisionCyan
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onSaveCurrentOptions,
                    enabled = !isLocked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldReady,
                        contentColor = ObsidianBackground
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("save_current_options_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Save Options",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                OutlinedButton(
                    onClick = onOpenSavePresetDialog,
                    enabled = !isLocked,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("save_as_preset_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = PrecisionCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Save as Preset",
                        color = CrispStudioWhite,
                        fontSize = 12.sp
                    )
                }
            }

            if (savedPresets.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(savedPresets, key = { it.id }) { preset ->
                        val isCurrentMatch =
                            preset.resolution == config.resolution &&
                                preset.audioSource == config.audioSource &&
                                preset.autoRecordDurationSeconds == config.autoRecordDurationSeconds &&
                                preset.multiShotCount == config.multiShotCount &&
                                preset.multiShotGapSeconds == config.multiShotGapSeconds

                        Surface(
                            color = if (isCurrentMatch) {
                                PrecisionCyan.copy(alpha = 0.18f)
                            } else {
                                ElevatedHudSurface
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !isLocked) { onApplyPreset(preset) }
                                .border(
                                    width = 1.dp,
                                    color = if (isCurrentMatch) PrecisionCyan else GlassBorderSubtle,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .testTag("preset_chip_${preset.id}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = CrispStudioWhite
                                    )
                                    Text(
                                        text = preset.summaryBadge,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 9.5.sp,
                                        color = if (isCurrentMatch) PrecisionCyan else MutedAluminum
                                    )
                                }
                                if (!preset.id.startsWith("preset_")) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { onDeletePreset(preset.id) },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove preset",
                                            tint = MutedAluminum,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedCapturesSubTabHeader(
    activeFilter: CaptureFilterTab,
    totalCount: Int,
    videoCount: Int,
    screenshotCount: Int,
    config: RecorderConfig,
    onSelectFilter: (CaptureFilterTab) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "SAVED CAPTURES ($totalCount)",
                style = MaterialTheme.typography.labelLarge,
                color = MutedAluminum
            )
            Text(
                text = String.format(
                    Locale.US,
                    "%s • %.1f Mbps • %s",
                    config.resolution.label,
                    config.bitrateMbps,
                    config.audioSource.shortBadge
                ),
                style = MaterialTheme.typography.labelMedium,
                color = PrecisionCyan
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            CaptureFilterTab.entries.forEach { filter ->
                val isSelected = activeFilter == filter
                val countBadge = when (filter) {
                    CaptureFilterTab.ALL -> totalCount
                    CaptureFilterTab.VIDEOS -> videoCount
                    CaptureFilterTab.SCREENSHOTS -> screenshotCount
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) PrecisionCyan.copy(alpha = 0.22f) else ElevatedHudSurface
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) PrecisionCyan else GlassBorderSubtle,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectFilter(filter) }
                        .padding(vertical = 8.dp, horizontal = 6.dp)
                        .testTag("capture_filter_${filter.name.lowercase(Locale.US)}")
                ) {
                    Text(
                        text = "${filter.label} ($countBadge)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) PrecisionCyan else MutedAluminum
                    )
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(
    config: RecorderConfig,
    onDismiss: () -> Unit,
    onConfirmSave: (String) -> Unit
) {
    var presetName by remember {
        mutableStateOf(
            "${config.resolution.shortBadge} ${config.audioSource.shortBadge} (${config.multiShotCount}x${config.autoRecordDurationSeconds}s)"
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AnodizedSlateSurface,
        titleContentColor = CrispStudioWhite,
        textContentColor = CrispStudioWhite,
        title = { Text("Save Recorder Preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Saves Audio (${config.audioSource.label}), Resolution (${config.resolution.label}), Bitrate (${config.bitrateMbps} Mbps), and Multi-Shot (${config.multiShotCount}x ${config.autoRecordDurationSeconds}s, ${config.multiShotGapSeconds}s gap).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedAluminum
                )
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    singleLine = true,
                    label = { Text("Preset Name") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmSave(presetName) },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldReady, contentColor = ObsidianBackground)
            ) {
                Text("Save Preset", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MutedAluminum)
            }
        }
    )
}

@Composable
private fun SimpleEmptyClipsCard(filter: CaptureFilterTab) {
    Surface(
        color = AnodizedSlateSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorderSubtle, RoundedCornerShape(20.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(18.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ElevatedHudSurface)
            ) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = PrecisionCyan
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (filter) {
                        CaptureFilterTab.ALL -> "No Saved Captures Yet"
                        CaptureFilterTab.VIDEOS -> "No Saved Video Clips Yet"
                        CaptureFilterTab.SCREENSHOTS -> "No Saved Screenshots Yet"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = CrispStudioWhite
                )
                Text(
                    text = "1x Tap to Record/Stop • 2x Tap for Screenshot (SS) • Hold for Multi-Shot Auto-Record.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedAluminum
                )
            }
        }
    }
}

@Composable
private fun SavedClipBanner(
    clipTitle: String,
    clipMeta: String,
    onPlayClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = ElevatedHudSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, EmeraldReady.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = EmeraldReady,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Saved $clipTitle",
                    style = MaterialTheme.typography.titleMedium,
                    color = CrispStudioWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = clipMeta,
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldReady
                )
            }
            Surface(
                color = TallyRecordingRed,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onPlayClick)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = CrispStudioWhite,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "VIEW",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CrispStudioWhite
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MutedAluminum,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
