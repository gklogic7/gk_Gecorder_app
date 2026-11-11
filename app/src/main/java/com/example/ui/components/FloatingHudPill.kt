package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.GestureIconState
import com.example.model.RecorderConfig
import com.example.model.RecorderStatus
import com.example.model.formatFloatingPointTimecode
import com.example.service.LiveRecordingTelemetry
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CrispStudioWhite
import com.example.ui.theme.ElevatedHudSurface
import com.example.ui.theme.GlassBorderSubtle
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.PrecisionCyan
import com.example.ui.theme.TallyRecordingRed
import kotlin.math.roundToInt

/**
 * In-app minimal floating circular gesture button with custom SVG icons:
 * - Single Tap: Start Recording / Tap again to Stop (ic_gesture_single_tap)
 * - Double Tap: Instant Screenshot / SS (ic_gesture_double_tap_ss)
 * - Long Press: Auto-record for configured time (ic_gesture_long_press_timer)
 * - Drag Down: Close App target zone (ic_gesture_drag_close)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DraggableFloatingHudPill(
    config: RecorderConfig,
    telemetry: LiveRecordingTelemetry,
    onSingleTapRecordOrStop: () -> Unit,
    onDoubleTapScreenshot: () -> Unit,
    onLongPressAutoRecord: () -> Unit,
    onDragDownToCloseApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!config.showFloatingHud || telemetry.isSystemOverlayShowing) return

    val density = LocalDensity.current
    val infiniteTransition = rememberInfiniteTransition(label = "hud_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tally_pulse"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val buttonSizePx = with(density) { 60.dp.toPx() }

        var rawOffset by remember {
            mutableStateOf(
                Offset(
                    x = (maxWidthPx - buttonSizePx).coerceAtLeast(0f),
                    y = (maxHeightPx * 0.22f).coerceAtLeast(0f)
                )
            )
        }
        var isDragging by remember { mutableStateOf(false) }

        val isOverBottomCloseZone = isDragging && (rawOffset.y + buttonSizePx >= maxHeightPx * 0.78f)

        val animatedOffset by animateOffsetAsState(
            targetValue = rawOffset,
            animationSpec = if (isDragging) {
                spring(stiffness = Spring.StiffnessHigh)
            } else {
                spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            },
            label = "hud_offset"
        )

        // Bottom Drag-Down-to-Close Target Zone with custom SVG icon
        AnimatedVisibility(
            visible = isDragging,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            Surface(
                color = if (isOverBottomCloseZone) TallyRecordingRed else ObsidianBackground.copy(alpha = 0.92f),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .border(
                        width = 2.dp,
                        color = TallyRecordingRed,
                        shape = RoundedCornerShape(28.dp)
                    )
                    .testTag("drag_to_close_zone")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_gesture_drag_close),
                        contentDescription = "Drag down to close app",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isOverBottomCloseZone) {
                            "Release to Close App"
                        } else {
                            "Drag Down Here to Close App"
                        },
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = CrispStudioWhite
                    )
                }
            }
        }

        val isRecording = telemetry.status == RecorderStatus.RECORDING
        val isTimedAuto = telemetry.autoStopTargetMillis > 0L && isRecording

        val borderColor = when (telemetry.gestureIconState) {
            GestureIconState.DOUBLE_TAP_SCREENSHOT -> PrecisionCyan
            GestureIconState.LONG_PRESS_TIMED -> AmberSignal
            GestureIconState.SINGLE_TAP_RECORDING -> TallyRecordingRed
            GestureIconState.SINGLE_TAP_IDLE -> GlassBorderSubtle
        }

        val svgIconRes = when (telemetry.gestureIconState) {
            GestureIconState.DOUBLE_TAP_SCREENSHOT -> R.drawable.ic_gesture_double_tap_ss
            GestureIconState.LONG_PRESS_TIMED -> R.drawable.ic_gesture_long_press_timer
            GestureIconState.SINGLE_TAP_RECORDING,
            GestureIconState.SINGLE_TAP_IDLE -> R.drawable.ic_gesture_single_tap
        }

        val displayMillis = when {
            telemetry.isMultiShotGap -> telemetry.gapRemainingMillis
            isTimedAuto -> (telemetry.autoStopTargetMillis - telemetry.elapsedMillis).coerceAtLeast(0L)
            else -> telemetry.elapsedMillis
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset {
                    IntOffset(
                        animatedOffset.x.roundToInt(),
                        animatedOffset.y.roundToInt()
                    )
                }
                .size(60.dp)
                .shadow(16.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(ElevatedHudSurface, ObsidianBackground)
                    )
                )
                .border(
                    width = 2.dp,
                    color = borderColor,
                    shape = CircleShape
                )
                .pointerInput(maxWidthPx, maxHeightPx) {
                    detectDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = {
                            val shouldClose = rawOffset.y + buttonSizePx >= maxHeightPx * 0.78f
                            isDragging = false
                            if (shouldClose) {
                                onDragDownToCloseApp()
                                return@detectDragGestures
                            }
                            val maxX = (maxWidthPx - buttonSizePx).coerceAtLeast(0f)
                            val maxY = (maxHeightPx - buttonSizePx).coerceAtLeast(0f)
                            val snappedX =
                                if (rawOffset.x + buttonSizePx / 2f < maxWidthPx / 2f) 0f else maxX
                            rawOffset = Offset(
                                x = snappedX,
                                y = rawOffset.y.coerceIn(0f, maxY)
                            )
                        },
                        onDragCancel = { isDragging = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val maxX = (maxWidthPx - buttonSizePx).coerceAtLeast(0f)
                            val maxY = (maxHeightPx - buttonSizePx).coerceAtLeast(0f)
                            rawOffset = Offset(
                                x = (rawOffset.x + dragAmount.x).coerceIn(0f, maxX),
                                y = (rawOffset.y + dragAmount.y).coerceIn(0f, maxY)
                            )
                        }
                    )
                }
                .combinedClickable(
                    onClick = onSingleTapRecordOrStop,
                    onDoubleClick = onDoubleTapScreenshot,
                    onLongClick = onLongPressAutoRecord
                )
                .testTag("floating_hud_pill")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = svgIconRes),
                    contentDescription = "Floating Gesture Button",
                    modifier = Modifier
                        .size(22.dp)
                        .alpha(if (isRecording || telemetry.isMultiShotGap) pulseAlpha else 1f)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when {
                        telemetry.gestureIconState == GestureIconState.DOUBLE_TAP_SCREENSHOT -> "SS!"
                        telemetry.isMultiShotGap -> "G:${(telemetry.gapRemainingMillis / 1000L) + 1}s"
                        isRecording && telemetry.totalShots > 1 ->
                            "${telemetry.currentShotIndex}/${telemetry.totalShots}"
                        isRecording -> formatFloatingPointTimecode(displayMillis).takeLast(4)
                        else -> "TAP"
                    },
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = CrispStudioWhite
                )
            }
        }
    }
}
