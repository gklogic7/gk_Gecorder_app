# FloatRec Studio — Video & Audio Encoder + Media Import Transcoder

Add configurable **Video Encoder (`H.264 AVC` / `H.265 HEVC`)** and **Audio Encoder (`AAC-LC` / `HE-AAC` / `Opus`)** with **Audio Bitrate control (`96–320 kbps`)** directly inside the existing **Recorder Settings**, plus an **Import Audio & Video Encoder** workflow inside the **Saved Captures** tab while keeping all existing screen recording, floating HUD, multi-shot, and audio features intact.

## User Review & Critical Decisions

> [!IMPORTANT]
> All choices below reflect your confirmed preferences from the clarification step and preserve 100% of existing functionality.

- **Confirmed Decision 1 — Video & Audio Codec Selection**: Support **H.264 AVC** and **H.265 HEVC** for video encoding, and **AAC-LC**, **HE-AAC**, and **Opus** for audio encoding with configurable audio bitrates (`96 kbps`, `128 kbps`, `192 kbps`, `256 kbps`, `320 kbps`).
- **Confirmed Decision 2 — Placement in Existing UI**: Keep the 2-subtab layout (`Recorder` and `Saved Captures`) unchanged. Place the **Video & Audio Encoder + Audio Bitrate** controls inside the existing **Recorder Settings** card, and place the **Import Audio & Video Encoder** workflow inside the existing **Saved Captures** subtab.
- **Confirmed Decision 3 — Hardware Codec Fallback Safety**: When `H.265 HEVC` or `Opus` is selected on a device whose hardware `MediaCodec` / `MediaMuxer` only supports standard MP4 tracks, the encoder automatically negotiates the highest-supported hardware profile or falls back cleanly so recordings and imports never fail.

---

## 1. Overview & Core Concept

- **What It Does**:
  1. **Live Screen Recording Codec Control**: Lets users choose between **H.264 AVC** (universal compatibility) and **H.265 HEVC** (high efficiency, ~35% smaller file size), plus **AAC-LC**, **HE-AAC**, or **Opus** audio codecs and audio bitrate (`96–320 kbps`) right inside the Recorder Settings card.
  2. **Import Audio & Video Encoder in Saved Captures**: Lets users import external video files and/or external audio tracks from their device (or pick an existing saved capture) and encode/mux them using their chosen Video Codec (`H.264` / `HEVC`), Audio Codec (`AAC` / `Opus`), Audio Bitrate (`kbps`), Frame Rate (`24/30/60 FPS`), and Video Bitrate (`Mbps`).
- **Target Audience / Persona**: Creators, gamers, and power users who want fine-grained control over video/audio compression codecs for both live screen captures and imported media clips.
- **Key Value**: Combines one-tap entire-screen capture with a built-in hardware-accelerated codec selector and media import encoder in the same streamlined interface.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Selecting Video & Audio Encoders (`Recorder` Subtab)**:
   - Inside `CaptureSettingsCard`, alongside Audio Source, Frame Rate (`24/30/60 FPS`), Resolution (`Full`, `16:9`, `9:16`, `1:1`), and Video Bitrate (`Mbps`), users can select:
     - **VIDEO ENCODER**: `H.264 (AVC)` or `H.265 (HEVC)` with live file-size efficiency calculation.
     - **AUDIO ENCODER**: `AAC-LC`, `HE-AAC`, or `Opus`.
     - **AUDIO BITRATE**: `96k`, `128k`, `192k`, `256k`, or `320k` kbps chips.
   - All encoder selections are automatically persisted in DataStore and included when saving or applying custom presets in the **Save System** card.
2. **Importing & Encoding Video/Audio (`Saved Captures` Subtab)**:
   - At the top of the **Saved Captures** subtab, an **Import Audio & Video Encoder** panel provides:
     - **Import Video** button (zero-permission system picker for `video/*`).
     - **Import Audio** button (zero-permission system picker for `audio/*` to either transcode standalone audio or mux/replace audio onto the selected video).
     - **Encoder Target Summary & Quick Override**: Displays the active target encoder settings (`H.264`/`HEVC`, `24/30/60 FPS`, `Mbps`, `AAC`/`Opus` `@ kbps`) and an **Encode & Save to Captures** action button with a real-time progress bar.
   - Each existing video card in **Saved Captures** also includes a quick **Re-Encode** action to load that clip into the encoder.

### Visual Identity & Theme
- **Aesthetic Direction**: Preserves the tactile **Obsidian & Anodized Slate** broadcast studio aesthetic (`ObsidianBackground #0A0D12`, `AnodizedSlateSurface #131822`, `ElevatedHudSurface #1C2331`) with `TallyRecordingRed #FF3B30`, `PrecisionCyan #00E5FF`, `AmberSignal #FFB300`, and `EmeraldReady #00E676`.
- **Typography & Hierarchy**: `JetBrainsMonoFontFamily` for codec badges (`HEVC • 60 FPS • AAC 192k`), bitrates, and encoding progress percentages.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Hardware `MediaCodec` + `MediaExtractor` + `MediaMuxer` Transcoding Engine**
  - *Chosen Approach*: Implement a dedicated on-device media import & encoding engine that decodes/extracts imported video and audio streams and re-encodes/muxes them with the user's selected `VideoEncoderCodec`, `AudioEncoderCodec`, `audioBitrateKbps`, `frameRate`, and `bitrateMbps`.
  - *Why*: Runs 100% offline on-device with hardware acceleration, requires zero storage permissions by reading URI streams via `ContentResolver`, and outputs directly to the app's Saved Captures library and `MediaStore`.
- **Decision 2: Seamless Codec Integration in Live Screen Recorder**
  - *Chosen Approach*: Wire `VideoEncoderCodec` (`MIME_VIDEO_AVC = "video/avc"`, `MIME_VIDEO_HEVC = "video/hevc"`) and `AudioEncoderCodec` + `audioBitrateKbps` into both `MixedAvCaptureSession` and `MediaRecorder` in `ScreenRecorderService`, with automatic fallback if a device's hardware encoder rejects HEVC or high-profile parameters.
  - *Why*: Guarantees rock-solid recording reliability across all Android GPU/SoC vendors while delivering genuine HEVC and high-bitrate audio encoding where supported.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram
```
┌───────────────────────────────────────────────────────────────────────────┐
│                         MainActivity (Compose UI)                         │
│  ┌───────────────────────────────────┐ ┌────────────────────────────────┐ │
│  │        Recorder Subtab            │ │     Saved Captures Subtab      │ │
│  │  • WholeScreenGestureHubCard      │ │  • ImportAudioVideoEncoderCard │ │
│  │  • CaptureSettingsCard            │ │    - Pick Video / Pick Audio   │ │
│  │    - Audio Source + Live Check    │ │    - Codec & Bitrate Summary   │ │
│  │    - Video Codec (H.264 / HEVC)   │ │    - Live Encoding Progress    │ │
│  │    - Audio Codec (AAC / Opus)     │ │  • Filter Chips (All/Vid/SS)   │ │
│  │    - Audio Bitrate (96k–320k)     │ │  • RecordedClipCard List       │ │
│  │    - Frame Rate (24 / 30 / 60)    │ │    - Codec & Audio Badges      │ │
│  │    - Resolution & Mbps Slider     │ │    - Play / Re-Encode / Share  │ │
│  │  • SaveOptionsSystemCard          │ └───────────────┬────────────────┘ │
│  └─────────────────┬─────────────────┘                 │                  │
└────────────────────┼───────────────────────────────────┼──────────────────┘
                     ▼                                   ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                      RecorderViewModel & Repository                       │
│  • Persists VideoEncoderCodec, AudioEncoderCodec, audioBitrateKbps        │
│  • Manages ImportMediaState (selectedVideoUri, selectedAudioUri, status)  │
└────────────────────┬───────────────────────────────────┬──────────────────┘
                     ▼                                   ▼
┌──────────────────────────────────────┐ ┌──────────────────────────────────┐
│        ScreenRecorderService         │ │     MediaImportEncoderEngine     │
│  • MixedAvCaptureSession (H.264/HEVC │ │  • MediaExtractor + MediaCodec   │
│    + AAC/Opus @ 96k-320k kbps)       │ │  • Re-encodes video & audio or   │
│  • Automatic hardware codec fallback │ │    muxes imported audio + video  │
└──────────────────────────────────────┘ └──────────────────────────────────┘
```

### Data Model & State Updates
- **`VideoEncoderCodec`**:
  - `H264_AVC` (`label = "H.264 AVC"`, `shortBadge = "H.264"`, `mimeType = "video/avc"`, `efficiencyFactor = 1.0f`, `hint = "Universal Compatibility"`)
  - `H265_HEVC` (`label = "H.265 HEVC"`, `shortBadge = "HEVC"`, `mimeType = "video/hevc"`, `efficiencyFactor = 0.68f`, `hint = "High Efficiency • ~32% Smaller"`)
- **`AudioEncoderCodec`**:
  - `AAC_LC` (`label = "AAC-LC"`, `shortBadge = "AAC"`, `mimeType = "audio/mp4a-latm"`, `hint = "Standard Studio MP4 Audio"`)
  - `HE_AAC` (`label = "HE-AAC"`, `shortBadge = "HE-AAC"`, `mimeType = "audio/mp4a-latm"`, `hint = "High-Efficiency Voice & Media"`)
  - `OPUS` (`label = "Opus"`, `shortBadge = "Opus"`, `mimeType = "audio/opus"`, `hint = "Wideband Crisp Dynamic Audio"`)
- **`RecorderConfig` & `SavedRecorderPreset`**:
  - Add `videoCodec: VideoEncoderCodec = VideoEncoderCodec.H264_AVC`
  - Add `audioCodec: AudioEncoderCodec = AudioEncoderCodec.AAC_LC`
  - Add `audioBitrateKbps: Int = 192` (`96`, `128`, `192`, `256`, `320`)
- **`RecordedClip`**:
  - Add `videoCodecLabel: String = "H.264"` and `audioCodecLabel: String = "AAC 192k"` so saved and imported clips display their exact encoding specs.
- **`ImportEncoderUiState`**:
  - Tracks `importedVideoUri`, `importedVideoName`, `importedAudioUri`, `importedAudioName`, `isEncoding`, `encodeProgress` (`0f..1f`), and `statusMessage`.
