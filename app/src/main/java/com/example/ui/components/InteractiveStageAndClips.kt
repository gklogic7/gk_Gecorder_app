package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.AudioEncoderCodec
import com.example.model.ImportMediaEncoderState
import com.example.model.RecordedClip
import com.example.model.RecorderConfig
import com.example.model.RecorderStatus
import com.example.model.VideoEncoderCodec
import com.example.model.formatFileSize
import com.example.model.formatFloatingPointTimecode
import com.example.service.LiveRecordingTelemetry
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.AnodizedSlateSurface
import com.example.ui.theme.CrispStudioWhite
import com.example.ui.theme.ElevatedHudSurface
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.GlassBorderSubtle
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MutedAluminum
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.PrecisionCyan
import com.example.ui.theme.TallyRecordingRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ImportAudioVideoEncoderCard(
    config: RecorderConfig,
    importState: ImportMediaEncoderState,
    onImportVideoClick: () -> Unit,
    onImportAudioClick: () -> Unit,
    onClearImportedMedia: () -> Unit,
    onSelectVideoCodec: (VideoEncoderCodec) -> Unit,
    onSelectAudioCodec: (AudioEncoderCodec) -> Unit,
    onSelectAudioBitrateKbps: (Int) -> Unit,
    onStartEncodeClick: () -> Unit
) {
    val audioBitratePresets = listOf(96, 128, 192, 256, 320)
    val animatedProgress by animateFloatAsState(
        targetValue = importState.encodeProgress.coerceIn(0f, 1f),
        label = "encode_progress_anim"
    )
    val hasMediaSelected =
        importState.selectedVideoUri != null || importState.selectedAudioUri != null

    Surface(
        color = AnodizedSlateSurface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (importState.isEncoding) AmberSignal else PrecisionCyan.copy(alpha = 0.45f),
                shape = RoundedCornerShape(22.dp)
            )
            .testTag("import_audio_video_encoder_card")
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
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = PrecisionCyan,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "IMPORT AUDIO & VIDEO ENCODER",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CrispStudioWhite
                    )
                }

                Text(
                    text = "${config.videoCodec.shortBadge} • ${config.audioCodec.shortBadge} ${config.audioBitrateKbps}k",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = EmeraldReady
                )
            }

            // Import Video & Import Audio Buttons Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onImportVideoClick,
                    enabled = !importState.isEncoding,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("import_video_file_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoFile,
                        contentDescription = null,
                        tint = PrecisionCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (importState.selectedVideoName != null) "Change Video" else "Import Video",
                        color = CrispStudioWhite,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onImportAudioClick,
                    enabled = !importState.isEncoding,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("import_audio_file_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = AmberSignal,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (importState.selectedAudioName != null) "Change Audio" else "Import Audio",
                        color = CrispStudioWhite,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Selected Media Summary Pill
            if (hasMediaSelected) {
                Surface(
                    color = ObsidianBackground.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, GlassBorderSubtle, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            importState.selectedVideoName?.let { vName ->
                                Text(
                                    text = "Video: $vName (${formatFloatingPointTimecode(importState.selectedVideoDurationMs)})",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 10.5.sp,
                                    color = PrecisionCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            importState.selectedAudioName?.let { aName ->
                                Text(
                                    text = "Audio: $aName (${formatFloatingPointTimecode(importState.selectedAudioDurationMs)})",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 10.5.sp,
                                    color = AmberSignal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (!importState.isEncoding) {
                            IconButton(
                                onClick = onClearImportedMedia,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear Imported Media",
                                    tint = MutedAluminum,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Inline Video Codec + Audio Codec + Audio Bitrate selectors
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "VIDEO:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MutedAluminum,
                    fontSize = 10.sp
                )
                VideoEncoderCodec.entries.forEach { vCodec ->
                    val selected = config.videoCodec == vCodec
                    val bg by animateColorAsState(
                        targetValue = if (selected) PrecisionCyan.copy(alpha = 0.24f) else ElevatedHudSurface,
                        label = "import_vcodec_bg"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(bg)
                            .border(
                                width = 1.dp,
                                color = if (selected) PrecisionCyan else GlassBorderSubtle,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable(enabled = !importState.isEncoding) {
                                onSelectVideoCodec(vCodec)
                            }
                            .padding(vertical = 6.dp, horizontal = 6.dp)
                            .testTag("import_video_codec_${vCodec.name.lowercase(Locale.US)}")
                    ) {
                        Text(
                            text = vCodec.label,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            color = if (selected) PrecisionCyan else CrispStudioWhite
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "AUDIO:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MutedAluminum,
                    fontSize = 10.sp
                )
                AudioEncoderCodec.entries.forEach { aCodec ->
                    val selected = config.audioCodec == aCodec
                    val bg by animateColorAsState(
                        targetValue = if (selected) EmeraldReady.copy(alpha = 0.24f) else ElevatedHudSurface,
                        label = "import_acodec_bg"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(bg)
                            .border(
                                width = 1.dp,
                                color = if (selected) EmeraldReady else GlassBorderSubtle,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable(enabled = !importState.isEncoding) {
                                onSelectAudioCodec(aCodec)
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                            .testTag("import_audio_codec_${aCodec.name.lowercase(Locale.US)}")
                    ) {
                        Text(
                            text = aCodec.label,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (selected) EmeraldReady else CrispStudioWhite
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "KBPS:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MutedAluminum,
                    fontSize = 10.sp
                )
                audioBitratePresets.forEach { kbps ->
                    val selected = config.audioBitrateKbps == kbps
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) AmberSignal else ElevatedHudSurface)
                            .clickable(enabled = !importState.isEncoding) {
                                onSelectAudioBitrateKbps(kbps)
                            }
                            .padding(vertical = 5.dp)
                            .testTag("import_audio_kbps_$kbps")
                    ) {
                        Text(
                            text = "${kbps}k",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (selected) ObsidianBackground else CrispStudioWhite
                        )
                    }
                }
            }

            if (importState.isEncoding || importState.encodeProgress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ElevatedHudSurface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (importState.isEncoding) AmberSignal else EmeraldReady)
                    )
                }
            }

            Text(
                text = importState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 11.sp,
                color = if (importState.isEncoding) AmberSignal else MutedAluminum,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Button(
                onClick = onStartEncodeClick,
                enabled = !importState.isEncoding && hasMediaSelected,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrecisionCyan,
                    contentColor = ObsidianBackground,
                    disabledContainerColor = ElevatedHudSurface,
                    disabledContentColor = MutedAluminum
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .testTag("encode_imported_media_button")
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (importState.isEncoding) {
                        "Encoding ${config.videoCodec.shortBadge} + ${config.audioCodec.shortBadge}..."
                    } else {
                        "Encode & Save (${config.videoCodec.shortBadge} • ${config.frameRate.shortBadge} • ${config.audioCodec.shortBadge} ${config.audioBitrateKbps}k)"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun RecordedClipCard(
    clip: RecordedClip,
    onPlay: () -> Unit,
    onReEncode: (() -> Unit)? = null,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var thumbnailBitmap by remember(clip.uriString) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(clip.uriString, clip.filePath, clip.isScreenshot) {
        thumbnailBitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (clip.isScreenshot) {
                    if (!clip.filePath.isNullOrEmpty()) {
                        BitmapFactory.decodeFile(clip.filePath)
                    } else {
                        context.contentResolver.openInputStream(Uri.parse(clip.uriString))?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (!clip.filePath.isNullOrEmpty()) {
                            retriever.setDataSource(clip.filePath)
                        } else {
                            retriever.setDataSource(context, Uri.parse(clip.uriString))
                        }
                        retriever.getFrameAtTime(200_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        runCatching { retriever.release() }
                    }
                }
            }.getOrNull()
        }
    }

    val formattedDate = remember(clip.createdAtMillis) {
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(clip.createdAtMillis))
    }

    Surface(
        color = AnodizedSlateSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onPlay)
            .border(1.dp, GlassBorderSubtle, RoundedCornerShape(20.dp))
            .testTag("clip_card_${clip.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 88.dp, height = 64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ElevatedHudSurface)
            ) {
                val bmp = thumbnailBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = clip.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (clip.isScreenshot) Icons.Default.Image else Icons.Default.MovieCreation,
                        contentDescription = null,
                        tint = MutedAluminum,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(ObsidianBackground.copy(alpha = 0.78f))
                        .border(
                            1.dp,
                            if (clip.isScreenshot) PrecisionCyan else TallyRecordingRed,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (clip.isScreenshot) Icons.Default.Image else Icons.Default.PlayArrow,
                        contentDescription = if (clip.isScreenshot) "View Screenshot" else "Play Clip",
                        tint = CrispStudioWhite,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = clip.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = CrispStudioWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (clip.isScreenshot) {
                            "SCREENSHOT (SS)"
                        } else {
                            formatFloatingPointTimecode(clip.durationMillis)
                        },
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (clip.isScreenshot) PrecisionCyan else TallyRecordingRed
                    )
                    Text(text = "•", color = MutedAluminum)
                    Text(
                        text = formatFileSize(clip.sizeBytes),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = CrispStudioWhite
                    )
                    if (!clip.isScreenshot) {
                        Text(text = "•", color = MutedAluminum)
                        Text(
                            text = clip.videoCodecLabel,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = AmberSignal
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (!clip.isScreenshot) {
                        Icon(
                            imageVector = if (clip.hasAudio) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = null,
                            tint = if (clip.hasAudio) EmeraldReady else MutedAluminum,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = clip.audioCodecLabel,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = PrecisionCyan,
                            fontSize = 10.sp
                        )
                        Text(text = "•", color = MutedAluminum, fontSize = 10.sp)
                        Text(
                            text = "${clip.fps} FPS",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldReady
                        )
                        Text(text = "•", color = MutedAluminum, fontSize = 10.sp)
                    }
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedAluminum,
                        fontSize = 10.5.sp,
                        maxLines = 1
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!clip.isScreenshot && onReEncode != null) {
                    IconButton(
                        onClick = onReEncode,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("reencode_clip_${clip.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = "Load into Audio/Video Encoder",
                            tint = AmberSignal,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                IconButton(onClick = onRename, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Rename",
                        tint = MutedAluminum,
                        modifier = Modifier.size(17.dp)
                    )
                }
                IconButton(onClick = onShare, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = PrecisionCyan,
                        modifier = Modifier.size(17.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = TallyRecordingRed,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ClipPlaybackDialog(
    clip: RecordedClip,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var screenshotBitmap by remember(clip.uriString) { mutableStateOf<Bitmap?>(null) }
    var isPlaybackMuted by remember(clip.uriString) { mutableStateOf(false) }
    var activeMediaPlayer by remember(clip.uriString) {
        mutableStateOf<android.media.MediaPlayer?>(null)
    }

    LaunchedEffect(clip.uriString, clip.filePath, clip.isScreenshot) {
        if (clip.isScreenshot) {
            screenshotBitmap = withContext(Dispatchers.IO) {
                runCatching {
                    if (!clip.filePath.isNullOrEmpty()) {
                        BitmapFactory.decodeFile(clip.filePath)
                    } else {
                        context.contentResolver.openInputStream(Uri.parse(clip.uriString))?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                }.getOrNull()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = ObsidianBackground,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .border(1.dp, GlassBorderSubtle, RoundedCornerShape(28.dp))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = clip.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = CrispStudioWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (clip.isScreenshot) {
                                "SCREENSHOT PNG • ${formatFileSize(clip.sizeBytes)}"
                            } else {
                                "${formatFloatingPointTimecode(clip.durationMillis)} • ${clip.videoCodecLabel} • ${clip.fps} FPS • ${clip.audioCodecLabel} • ${formatFileSize(clip.sizeBytes)}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = PrecisionCyan
                        )
                    }
                    if (!clip.isScreenshot && clip.hasAudio) {
                        IconButton(
                            onClick = {
                                isPlaybackMuted = !isPlaybackMuted
                                val vol = if (isPlaybackMuted) 0f else 1f
                                runCatching { activeMediaPlayer?.setVolume(vol, vol) }
                            }
                        ) {
                            Icon(
                                imageVector = if (isPlaybackMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = if (isPlaybackMuted) "Unmute Audio" else "Mute Audio",
                                tint = if (isPlaybackMuted) AmberSignal else EmeraldReady
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Preview",
                            tint = CrispStudioWhite
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(AnodizedSlateSurface)
                ) {
                    if (clip.isScreenshot) {
                        val bmp = screenshotBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = clip.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MutedAluminum,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    val mediaController = MediaController(ctx)
                                    mediaController.setAnchorView(this)
                                    setMediaController(mediaController)
                                    if (!clip.filePath.isNullOrEmpty()) {
                                        setVideoPath(clip.filePath)
                                    } else {
                                        setVideoURI(Uri.parse(clip.uriString))
                                    }
                                    setOnPreparedListener { mp ->
                                        activeMediaPlayer = mp
                                        mp.isLooping = true
                                        val vol = if (isPlaybackMuted) 0f else 1.0f
                                        runCatching { mp.setVolume(vol, vol) }
                                        start()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onShare,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElevatedHudSurface,
                            contentColor = CrispStudioWhite
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (clip.isScreenshot) "Share PNG" else "Share MP4")
                    }

                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TallyRecordingRed.copy(alpha = 0.2f),
                            contentColor = TallyRecordingRed
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
fun RenameClipDialog(
    clip: RecordedClip,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(clip.id) { mutableStateOf(clip.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AnodizedSlateSurface,
        titleContentColor = CrispStudioWhite,
        textContentColor = CrispStudioWhite,
        title = { Text("Rename Capture") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Title") }
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColors(containerColor = TallyRecordingRed)
            ) {
                Text("Save")
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
fun PipCompactHudView(
    telemetry: LiveRecordingTelemetry,
    onStartCaptureClick: () -> Unit,
    onPauseResumeClick: () -> Unit,
    onStopCaptureClick: () -> Unit
) {
    val isActive =
        telemetry.status == RecorderStatus.RECORDING || telemetry.status == RecorderStatus.PAUSED
    val statusColor = when (telemetry.status) {
        RecorderStatus.RECORDING -> TallyRecordingRed
        RecorderStatus.PAUSED -> AmberSignal
        RecorderStatus.COUNTDOWN -> AmberSignal
        RecorderStatus.STANDBY -> EmeraldReady
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .border(2.dp, statusColor, RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = telemetry.status.name,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = MutedAluminum
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatFloatingPointTimecode(telemetry.elapsedMillis),
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = CrispStudioWhite
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isActive) {
                    IconButton(
                        onClick = onPauseResumeClick,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AmberSignal.copy(alpha = 0.25f))
                    ) {
                        Icon(
                            imageVector = if (telemetry.status == RecorderStatus.PAUSED) {
                                Icons.Default.PlayArrow
                            } else {
                                Icons.Default.Pause
                            },
                            contentDescription = "Pause or Resume",
                            tint = AmberSignal,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onStopCaptureClick,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(TallyRecordingRed)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = CrispStudioWhite,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onStartCaptureClick,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(TallyRecordingRed)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = "Record",
                            tint = CrispStudioWhite,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

fun shareRecordedClip(context: Context, clip: RecordedClip) {
    runCatching {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = if (clip.isScreenshot) "image/png" else "video/mp4"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(clip.uriString))
            putExtra(Intent.EXTRA_TITLE, clip.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(
                shareIntent,
                if (clip.isScreenshot) "Share Screenshot" else "Share Screen Recording"
            )
        )
    }
}
