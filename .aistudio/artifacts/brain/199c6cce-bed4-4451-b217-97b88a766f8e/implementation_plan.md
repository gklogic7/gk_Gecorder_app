# Simple Floating Point Screen Recorder

Streamline the application into a fast, lightweight single-screen recorder featuring a one-time permission setup card, persistent screen-capture session reuse so you can record multiple clips without repeated prompts, and a minimal floating circular button over other apps that expands on tap.

## User Review & Critical Decisions

> [!IMPORTANT]
> The heavy multi-tab studio interface (oscilloscope test stage, bottom navigation bar, and multi-card encoder matrix) will be removed and replaced with a clean, single-screen layout focused on instant floating-overlay recording and your saved clips.

- **Confirmed Decision — Minimal Floating Circular Button**: The overlay appears over other apps as a compact, draggable circular button showing a live recording dot and timer, expanding into a control bar (`Record/Stop`, `Pause/Resume`, `Minimize`, `Exit`) only when tapped.
- **Confirmed Decision — Single One-Time Setup Card**: On first open, a single streamlined setup card guides you through granting overlay and audio/notification permissions once and arming the screen capture session.
- **Recommended Default — Single-Screen Home Layout**: Once the floating overlay is active, the main screen displays a clean status card with a one-tap "Minimize App & Record Other Apps" button alongside your saved `.mp4` recordings list.

---

## 1. Overview & Core Concept

- **What It Does**: Provides a lightweight floating screen recorder that stays out of your way over any Android app as a small draggable circle, expanding on tap to start, pause, or stop recordings with sub-second (`00:00.0`) floating-point timer accuracy.
- **Target Audience / Persona**: Users who want a zero-clutter, lightweight screen recorder that can be authorized once on startup and used continuously over other apps without heavy menus or repeated permission dialogs.
- **Key Value**: Eliminates UI bloat and repetitive system prompts by keeping the authorized capture projection warm in the background service while providing a responsive floating circular overlay.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **One-Time Startup Setup**: On first launch, a single setup card checks whether Display Over Other Apps, Microphone, and Screen Capture session permissions are active. One tap walks through any missing permission once and immediately launches the floating circular overlay.
  2. **Floating Circular Overlay Over Other Apps**:
     - **Collapsed State (Default)**: A small `52dp` translucent circular button anchored to the screen edge (draggable anywhere with magnetic edge-snapping). Displays a red recording lens when idle or a pulsing indicator + sub-second `.0` timer while recording.
     - **Expanded State (On Tap)**: Tapping the circle smoothly expands it into a compact horizontal pill with `Record / Stop`, `Pause / Resume`, `Audio Mic Toggle`, and `Collapse` actions.
  3. **Multi-Clip Recording Without Re-Prompting**: Stopping a recording finalizes the `.mp4` video to `MediaStore` while keeping the `MediaProjection` token alive in the foreground service, allowing the next recording to start immediately on tap with zero permission popups.
  4. **Instant Clip Review**: Opening the app shows your recorded clips on the same single screen with one-tap playback, share, rename, and delete.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Clean, minimal dark utility with high-contrast recording indicators and zero visual clutter.
  - *Color Palette & Mood*:
    - `--background`: `#0C0D12` (Deep Matte Obsidian)
    - `--surface`: `#151821` (Clean Slate Card Surface)
    - `--primary`: `#FF3B30` (Recording Tally Red)
    - `--secondary`: `#FF9F0A` (Amber Pause Signal)
    - `--accent`: `#30D158` (Emerald Ready Indicator)
  - *Typography & Hierarchy*: Crisp geometric headers (`Space Grotesk`), clean readable labels (`Inter`), and tabular monospace numerals (`JetBrains Mono`) for jitter-free `00:00.0` floating-point timers.
  - *Component Styling & Layout*: Single vertical column layout with generous spacing, a single top action card, and a scrollable saved recordings list underneath.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Persistent `MediaProjection` Session Reuse**
  - *Chosen Approach*: Keep the `MediaProjection` token alive inside the foreground service after a clip finishes recording, tearing down only the `MediaRecorder` and `VirtualDisplay` per clip until the user explicitly closes the floating overlay.
  - *Why*: Android requires a system token for screen capture, but allows a single authorized `MediaProjection` session in a foreground service to record multiple consecutive clips without showing the system confirmation dialog again.
  - *Alternatives Considered*: Stopping the entire service after every clip, which forces the user to re-approve the system screen-capture dialog before every recording.

- **Decision 2: System Overlay Window + In-App / PiP Fallback**
  - *Chosen Approach*: Use a lightweight `WindowManager` system overlay (`TYPE_APPLICATION_OVERLAY`) hosted directly by the Foreground Service so the circular button floats over all apps, paired with an automatic in-app / Picture-in-Picture fallback if the user chooses not to enable system overlays.
  - *Why*: Delivers a true floating button over external apps while remaining resilient on all Android devices.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       Single-Screen Minimal UI                          │
│  ┌───────────────────────────────┐   ┌───────────────────────────────┐  │
│  │  One-Time Setup & Status Card │   │     Saved Recordings List     │  │
│  │  • Grant Permissions Once     │   │  • Video Thumbnail & Duration │  │
│  │  • Toggle Floating Circle     │   │  • In-App MP4 Player Dialog   │  │
│  │  • Minimize to Home Action    │   │  • Share / Rename / Delete    │  │
│  └───────────────┬───────────────┘   └───────────────▲───────────────┘  │
└──────────────────┼───────────────────────────────────┼──────────────────┘
                   │                                   │
                   ▼                                   │
┌─────────────────────────────────────────────────────────────────────────┐
│                Persistent ScreenRecorderService (FGS)                   │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ Warm MediaProjection Session (Authorized Once, Reused Per Clip)   │  │
│  │  ├─► VirtualDisplay + MediaRecorder (Created/Stopped Per Clip)    │  │
│  │  └─► Scoped MediaStore MP4 Writer (Movies/FloatRec)               │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                   │                                     │
│                                   ▼                                     │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │             Floating Circular Overlay WindowManager               │  │
│  │  [ (●) 00:12.4 ] ──(Tap)──► [ (●) 00:12.4 │ ⏸ Pause │ ■ Stop │ × ]│  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

- **Data Model & State**:
  - `SessionState`: Tracks whether one-time permissions (`overlayGranted`, `audioGranted`, `projectionArmed`) are active alongside live recording status (`STANDBY`, `RECORDING`, `PAUSED`) and floating-point elapsed time (`elapsedMillis`).
  - `RecorderRepository`: Persists user preferences (mic toggle, one-time setup state) and indexes recorded `.mp4` clips in `MediaStore`.

- **Interactive Component & State Mapping**:
  - **One-Time Setup Card Button**: Checks `Settings.canDrawOverlays`, `RECORD_AUDIO`, and `POST_NOTIFICATIONS`. Requests only ungranted permissions once, launches `createScreenCaptureIntent()` once to arm the foreground service, and transitions the card to `Floating Overlay Active`.
  - **Floating Circular Overlay Button**:
    - *Drag Gesture*: Updates window `(X, Y)` layout parameters in real time and snaps horizontally to the nearest screen edge on release.
    - *Single Tap (Collapsed)*: Expands the circular button into the horizontal control bar.
    - *Record / Stop Action*: Starts or stops `MediaRecorder` using the warm `MediaProjection` session without re-prompting, then collapses back to the minimal indicator.
    - *Pause / Resume Action*: Pauses or resumes the active `MediaRecorder` stream and updates the sub-second timer state.
