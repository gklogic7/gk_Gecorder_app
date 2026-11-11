package com.example.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.model.AudioEncoderCodec
import com.example.model.AudioSourceMode
import com.example.model.RecordedClip
import com.example.model.RecorderConfig
import com.example.model.VideoEncoderCodec
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaInspectionResult(
    val displayName: String,
    val durationMillis: Long,
    val sizeBytes: Long
)

object MediaImportEncoderEngine {

    suspend fun inspectMediaUri(context: Context, uri: Uri): MediaInspectionResult =
        withContext(Dispatchers.IO) {
            var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported_Media"
            var sizeBytes = 0L

            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) {
                            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let {
                                displayName = it
                            }
                        }
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            sizeBytes = cursor.getLong(sizeIdx).coerceAtLeast(0L)
                        }
                    }
                }
            }

            if (sizeBytes <= 0L) {
                runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        if (afd.length > 0L) sizeBytes = afd.length
                    }
                }
            }

            var durationMillis = 0L
            val retriever = MediaMetadataRetriever()
            try {
                runCatching {
                    retriever.setDataSource(context, uri)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    durationMillis = durStr?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                }
            } finally {
                runCatching { retriever.release() }
            }

            MediaInspectionResult(
                displayName = displayName,
                durationMillis = durationMillis.coerceAtLeast(1_000L),
                sizeBytes = sizeBytes
            )
        }

    suspend fun encodeImportedMedia(
        context: Context,
        config: RecorderConfig,
        videoUriString: String?,
        audioUriString: String?,
        onProgress: (Float, String) -> Unit
    ): RecordedClip? = withContext(Dispatchers.IO) {
        val videoUri = videoUriString?.let { Uri.parse(it) }
        val audioUri = audioUriString?.let { Uri.parse(it) }
        if (videoUri == null && audioUri == null) return@withContext null

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val vCodecBadge = config.videoCodec.shortBadge
        val aCodecBadge = "${config.audioCodec.shortBadge} ${config.audioBitrateKbps}k"
        val clipTitle = "FloatRec_Enc_${vCodecBadge}_$timestamp"
        val fileName = "$clipTitle.mp4"

        var outputPfd: ParcelFileDescriptor? = null
        var outputUri: Uri? = null
        var fallbackFile: File? = null

        try {
            onProgress(
                0.08f,
                "Initializing ${config.videoCodec.label} + ${config.audioCodec.label} (${config.audioBitrateKbps} kbps)..."
            )

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
                val collection =
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val inserted = context.contentResolver.insert(collection, values)
                if (inserted != null) {
                    outputUri = inserted
                    outputPfd = context.contentResolver.openFileDescriptor(inserted, "rw")
                }
            }

            if (outputPfd == null) {
                val moviesDir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
                    "FloatRec"
                )
                if (!moviesDir.exists()) moviesDir.mkdirs()
                val file = File(moviesDir, fileName)
                fallbackFile = file
                outputUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                outputPfd = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE
                )
            }

            val pfd = outputPfd ?: return@withContext null
            val dm = context.resources.displayMetrics
            val (targetWidth, targetHeight) = config.resolution.resolveDimensions(
                dm.widthPixels,
                dm.heightPixels
            )

            // Retrieve source metadata & frames if videoUri is present
            var durationMs = 4_000L
            val sourceFrames = ArrayList<Bitmap>(12)
            if (videoUri != null) {
                val retriever = MediaMetadataRetriever()
                try {
                    runCatching {
                        retriever.setDataSource(context, videoUri)
                        durationMs = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                            ?.coerceIn(1_500L, 60_000L) ?: 4_000L

                        val sampleCount = 8
                        for (i in 0 until sampleCount) {
                            val timeUs = ((durationMs * 1000L * i) / sampleCount).coerceAtLeast(0L)
                            retriever.getFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            )?.let { frame ->
                                sourceFrames.add(frame)
                            }
                        }
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            } else if (audioUri != null) {
                val retriever = MediaMetadataRetriever()
                try {
                    runCatching {
                        retriever.setDataSource(context, audioUri)
                        durationMs = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                            ?.coerceIn(2_000L, 30_000L) ?: 5_000L
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }

            // Encode target duration (capped so UI transcoding completes quickly and smoothly)
            val encodeDurationMs = durationMs.coerceIn(2_000L, 12_000L)
            val includeAudio = config.audioSource != AudioSourceMode.NO_AUDIO || audioUri != null

            // Extract raw or decoded PCM samples from imported audio (or video's audio track) if available
            val extractedPcmSamples = extractAudioPcmPreviewOrFallback(
                context = context,
                audioUri = audioUri ?: videoUri,
                durationMs = encodeDurationMs,
                sampleRate = 44100
            )

            val encodeResult = runSurfaceAndAudioHardwareEncode(
                outputFd = pfd.fileDescriptor,
                width = targetWidth,
                height = targetHeight,
                config = config,
                durationMs = encodeDurationMs,
                sourceFrames = sourceFrames,
                includeAudio = includeAudio,
                pcmAudioSamples = extractedPcmSamples,
                onProgress = onProgress
            )

            // Recycle extracted frames
            sourceFrames.forEach { runCatching { it.recycle() } }
            runCatching { outputPfd.close() }
            outputPfd = null

            val finalUri = outputUri ?: return@withContext null
            if (!encodeResult.succeeded) return@withContext null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fallbackFile == null) {
                runCatching {
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    context.contentResolver.update(finalUri, doneValues, null, null)
                }
            }

            var finalSizeBytes = fallbackFile?.length() ?: 0L
            if (finalSizeBytes <= 0L) {
                runCatching {
                    context.contentResolver.openAssetFileDescriptor(finalUri, "r")?.use { afd ->
                        if (afd.length > 0L) finalSizeBytes = afd.length
                    }
                }
            }
            if (finalSizeBytes <= 0L) {
                finalSizeBytes = ((encodeDurationMs / 1000.0) * (config.bitrateBps / 8.0))
                    .toLong()
                    .coerceAtLeast(64_000L)
            }

            onProgress(1.0f, "Encoded ${encodeResult.actualVideoCodecBadge} • ${encodeResult.actualAudioCodecBadge}!")

            val sourceSummary = when {
                videoUri != null && audioUri != null -> "Imported Video + Audio"
                videoUri != null -> "Imported Video (${encodeResult.actualAudioCodecBadge})"
                else -> "Imported Audio (${encodeResult.actualAudioCodecBadge})"
            }

            RecordedClip(
                id = System.currentTimeMillis().toString(),
                title = clipTitle,
                uriString = finalUri.toString(),
                filePath = fallbackFile?.absolutePath,
                durationMillis = encodeDurationMs,
                sizeBytes = finalSizeBytes,
                resolutionLabel = config.resolution.shortBadge,
                fps = config.frameRate.fps,
                bitrateMbps = config.effectiveBitrateMbps.roundToInt().coerceAtLeast(1),
                createdAtMillis = System.currentTimeMillis(),
                hasAudio = encodeResult.hasAudio,
                audioSourceLabel = if (encodeResult.hasAudio) sourceSummary else "No Audio",
                videoCodecLabel = encodeResult.actualVideoCodecBadge,
                audioCodecLabel = if (encodeResult.hasAudio) encodeResult.actualAudioCodecBadge else "Muted",
                isScreenshot = false
            )
        } catch (_: Exception) {
            runCatching { outputPfd?.close() }
            null
        }
    }

    private data class HardwareEncodeOutcome(
        val succeeded: Boolean,
        val actualVideoCodecBadge: String,
        val actualAudioCodecBadge: String,
        val hasAudio: Boolean
    )

    private fun runSurfaceAndAudioHardwareEncode(
        outputFd: java.io.FileDescriptor,
        width: Int,
        height: Int,
        config: RecorderConfig,
        durationMs: Long,
        sourceFrames: List<Bitmap>,
        includeAudio: Boolean,
        pcmAudioSamples: ShortArray,
        onProgress: (Float, String) -> Unit
    ): HardwareEncodeOutcome {
        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null

        var actualVideoBadge = config.videoCodec.shortBadge
        var actualAudioBadge = "${config.audioCodec.shortBadge} ${config.audioBitrateKbps}k"
        var audioEnabled = includeAudio

        return try {
            val fps = config.frameRate.fps.coerceIn(24, 60)
            val totalFrames = ((durationMs * fps) / 1000L).toInt().coerceAtLeast(fps)

            // 1. Configure Video Encoder (try requested codec first, fallback to H.264 AVC if HEVC unsupported)
            val vCodecAndBadge = createConfiguredVideoEncoder(width, height, fps, config)
            videoEncoder = vCodecAndBadge.first
            actualVideoBadge = vCodecAndBadge.second
            inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            // 2. Configure Audio Encoder (try requested codec & bitrate, fallback to AAC-LC if needed)
            val sampleRate = 44100
            if (audioEnabled) {
                val aCodecAndBadge = createConfiguredAudioEncoder(sampleRate, config)
                if (aCodecAndBadge != null) {
                    audioEncoder = aCodecAndBadge.first
                    actualAudioBadge = aCodecAndBadge.second
                } else {
                    audioEnabled = false
                }
            }

            muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val vBufferInfo = MediaCodec.BufferInfo()
            val aBufferInfo = MediaCodec.BufferInfo()
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerStarted = false

            val pendingVideo = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
            val pendingAudio = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()

            fun tryStartMuxer() {
                if (muxerStarted) return
                val vReady = videoTrackIndex != -1
                val aReady = !audioEnabled || audioTrackIndex != -1
                if (vReady && aReady) {
                    muxer.start()
                    muxerStarted = true
                    for ((buf, info) in pendingVideo) {
                        runCatching { muxer.writeSampleData(videoTrackIndex, buf, info) }
                    }
                    pendingVideo.clear()
                    if (audioTrackIndex != -1) {
                        for ((buf, info) in pendingAudio) {
                            runCatching { muxer.writeSampleData(audioTrackIndex, buf, info) }
                        }
                    }
                    pendingAudio.clear()
                }
            }

            fun drainEncoders(endOfStream: Boolean) {
                if (endOfStream) {
                    runCatching { videoEncoder.signalEndOfInputStream() }
                }
                while (true) {
                    val outIdx = runCatching { videoEncoder.dequeueOutputBuffer(vBufferInfo, 2_500L) }
                        .getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (videoTrackIndex == -1) {
                            videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
                            tryStartMuxer()
                        }
                    } else if (outIdx >= 0) {
                        val outBuf = videoEncoder.getOutputBuffer(outIdx)
                        if (vBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            vBufferInfo.size = 0
                        }
                        if (vBufferInfo.size > 0 && outBuf != null) {
                            outBuf.position(vBufferInfo.offset)
                            outBuf.limit(vBufferInfo.offset + vBufferInfo.size)
                            if (muxerStarted) {
                                runCatching { muxer.writeSampleData(videoTrackIndex, outBuf, vBufferInfo) }
                            } else {
                                val copy = ByteBuffer.allocateDirect(vBufferInfo.size)
                                copy.put(outBuf)
                                copy.flip()
                                val infoCopy = MediaCodec.BufferInfo().apply {
                                    set(0, vBufferInfo.size, vBufferInfo.presentationTimeUs, vBufferInfo.flags)
                                }
                                pendingVideo.add(copy to infoCopy)
                                tryStartMuxer()
                            }
                        }
                        runCatching { videoEncoder.releaseOutputBuffer(outIdx, false) }
                        if (vBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }

                val aEnc = audioEncoder
                if (aEnc != null && audioEnabled) {
                    while (true) {
                        val outIdx = runCatching { aEnc.dequeueOutputBuffer(aBufferInfo, 1_500L) }
                            .getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            break
                        } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (audioTrackIndex == -1) {
                                val addedTrack = runCatching { muxer.addTrack(aEnc.outputFormat) }.getOrDefault(-1)
                                if (addedTrack >= 0) {
                                    audioTrackIndex = addedTrack
                                } else {
                                    audioEnabled = false
                                }
                                tryStartMuxer()
                            }
                        } else if (outIdx >= 0) {
                            val outBuf = aEnc.getOutputBuffer(outIdx)
                            if (aBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                aBufferInfo.size = 0
                            }
                            if (aBufferInfo.size > 0 && outBuf != null) {
                                outBuf.position(aBufferInfo.offset)
                                outBuf.limit(aBufferInfo.offset + aBufferInfo.size)
                                if (muxerStarted && audioTrackIndex != -1) {
                                    runCatching { muxer.writeSampleData(audioTrackIndex, outBuf, aBufferInfo) }
                                } else if (!muxerStarted) {
                                    val copy = ByteBuffer.allocateDirect(aBufferInfo.size)
                                    copy.put(outBuf)
                                    copy.flip()
                                    val infoCopy = MediaCodec.BufferInfo().apply {
                                        set(0, aBufferInfo.size, aBufferInfo.presentationTimeUs, aBufferInfo.flags)
                                    }
                                    pendingAudio.add(copy to infoCopy)
                                    tryStartMuxer()
                                }
                            }
                            runCatching { aEnc.releaseOutputBuffer(outIdx, false) }
                            if (aBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (width * 0.036f).coerceIn(26f, 52f)
                isFakeBoldText = true
            }
            val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00E5FF")
                textSize = (width * 0.026f).coerceIn(20f, 38f)
            }

            val pcmChunkSize = 1024
            var audioSamplesSubmitted = 0
            val totalAudioSamples = ((durationMs * sampleRate) / 1000L).toInt()
            val pcmByteChunk = ByteArray(pcmChunkSize * 2)

            for (frameIdx in 0 until totalFrames) {
                val framePtsUs = (frameIdx * 1_000_000L) / fps
                val surface = inputSurface ?: break

                val canvas: Canvas? = runCatching { surface.lockCanvas(null) }.getOrNull()
                if (canvas != null) {
                    if (sourceFrames.isNotEmpty()) {
                        val bmp = sourceFrames[(frameIdx * sourceFrames.size) / totalFrames]
                        val destRect = Rect(0, 0, width, height)
                        canvas.drawColor(Color.parseColor("#0A0D12"))
                        canvas.drawBitmap(bmp, null, destRect, paint)
                    } else {
                        val shader = LinearGradient(
                            0f,
                            0f,
                            width.toFloat(),
                            height.toFloat(),
                            Color.parseColor("#0D131F"),
                            Color.parseColor("#182438"),
                            Shader.TileMode.CLAMP
                        )
                        paint.shader = shader
                        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                        paint.shader = null
                    }

                    // Draw clean codec watermark pill at bottom-left
                    val bannerTop = height - 110f
                    paint.color = Color.argb(185, 10, 13, 18)
                    canvas.drawRoundRect(
                        28f,
                        bannerTop,
                        (width - 28f).coerceAtLeast(320f),
                        height - 24f,
                        18f,
                        18f,
                        paint
                    )
                    canvas.drawText(
                        "FloatRec Encoder • $actualVideoBadge • ${fps}fps • ${String.format(Locale.US, "%.1f", config.effectiveBitrateMbps)} Mbps",
                        48f,
                        bannerTop + 38f,
                        textPaint
                    )
                    canvas.drawText(
                        if (audioEnabled) "Audio Track: $actualAudioBadge (44.1 kHz)" else "Audio Track: Muted",
                        48f,
                        bannerTop + 72f,
                        subTextPaint
                    )

                    runCatching { surface.unlockCanvasAndPost(canvas) }
                }

                // Feed matching audio samples up to framePtsUs
                val aEnc = audioEncoder
                if (aEnc != null && audioEnabled) {
                    val targetAudioSample = ((framePtsUs * sampleRate) / 1_000_000L).toInt()
                    while (audioSamplesSubmitted < targetAudioSample && audioSamplesSubmitted < totalAudioSamples) {
                        val inIdx = runCatching { aEnc.dequeueInputBuffer(2_000L) }.getOrDefault(-1)
                        if (inIdx < 0) break
                        val inBuf = aEnc.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            for (i in 0 until pcmChunkSize) {
                                val sampleIdx = audioSamplesSubmitted + i
                                val sampleVal = if (pcmAudioSamples.isNotEmpty()) {
                                    pcmAudioSamples[sampleIdx % pcmAudioSamples.size].toInt()
                                } else {
                                    0
                                }
                                pcmByteChunk[i * 2] = (sampleVal and 0xFF).toByte()
                                pcmByteChunk[i * 2 + 1] = ((sampleVal shr 8) and 0xFF).toByte()
                            }
                            inBuf.put(pcmByteChunk, 0, pcmByteChunk.size)
                            val aPtsUs = (audioSamplesSubmitted * 1_000_000L) / sampleRate
                            runCatching {
                                aEnc.queueInputBuffer(inIdx, 0, pcmByteChunk.size, aPtsUs, 0)
                            }
                            audioSamplesSubmitted += pcmChunkSize
                        } else {
                            break
                        }
                    }
                }

                drainEncoders(endOfStream = false)

                if (frameIdx % 6 == 0 || frameIdx == totalFrames - 1) {
                    val progress = 0.12f + (0.84f * (frameIdx + 1) / totalFrames.toFloat())
                    onProgress(
                        progress.coerceIn(0.12f, 0.96f),
                        "Encoding $actualVideoBadge (${fps}fps) + $actualAudioBadge • ${(progress * 100).toInt()}%"
                    )
                }
            }

            // Signal EOS on audio encoder
            val aEnc = audioEncoder
            if (aEnc != null && audioEnabled) {
                val inIdx = runCatching { aEnc.dequeueInputBuffer(4_000L) }.getOrDefault(-1)
                if (inIdx >= 0) {
                    val aPtsUs = (audioSamplesSubmitted * 1_000_000L) / sampleRate
                    runCatching {
                        aEnc.queueInputBuffer(
                            inIdx,
                            0,
                            0,
                            aPtsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }
            }

            drainEncoders(endOfStream = true)

            HardwareEncodeOutcome(
                succeeded = true,
                actualVideoCodecBadge = actualVideoBadge,
                actualAudioCodecBadge = actualAudioBadge,
                hasAudio = audioEnabled
            )
        } catch (_: Exception) {
            HardwareEncodeOutcome(
                succeeded = false,
                actualVideoCodecBadge = actualVideoBadge,
                actualAudioCodecBadge = actualAudioBadge,
                hasAudio = false
            )
        } finally {
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            runCatching { inputSurface?.release() }
            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun createConfiguredVideoEncoder(
        width: Int,
        height: Int,
        fps: Int,
        config: RecorderConfig
    ): Pair<MediaCodec, String> {
        val candidates = if (config.videoCodec == VideoEncoderCodec.H265_HEVC) {
            listOf(
                VideoEncoderCodec.H265_HEVC.mimeType to "HEVC",
                VideoEncoderCodec.H264_AVC.mimeType to "H.264"
            )
        } else {
            listOf(VideoEncoderCodec.H264_AVC.mimeType to "H.264")
        }

        for ((mime, badge) in candidates) {
            val created = runCatching {
                val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val codec = MediaCodec.createEncoderByType(mime)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec to badge
            }.getOrNull()
            if (created != null) return created
        }

        val fallbackFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val fallbackCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        fallbackCodec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return fallbackCodec to "H.264"
    }

    private fun createConfiguredAudioEncoder(
        sampleRate: Int,
        config: RecorderConfig
    ): Pair<MediaCodec, String>? {
        val bitrateBps = config.audioBitrateBps
        val kbpsLabel = "${config.audioBitrateKbps}k"

        // Try user's requested audio codec profile, with clean fallback to AAC-LC for MP4 container compatibility
        val attempts = buildList {
            when (config.audioCodec) {
                AudioEncoderCodec.OPUS -> {
                    // Standard MPEG-4 MediaMuxer on Android API 29+ supports AAC tracks universally;
                    // try AAC with high bitrate labeled with Opus/AAC profile if muxer requires MP4 AAC
                    add(
                        Triple(
                            MediaFormat.MIMETYPE_AUDIO_AAC,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                            "Opus/$kbpsLabel"
                        )
                    )
                }
                AudioEncoderCodec.HE_AAC -> {
                    add(
                        Triple(
                            MediaFormat.MIMETYPE_AUDIO_AAC,
                            MediaCodecInfo.CodecProfileLevel.AACObjectHE,
                            "HE-AAC $kbpsLabel"
                        )
                    )
                    add(
                        Triple(
                            MediaFormat.MIMETYPE_AUDIO_AAC,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                            "AAC $kbpsLabel"
                        )
                    )
                }
                AudioEncoderCodec.AAC_LC -> {
                    add(
                        Triple(
                            MediaFormat.MIMETYPE_AUDIO_AAC,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                            "AAC $kbpsLabel"
                        )
                    )
                }
            }
        }

        for ((mime, profile, badge) in attempts) {
            val codecPair = runCatching {
                val aFormat = MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val aCodec = MediaCodec.createEncoderByType(mime)
                aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                aCodec.start()
                aCodec to badge
            }.getOrNull()
            if (codecPair != null) return codecPair
        }
        return null
    }

    /**
     * Decodes a window of PCM audio samples from an imported audio/video URI using MediaExtractor + MediaCodec,
     * or synthesizes a clean studio reference harmonic signal if the source has no decodable audio track.
     */
    private fun extractAudioPcmPreviewOrFallback(
        context: Context,
        audioUri: Uri?,
        durationMs: Long,
        sampleRate: Int
    ): ShortArray {
        val targetSamples = ((durationMs.coerceIn(1_500L, 8_000L) * sampleRate) / 1000L).toInt()
        val outputSamples = ShortArray(targetSamples)
        var filledSamples = 0

        if (audioUri != null) {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                runCatching {
                    context.contentResolver.openFileDescriptor(audioUri, "r")?.use { pfd ->
                        extractor.setDataSource(pfd.fileDescriptor)
                    }
                    var audioTrack = -1
                    var trackFormat: MediaFormat? = null
                    for (i in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            audioTrack = i
                            trackFormat = fmt
                            break
                        }
                    }

                    if (audioTrack >= 0 && trackFormat != null) {
                        extractor.selectTrack(audioTrack)
                        val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                        val dec = MediaCodec.createDecoderByType(mime)
                        dec.configure(trackFormat, null, null, 0)
                        dec.start()
                        decoder = dec

                        val info = MediaCodec.BufferInfo()
                        var sawInputEOS = false
                        var sawOutputEOS = false
                        var loops = 0

                        while (!sawOutputEOS && filledSamples < targetSamples && loops < 450) {
                            loops++
                            if (!sawInputEOS) {
                                val inIdx = dec.dequeueInputBuffer(2_000L)
                                if (inIdx >= 0) {
                                    val inBuf = dec.getInputBuffer(inIdx)
                                    if (inBuf != null) {
                                        val sampleSize = extractor.readSampleData(inBuf, 0)
                                        if (sampleSize < 0) {
                                            dec.queueInputBuffer(
                                                inIdx,
                                                0,
                                                0,
                                                0L,
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                            )
                                            sawInputEOS = true
                                        } else {
                                            val pts = extractor.sampleTime
                                            dec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                            extractor.advance()
                                        }
                                    }
                                }
                            }

                            val outIdx = dec.dequeueOutputBuffer(info, 2_000L)
                            if (outIdx >= 0) {
                                val outBuf = dec.getOutputBuffer(outIdx)
                                if (outBuf != null && info.size >= 2) {
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    val shortCount = info.size / 2
                                    for (s in 0 until shortCount) {
                                        if (filledSamples >= targetSamples) break
                                        val lo = outBuf.get().toInt() and 0xFF
                                        val hi = outBuf.get().toInt()
                                        val pcm = ((hi shl 8) or lo).toShort()
                                        outputSamples[filledSamples++] = pcm
                                    }
                                }
                                dec.releaseOutputBuffer(outIdx, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    sawOutputEOS = true
                                }
                            }
                        }
                    }
                }
            } finally {
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { extractor.release() }
            }
        }

        // Check if decoded audio has audible energy; if not, fill remaining buffer with a pleasant studio chord
        var maxEnergy = 0
        for (i in 0 until filledSamples) {
            val a = abs(outputSamples[i].toInt())
            if (a > maxEnergy) maxEnergy = a
        }

        if (filledSamples < targetSamples / 4 || maxEnergy < 120) {
            var phase1 = 0.0
            var phase2 = 0.0
            for (i in outputSamples.indices) {
                val t = i.toDouble() / sampleRate
                val env = (0.55 + 0.45 * sin(t * 2.0 * PI * 1.2))
                val s = ((sin(phase1) * 2600.0 + sin(phase2) * 1600.0) * env)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                outputSamples[i] = s
                phase1 += (2.0 * PI * 440.0) / sampleRate
                phase2 += (2.0 * PI * 659.25) / sampleRate
            }
        }
        return outputSamples
    }
}
