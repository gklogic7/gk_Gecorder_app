package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.R
import com.example.model.GestureIconState
import com.example.model.RecorderStatus
import com.example.model.formatFloatingPointTimecode
import kotlin.math.abs

/**
 * Manages the system-wide floating circular button over other apps
 * using WindowManager (TYPE_APPLICATION_OVERLAY).
 *
 * Gesture Mapping with Custom SVG Vector Icons:
 * - 1x Single Tap: Start Recording / Tap again to Stop (ic_gesture_single_tap)
 * - 2x Double Tap: Instant Screenshot / SS (ic_gesture_double_tap_ss)
 * - Long Press: Auto-record for configured duration (ic_gesture_long_press_timer)
 * - Drag Down: Close App target zone (ic_gesture_drag_close)
 */
class FloatingOverlayViewManager(
    private val context: Context,
    private val onSingleTapRecordOrStop: () -> Unit,
    private val onDoubleTapScreenshot: () -> Unit,
    private val onLongPressAutoRecord: () -> Unit,
    private val onCloseSessionAndApp: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootContainer: FrameLayout? = null
    private var circleLayoutView: LinearLayout? = null
    private var gestureSvgIconView: ImageView? = null
    private var miniTimerText: TextView? = null

    private var bottomCloseZoneView: LinearLayout? = null
    private var bottomCloseZoneLabel: TextView? = null
    private var bottomCloseParams: WindowManager.LayoutParams? = null
    private var isCloseZoneAttached: Boolean = false

    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAttached: Boolean = false

    fun canShowSystemOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isAttached || !canShowSystemOverlay()) return

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Bottom Drag-to-Close Target Zone with custom SVG icon
        val closeZoneLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#E6151821"))
                setStroke(dp(2), Color.parseColor("#FF3B30"))
            }
            elevation = dp(12).toFloat()
        }
        val closeIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_gesture_drag_close)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(8)
            }
        }
        val closeLabel = TextView(context).apply {
            text = "Drag Here to Close App"
            setTextColor(Color.parseColor("#F4F6FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        closeZoneLayout.addView(closeIcon)
        closeZoneLayout.addView(closeLabel)

        val closeParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(42)
        }
        bottomCloseZoneView = closeZoneLayout
        bottomCloseZoneLabel = closeLabel
        bottomCloseParams = closeParams

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - dp(74)).coerceAtLeast(dp(12))
            y = (screenHeight * 0.25f).toInt()
        }
        layoutParams = params

        val container = FrameLayout(context)

        // Minimal Floating Circular Gesture Button with SVG Icon + Live Timer
        val circleSize = dp(60)
        val circleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(circleSize, circleSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EB151821"))
                setStroke(dp(2), Color.parseColor("#FF3B30"))
            }
            elevation = dp(12).toFloat()
        }

        val svgIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_gesture_single_tap)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }

        val miniTimer = TextView(context).apply {
            text = "REC"
            setTextColor(Color.parseColor("#F4F6FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }

        circleLayout.addView(svgIcon)
        circleLayout.addView(miniTimer)
        container.addView(circleLayout)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = dp(10)

        fun isInsideBottomCloseZone(rawY: Float, totalHeight: Int): Boolean {
            return rawY >= totalHeight * 0.78f
        }

        fun updateCloseZoneHighlight(inCloseZone: Boolean) {
            bottomCloseZoneView?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(if (inCloseZone) Color.parseColor("#FF3B30") else Color.parseColor("#E6151821"))
                setStroke(dp(2), Color.parseColor("#FF3B30"))
            }
            bottomCloseZoneLabel?.text = if (inCloseZone) {
                "Release to Close App"
            } else {
                "Drag Down Here to Close App"
            }
        }

        val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!isDragging) {
                        onSingleTapRecordOrStop()
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!isDragging) {
                        onDoubleTapScreenshot()
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (!isDragging) {
                        onLongPressAutoRecord()
                    }
                }
            },
            Handler(Looper.getMainLooper())
        )

        val touchListener = View.OnTouchListener { _, event ->
            val currentParams = layoutParams ?: return@OnTouchListener false
            val height = context.resources.displayMetrics.heightPixels
            val width = context.resources.displayMetrics.widthPixels

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = currentParams.x
                    initialY = currentParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                        bottomCloseZoneView?.visibility = View.VISIBLE
                    }
                    if (isDragging) {
                        currentParams.x =
                            (initialX + dx).coerceIn(0, (width - container.width).coerceAtLeast(0))
                        currentParams.y =
                            (initialY + dy).coerceIn(dp(24), (height - dp(64)).coerceAtLeast(dp(24)))
                        runCatching { windowManager.updateViewLayout(container, currentParams) }

                        val inCloseZone = isInsideBottomCloseZone(event.rawY, height)
                        updateCloseZoneHighlight(inCloseZone)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    bottomCloseZoneView?.visibility = View.GONE
                    if (isDragging) {
                        val droppedInCloseZone = isInsideBottomCloseZone(event.rawY, height)
                        if (droppedInCloseZone) {
                            onCloseSessionAndApp()
                            return@OnTouchListener true
                        }
                        val mid = width / 2
                        currentParams.x = if (currentParams.x + container.width / 2 < mid) {
                            dp(10)
                        } else {
                            (width - container.width - dp(10)).coerceAtLeast(dp(10))
                        }
                        runCatching { windowManager.updateViewLayout(container, currentParams) }
                        return@OnTouchListener true
                    }
                }
            }
            gestureDetector.onTouchEvent(event)
            true
        }

        circleLayout.setOnTouchListener(touchListener)

        circleLayoutView = circleLayout
        gestureSvgIconView = svgIcon
        miniTimerText = miniTimer
        rootContainer = container

        runCatching {
            windowManager.addView(closeZoneLayout, closeParams)
            isCloseZoneAttached = true
        }
        runCatching {
            windowManager.addView(container, params)
            isAttached = true
            ScreenRecorderStateHolder.updateTelemetry {
                it.copy(isSystemOverlayShowing = true)
            }
        }
    }

    fun updateTelemetry(telemetry: LiveRecordingTelemetry) {
        if (!isAttached) return
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val isRecording = telemetry.status == RecorderStatus.RECORDING
        val isTimedAuto = telemetry.autoStopTargetMillis > 0L && isRecording

        val strokeColor = when (telemetry.gestureIconState) {
            GestureIconState.DOUBLE_TAP_SCREENSHOT -> Color.parseColor("#00E5FF")
            GestureIconState.LONG_PRESS_TIMED -> Color.parseColor("#FF9F0A")
            GestureIconState.SINGLE_TAP_RECORDING -> Color.parseColor("#FF3B30")
            GestureIconState.SINGLE_TAP_IDLE -> Color.parseColor("#2B3245")
        }

        val iconRes = when (telemetry.gestureIconState) {
            GestureIconState.DOUBLE_TAP_SCREENSHOT -> R.drawable.ic_gesture_double_tap_ss
            GestureIconState.LONG_PRESS_TIMED -> R.drawable.ic_gesture_long_press_timer
            GestureIconState.SINGLE_TAP_RECORDING,
            GestureIconState.SINGLE_TAP_IDLE -> R.drawable.ic_gesture_single_tap
        }

        gestureSvgIconView?.setImageResource(iconRes)

        circleLayoutView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#EB151821"))
            setStroke(dp(2), strokeColor)
        }

        val displayMillis = when {
            telemetry.isMultiShotGap -> telemetry.gapRemainingMillis
            isTimedAuto -> (telemetry.autoStopTargetMillis - telemetry.elapsedMillis).coerceAtLeast(0L)
            else -> telemetry.elapsedMillis
        }
        val formattedTime = formatFloatingPointTimecode(displayMillis)

        miniTimerText?.text = when {
            telemetry.gestureIconState == GestureIconState.DOUBLE_TAP_SCREENSHOT -> "SS!"
            telemetry.isMultiShotGap -> "G:${(telemetry.gapRemainingMillis / 1000L) + 1}s"
            isRecording && telemetry.totalShots > 1 ->
                "${telemetry.currentShotIndex}/${telemetry.totalShots}"
            isRecording -> formattedTime.takeLast(4)
            else -> "TAP"
        }
    }

    fun hide() {
        if (isCloseZoneAttached) {
            bottomCloseZoneView?.let { zone ->
                runCatching { windowManager.removeView(zone) }
            }
            bottomCloseZoneView = null
            isCloseZoneAttached = false
        }
        if (!isAttached) return
        rootContainer?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootContainer = null
        isAttached = false
        ScreenRecorderStateHolder.updateTelemetry {
            it.copy(isSystemOverlayShowing = false)
        }
    }
}
