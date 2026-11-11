package com.example.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AudioSourceMode
import com.example.model.FrameRatePreset
import com.example.model.HudPillMode
import com.example.model.RecordedClip
import com.example.model.RecorderConfig
import com.example.model.ResolutionPreset
import com.example.model.SavedRecorderPreset
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.recorderDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "floatrec_studio_prefs"
)

class RecorderRepository(private val context: Context) {

    private object Keys {
        val RESOLUTION = stringPreferencesKey("resolution_aspect")
        val FRAME_RATE = stringPreferencesKey("frame_rate")
        val BITRATE_MBPS = floatPreferencesKey("bitrate_mbps")
        val AUDIO_SOURCE = stringPreferencesKey("audio_source_v2")
        val AUTO_RECORD_DURATION_SEC = intPreferencesKey("auto_record_duration_sec")
        val MULTI_SHOT_COUNT = intPreferencesKey("multi_shot_count")
        val MULTI_SHOT_GAP_SEC = intPreferencesKey("multi_shot_gap_sec")
        val COUNTDOWN_SECONDS = intPreferencesKey("countdown_seconds")
        val SHOW_FLOATING_HUD = booleanPreferencesKey("show_floating_hud")
        val SHOW_TOUCH_RETICLE = booleanPreferencesKey("show_touch_reticle")
        val HUD_PILL_MODE = stringPreferencesKey("hud_pill_mode")
        val SAVED_CLIPS_JSON = stringPreferencesKey("saved_clips_json")
        val SAVED_PRESETS_JSON = stringPreferencesKey("saved_presets_json")
    }

    val configFlow: Flow<RecorderConfig> = context.recorderDataStore.data.map { prefs ->
        RecorderConfig(
            resolution = runCatching {
                ResolutionPreset.valueOf(
                    prefs[Keys.RESOLUTION] ?: ResolutionPreset.FULL_SCREEN.name
                )
            }.getOrDefault(ResolutionPreset.FULL_SCREEN),
            frameRate = runCatching {
                FrameRatePreset.valueOf(prefs[Keys.FRAME_RATE] ?: FrameRatePreset.FPS_60.name)
            }.getOrDefault(FrameRatePreset.FPS_60),
            bitrateMbps = (prefs[Keys.BITRATE_MBPS] ?: 12.0f).coerceIn(1.0f, 24.0f),
            audioSource = runCatching {
                AudioSourceMode.valueOf(
                    prefs[Keys.AUDIO_SOURCE] ?: AudioSourceMode.INTERNAL_PLUS_MIC.name
                )
            }.getOrDefault(AudioSourceMode.INTERNAL_PLUS_MIC),
            autoRecordDurationSeconds = (prefs[Keys.AUTO_RECORD_DURATION_SEC] ?: 10).coerceIn(3, 300),
            multiShotCount = (prefs[Keys.MULTI_SHOT_COUNT] ?: 1).coerceIn(1, 20),
            multiShotGapSeconds = (prefs[Keys.MULTI_SHOT_GAP_SEC] ?: 3).coerceIn(0, 60),
            countdownSeconds = prefs[Keys.COUNTDOWN_SECONDS] ?: 0,
            showFloatingHud = prefs[Keys.SHOW_FLOATING_HUD] ?: true,
            showTouchReticle = prefs[Keys.SHOW_TOUCH_RETICLE] ?: false,
            hudPillMode = runCatching {
                HudPillMode.valueOf(prefs[Keys.HUD_PILL_MODE] ?: HudPillMode.COMPACT_ORB.name)
            }.getOrDefault(HudPillMode.COMPACT_ORB)
        )
    }

    val clipsFlow: Flow<List<RecordedClip>> = context.recorderDataStore.data.map { prefs ->
        val raw = prefs[Keys.SAVED_CLIPS_JSON] ?: "[]"
        parseClipsJson(raw)
    }

    val presetsFlow: Flow<List<SavedRecorderPreset>> = context.recorderDataStore.data.map { prefs ->
        val raw = prefs[Keys.SAVED_PRESETS_JSON]
        if (raw.isNullOrBlank()) {
            defaultStarterPresets()
        } else {
            parsePresetsJson(raw).ifEmpty { defaultStarterPresets() }
        }
    }

    suspend fun updateConfig(config: RecorderConfig) {
        context.recorderDataStore.edit { prefs ->
            prefs[Keys.RESOLUTION] = config.resolution.name
            prefs[Keys.FRAME_RATE] = config.frameRate.name
            prefs[Keys.BITRATE_MBPS] = config.bitrateMbps.coerceIn(1.0f, 24.0f)
            prefs[Keys.AUDIO_SOURCE] = config.audioSource.name
            prefs[Keys.AUTO_RECORD_DURATION_SEC] = config.autoRecordDurationSeconds.coerceIn(3, 300)
            prefs[Keys.MULTI_SHOT_COUNT] = config.multiShotCount.coerceIn(1, 20)
            prefs[Keys.MULTI_SHOT_GAP_SEC] = config.multiShotGapSeconds.coerceIn(0, 60)
            prefs[Keys.COUNTDOWN_SECONDS] = config.countdownSeconds
            prefs[Keys.SHOW_FLOATING_HUD] = config.showFloatingHud
            prefs[Keys.SHOW_TOUCH_RETICLE] = config.showTouchReticle
            prefs[Keys.HUD_PILL_MODE] = config.hudPillMode.name
        }
    }

    suspend fun saveCustomPreset(name: String, config: RecorderConfig): SavedRecorderPreset {
        val cleanName = name.trim().ifEmpty {
            "${config.resolution.shortBadge} ${config.audioSource.shortBadge}"
        }
        val newPreset = SavedRecorderPreset(
            id = System.currentTimeMillis().toString(),
            name = cleanName,
            resolution = config.resolution,
            bitrateMbps = config.bitrateMbps,
            audioSource = config.audioSource,
            autoRecordDurationSeconds = config.autoRecordDurationSeconds,
            multiShotCount = config.multiShotCount,
            multiShotGapSeconds = config.multiShotGapSeconds
        )
        context.recorderDataStore.edit { prefs ->
            val currentRaw = prefs[Keys.SAVED_PRESETS_JSON]
            val currentList = if (currentRaw.isNullOrBlank()) {
                defaultStarterPresets().toMutableList()
            } else {
                parsePresetsJson(currentRaw).ifEmpty { defaultStarterPresets() }.toMutableList()
            }
            currentList.removeAll { it.name.equals(cleanName, ignoreCase = true) }
            currentList.add(0, newPreset)
            prefs[Keys.SAVED_PRESETS_JSON] = encodePresetsJson(currentList.take(10))
        }
        return newPreset
    }

    suspend fun deletePreset(presetId: String) {
        context.recorderDataStore.edit { prefs ->
            val currentRaw = prefs[Keys.SAVED_PRESETS_JSON]
            val currentList = if (currentRaw.isNullOrBlank()) {
                defaultStarterPresets()
            } else {
                parsePresetsJson(currentRaw)
            }
            val updated = currentList.filterNot { it.id == presetId }
            prefs[Keys.SAVED_PRESETS_JSON] = encodePresetsJson(updated)
        }
    }

    suspend fun addRecordedClip(clip: RecordedClip) {
        context.recorderDataStore.edit { prefs ->
            val current = parseClipsJson(prefs[Keys.SAVED_CLIPS_JSON] ?: "[]").toMutableList()
            current.removeAll { it.id == clip.id }
            current.add(0, clip)
            prefs[Keys.SAVED_CLIPS_JSON] = encodeClipsJson(current)
        }
    }

    suspend fun renameClip(clipId: String, newTitle: String) {
        val cleanTitle = newTitle.trim().ifEmpty { return }
        context.recorderDataStore.edit { prefs ->
            val updated = parseClipsJson(prefs[Keys.SAVED_CLIPS_JSON] ?: "[]").map { clip ->
                if (clip.id == clipId) clip.copy(title = cleanTitle) else clip
            }
            prefs[Keys.SAVED_CLIPS_JSON] = encodeClipsJson(updated)
        }
    }

    suspend fun deleteClip(clip: RecordedClip) = withContext(Dispatchers.IO) {
        runCatching {
            if (clip.uriString.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(clip.uriString), null, null)
            }
            clip.filePath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
        context.recorderDataStore.edit { prefs ->
            val updated = parseClipsJson(prefs[Keys.SAVED_CLIPS_JSON] ?: "[]")
                .filterNot { it.id == clip.id }
            prefs[Keys.SAVED_CLIPS_JSON] = encodeClipsJson(updated)
        }
    }

    suspend fun syncWithMediaStore() = withContext(Dispatchers.IO) {
        val existing = clipsFlow.first().toMutableList()
        val existingUris = existing.map { it.uriString }.toSet()
        var changed = false

        runCatching {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT
            )
            val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("FloatRec_%")
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(collection, mediaId).toString()
                    if (contentUri !in existingUris) {
                        val name = cursor.getString(nameCol) ?: "FloatRec_$mediaId.mp4"
                        val duration = cursor.getLong(durCol).coerceAtLeast(1000L)
                        val size = cursor.getLong(sizeCol)
                        val dateAddedSec = cursor.getLong(dateCol)
                        val width = cursor.getInt(widthCol)
                        val height = cursor.getInt(heightCol)
                        val resLabel = when {
                            width == height && width > 0 -> "1:1"
                            width > height -> "16:9"
                            else -> "9:16"
                        }

                        existing.add(
                            RecordedClip(
                                id = mediaId.toString(),
                                title = name.removeSuffix(".mp4"),
                                uriString = contentUri,
                                filePath = null,
                                durationMillis = duration,
                                sizeBytes = size,
                                resolutionLabel = resLabel,
                                fps = 60,
                                bitrateMbps = 12,
                                createdAtMillis = dateAddedSec * 1000L,
                                hasAudio = true,
                                audioSourceLabel = "Internal + Mic",
                                isScreenshot = false
                            )
                        )
                        changed = true
                    }
                }
            }
        }

        if (changed) {
            val sorted = existing.sortedByDescending { it.createdAtMillis }
            context.recorderDataStore.edit { prefs ->
                prefs[Keys.SAVED_CLIPS_JSON] = encodeClipsJson(sorted)
            }
        }
    }

    private fun defaultStarterPresets(): List<SavedRecorderPreset> = listOf(
        SavedRecorderPreset(
            id = "preset_gaming_mix",
            name = "Full + Int & Mic",
            resolution = ResolutionPreset.FULL_SCREEN,
            bitrateMbps = 16.0f,
            audioSource = AudioSourceMode.INTERNAL_PLUS_MIC,
            autoRecordDurationSeconds = 15,
            multiShotCount = 1,
            multiShotGapSeconds = 3
        ),
        SavedRecorderPreset(
            id = "preset_multishot_burst",
            name = "3x Multi-Shot Burst",
            resolution = ResolutionPreset.FULL_SCREEN,
            bitrateMbps = 12.0f,
            audioSource = AudioSourceMode.INTERNAL_AUDIO,
            autoRecordDurationSeconds = 10,
            multiShotCount = 3,
            multiShotGapSeconds = 3
        ),
        SavedRecorderPreset(
            id = "preset_reels_mic",
            name = "9:16 Voiceover",
            resolution = ResolutionPreset.RATIO_9_16,
            bitrateMbps = 10.0f,
            audioSource = AudioSourceMode.MICROPHONE,
            autoRecordDurationSeconds = 30,
            multiShotCount = 1,
            multiShotGapSeconds = 2
        )
    )

    private fun parsePresetsJson(raw: String): List<SavedRecorderPreset> {
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        SavedRecorderPreset(
                            id = obj.optString("id", System.currentTimeMillis().toString()),
                            name = obj.optString("name", "Saved Preset"),
                            resolution = runCatching {
                                ResolutionPreset.valueOf(obj.optString("resolution", "FULL_SCREEN"))
                            }.getOrDefault(ResolutionPreset.FULL_SCREEN),
                            bitrateMbps = obj.optDouble("bitrateMbps", 12.0).toFloat().coerceIn(1f, 24f),
                            audioSource = runCatching {
                                AudioSourceMode.valueOf(obj.optString("audioSource", "INTERNAL_PLUS_MIC"))
                            }.getOrDefault(AudioSourceMode.INTERNAL_PLUS_MIC),
                            autoRecordDurationSeconds = obj.optInt("autoRecordDurationSeconds", 10).coerceIn(3, 300),
                            multiShotCount = obj.optInt("multiShotCount", 1).coerceIn(1, 20),
                            multiShotGapSeconds = obj.optInt("multiShotGapSeconds", 3).coerceIn(0, 60)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodePresetsJson(presets: List<SavedRecorderPreset>): String {
        val array = JSONArray()
        presets.forEach { preset ->
            val obj = JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("resolution", preset.resolution.name)
                put("bitrateMbps", preset.bitrateMbps.toDouble())
                put("audioSource", preset.audioSource.name)
                put("autoRecordDurationSeconds", preset.autoRecordDurationSeconds)
                put("multiShotCount", preset.multiShotCount)
                put("multiShotGapSeconds", preset.multiShotGapSeconds)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseClipsJson(raw: String): List<RecordedClip> {
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val hasAudio = obj.optBoolean("hasAudio", true)
                    add(
                        RecordedClip(
                            id = obj.optString("id"),
                            title = obj.optString("title", "FloatRec Capture"),
                            uriString = obj.optString("uriString"),
                            filePath = obj.optString("filePath").takeIf { it.isNotEmpty() },
                            durationMillis = obj.optLong("durationMillis", 0L),
                            sizeBytes = obj.optLong("sizeBytes", 0L),
                            resolutionLabel = obj.optString("resolutionLabel", "FULL"),
                            fps = obj.optInt("fps", 60),
                            bitrateMbps = obj.optInt("bitrateMbps", 12),
                            createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                            hasAudio = hasAudio,
                            audioSourceLabel = obj.optString(
                                "audioSourceLabel",
                                if (hasAudio) "Internal + Mic" else "No Audio"
                            ),
                            isScreenshot = obj.optBoolean("isScreenshot", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeClipsJson(clips: List<RecordedClip>): String {
        val array = JSONArray()
        clips.forEach { clip ->
            val obj = JSONObject().apply {
                put("id", clip.id)
                put("title", clip.title)
                put("uriString", clip.uriString)
                put("filePath", clip.filePath ?: "")
                put("durationMillis", clip.durationMillis)
                put("sizeBytes", clip.sizeBytes)
                put("resolutionLabel", clip.resolutionLabel)
                put("fps", clip.fps)
                put("bitrateMbps", clip.bitrateMbps)
                put("createdAtMillis", clip.createdAtMillis)
                put("hasAudio", clip.hasAudio)
                put("audioSourceLabel", clip.audioSourceLabel)
                put("isScreenshot", clip.isScreenshot)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
