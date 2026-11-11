package com.example.model

import java.util.Locale

enum class RecorderStatus {
    STANDBY,
    COUNTDOWN,
    RECORDING,
    PAUSED
}

enum class RecorderSubTab(val label: String) {
    RECORDER("Recorder"),
    SAVED_CAPTURES("Saved Captures")
}

enum class CaptureFilterTab(val label: String) {
    ALL("All"),
    VIDEOS("Videos"),
    SCREENSHOTS("Screenshots")
}

enum class ResolutionPreset(
    val label: String,
    val shortBadge: String,
    val targetWidth: Int,
    val targetHeight: Int
) {
    FULL_SCREEN("Full Screen", "FULL", 0, 0),
    RATIO_16_9("16:9", "16:9", 1920, 1080),
    RATIO_9_16("9:16", "9:16", 1080, 1920),
    RATIO_1_1("1:1", "1:1", 1080, 1080);

    fun resolveDimensions(displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        fun align16(value: Int): Int = ((value.coerceAtLeast(320) + 8) / 16) * 16

        return if (this == FULL_SCREEN) {
            val rawW = displayWidth.coerceAtLeast(720)
            val rawH = displayHeight.coerceAtLeast(1280)
            val maxDim = maxOf(rawW, rawH)
            val scale = if (maxDim > 1920) 1920f / maxDim.toFloat() else 1f
            align16((rawW * scale).toInt()) to align16((rawH * scale).toInt())
        } else {
            align16(targetWidth) to align16(targetHeight)
        }
    }
}

enum class FrameRatePreset(
    val label: String,
    val fps: Int
) {
    FPS_30("30 FPS", 30),
    FPS_60("60 FPS", 60)
}

enum class AudioSourceMode(
    val label: String,
    val shortBadge: String,
    val recordAudio: Boolean
) {
    INTERNAL_AUDIO("Internal Audio", "Internal", true),
    MICROPHONE("Microphone", "Mic", true),
    INTERNAL_PLUS_MIC("Internal + Mic", "Int + Mic", true),
    NO_AUDIO("No Audio", "No Audio", false)
}

enum class HudPillMode {
    EXPANDED_PILL,
    COMPACT_ORB
}

enum class GestureIconState {
    SINGLE_TAP_IDLE,
    SINGLE_TAP_RECORDING,
    LONG_PRESS_TIMED,
    DOUBLE_TAP_SCREENSHOT
}

data class RecorderConfig(
    val resolution: ResolutionPreset = ResolutionPreset.FULL_SCREEN,
    val frameRate: FrameRatePreset = FrameRatePreset.FPS_60,
    val bitrateMbps: Float = 12.0f,
    val audioSource: AudioSourceMode = AudioSourceMode.INTERNAL_PLUS_MIC,
    val autoRecordDurationSeconds: Int = 10,
    val multiShotCount: Int = 1,
    val multiShotGapSeconds: Int = 3,
    val countdownSeconds: Int = 0,
    val showFloatingHud: Boolean = true,
    val showTouchReticle: Boolean = false,
    val hudPillMode: HudPillMode = HudPillMode.COMPACT_ORB
) {
    val bitrateBps: Int
        get() = (bitrateMbps.coerceIn(1.0f, 24.0f) * 1_000_000f).toInt()
}

data class SavedRecorderPreset(
    val id: String,
    val name: String,
    val resolution: ResolutionPreset,
    val bitrateMbps: Float,
    val audioSource: AudioSourceMode,
    val autoRecordDurationSeconds: Int,
    val multiShotCount: Int,
    val multiShotGapSeconds: Int
) {
    val summaryBadge: String
        get() = String.format(
            Locale.US,
            "%s • %.1fM • %s • %dx%ds (%ds gap)",
            resolution.shortBadge,
            bitrateMbps,
            audioSource.shortBadge,
            multiShotCount,
            autoRecordDurationSeconds,
            multiShotGapSeconds
        )
}

data class RecordedClip(
    val id: String,
    val title: String,
    val uriString: String,
    val filePath: String?,
    val durationMillis: Long,
    val sizeBytes: Long,
    val resolutionLabel: String,
    val fps: Int,
    val bitrateMbps: Int,
    val createdAtMillis: Long,
    val hasAudio: Boolean,
    val audioSourceLabel: String = "Internal + Mic",
    val isScreenshot: Boolean = false
)

fun formatFloatingPointTimecode(durationMillis: Long): String {
    val safeMillis = durationMillis.coerceAtLeast(0L)
    val totalSeconds = safeMillis / 1000L
    val tenths = (safeMillis % 1000L) / 100L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d.%d", hours, minutes, seconds, tenths)
    } else {
        String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
    }
}

fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "0.0 KB"
    val kb = sizeBytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.0f KB", kb)
    }
}
