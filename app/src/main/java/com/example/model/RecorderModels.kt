package com.example.model

import android.media.MediaCodecInfo
import android.media.MediaFormat
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
    val shortBadge: String,
    val fps: Int,
    val qualityHint: String,
    val bitrateEfficiencyFactor: Float
) {
    FPS_24("24 FPS", "24fps", 24, "Compact File Size • Cinema", 0.78f),
    FPS_30("30 FPS", "30fps", 30, "Balanced Quality & Size", 0.88f),
    FPS_60("60 FPS", "60fps", 60, "Ultra Smooth • High Motion", 1.0f)
}

enum class VideoEncoderCodec(
    val label: String,
    val shortBadge: String,
    val mimeType: String,
    val qualityHint: String,
    val efficiencyFactor: Float
) {
    H264_AVC(
        label = "H.264 AVC",
        shortBadge = "H.264",
        mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
        qualityHint = "Universal Player Compatibility",
        efficiencyFactor = 1.0f
    ),
    H265_HEVC(
        label = "H.265 HEVC",
        shortBadge = "HEVC",
        mimeType = MediaFormat.MIMETYPE_VIDEO_HEVC,
        qualityHint = "High Efficiency • ~32% Smaller",
        efficiencyFactor = 0.68f
    )
}

enum class AudioEncoderCodec(
    val label: String,
    val shortBadge: String,
    val mimeType: String,
    val aacProfile: Int,
    val qualityHint: String
) {
    AAC_LC(
        label = "AAC-LC",
        shortBadge = "AAC",
        mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
        aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC,
        qualityHint = "Standard Studio MP4 Audio"
    ),
    HE_AAC(
        label = "HE-AAC",
        shortBadge = "HE-AAC",
        mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
        aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectHE,
        qualityHint = "High-Efficiency Voice & Media"
    ),
    OPUS(
        label = "Opus",
        shortBadge = "Opus",
        mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS,
        aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC,
        qualityHint = "Wideband Dynamic Studio Audio"
    )
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
    val videoCodec: VideoEncoderCodec = VideoEncoderCodec.H264_AVC,
    val audioCodec: AudioEncoderCodec = AudioEncoderCodec.AAC_LC,
    val audioBitrateKbps: Int = 192,
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
    val effectiveBitrateMbps: Float
        get() = (
            bitrateMbps.coerceIn(1.0f, 24.0f) *
                frameRate.bitrateEfficiencyFactor *
                videoCodec.efficiencyFactor
            ).coerceIn(0.8f, 24.0f)

    val bitrateBps: Int
        get() = (effectiveBitrateMbps * 1_000_000f).toInt()

    val audioBitrateBps: Int
        get() = audioBitrateKbps.coerceIn(64, 320) * 1_000
}

data class SavedRecorderPreset(
    val id: String,
    val name: String,
    val resolution: ResolutionPreset,
    val frameRate: FrameRatePreset = FrameRatePreset.FPS_60,
    val videoCodec: VideoEncoderCodec = VideoEncoderCodec.H264_AVC,
    val audioCodec: AudioEncoderCodec = AudioEncoderCodec.AAC_LC,
    val audioBitrateKbps: Int = 192,
    val bitrateMbps: Float,
    val audioSource: AudioSourceMode,
    val autoRecordDurationSeconds: Int,
    val multiShotCount: Int,
    val multiShotGapSeconds: Int
) {
    val summaryBadge: String
        get() = String.format(
            Locale.US,
            "%s • %s • %s • %.1fM • %s %dk • %s",
            resolution.shortBadge,
            frameRate.shortBadge,
            videoCodec.shortBadge,
            bitrateMbps,
            audioCodec.shortBadge,
            audioBitrateKbps,
            audioSource.shortBadge
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
    val videoCodecLabel: String = "H.264",
    val audioCodecLabel: String = "AAC 192k",
    val isScreenshot: Boolean = false
)

data class ImportMediaEncoderState(
    val selectedVideoUri: String? = null,
    val selectedVideoName: String? = null,
    val selectedVideoDurationMs: Long = 0L,
    val selectedVideoSizeBytes: Long = 0L,
    val selectedAudioUri: String? = null,
    val selectedAudioName: String? = null,
    val selectedAudioDurationMs: Long = 0L,
    val isEncoding: Boolean = false,
    val encodeProgress: Float = 0f,
    val statusMessage: String = "Import a video and/or audio file to encode with your selected Video & Audio codecs."
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
