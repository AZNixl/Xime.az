package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.ButtonLayout
import com.kingzcheung.xime.settings.DisplayMode
import com.kingzcheung.xime.settings.KeyGestureConfig
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.util.CharInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.settings.SettingsPreferences
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment

/** 按键视觉缩进（padding），用于消除 spacedBy 死区。
 *  pointerInput 在 padding 之前，触摸区=全尺寸；
 *  shadow/clip/background 在 padding 之后，视觉区=缩进后。
 *  各布局按需要覆盖：QWERTY 默认 (2.dp, 4.25.dp)，T9/数字 (2.dp, 2.dp) */
val LocalKeyVisualPadding = staticCompositionLocalOf {
    PaddingValues(horizontal = 2.dp, vertical = 4.25.dp)
}

/** 按键圆角半径，由各布局在根层通过 CompositionLocalProvider 提供。
 *  独立于 shadow.shape_radius，为统一配置化而设。 */
val LocalKeyCornerRadius = staticCompositionLocalOf { 8.dp }

data class SwipeState(
    val isSwiping: Boolean = false,
    val swipeText: String? = null,
    val isSwipeDown: Boolean = false,
    val charInfos: List<CharInfo> = emptyList(),
    val isPressed: Boolean = false,
    val pressedText: String? = null,
    val isDanger: Boolean = false,
    // 长按弹出选择
    val isLongPress: Boolean = false,
    val longPressItems: List<String> = emptyList(),
    val selectedLongPressIndex: Int = 0,
    val longPressDrawableIds: List<Int> = emptyList(),
)

private val shadowColorCache = HashMap<Color, Color>()

internal fun crispShadowColor(backgroundColor: Color): Color {
    return shadowColorCache.getOrPut(backgroundColor) {
        val r = backgroundColor.red
        val g = backgroundColor.green
        val b = backgroundColor.blue
        val maxChroma = maxOf(r, g, b) - minOf(r, g, b)
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        if (maxChroma > 0.05f) {
            Color(r * 0.95f, g * 0.95f, b * 0.95f, backgroundColor.alpha)
        } else if (luminance > 0.5f) {
            Color.Black.copy(alpha = 0.10f)
        } else {
            Color.White.copy(alpha = 0.12f)
        }
    }
}

@Composable
fun KeyButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    swipeText: String? = null,
    swipeDownText: String? = null,
    onSwipe: ((String) -> Unit)? = null,
    onSwipeDown: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState) -> Unit)? = null,
    fontSize: androidx.compose.ui.unit.TextUnit? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    /** 长按回调（含震动反馈），点按仍走 [onClick] */
    onLongClick: (() -> Unit)? = null,
    /** 右上角角标文字（如 T9 数字键的数字浮标） */
    badgeText: String? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var hasTriggeredSwipeUp by remember { mutableStateOf(false) }
    var hasTriggeredSwipeDown by remember { mutableStateOf(false) }
    var isSwiping by remember { mutableStateOf(false) }
    var isSwipeDown by remember { mutableStateOf(false) }
    var longPressActivated by remember { mutableStateOf(false) }
    var dragActivated by remember { mutableStateOf(false) }
    
    val density = LocalDensity.current
    val view = LocalView.current
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val swipeUpThreshold = with(density) { (-50).dp.toPx() }
    val swipeDownThreshold = with(density) { 50.dp.toPx() }
    val bubbleShowThresholdUp = swipeUpThreshold
    val bubbleShowThresholdDown = swipeDownThreshold
    // 水平位移超过该值视为横向手势（如键盘区滑动移动光标），不再触发点击
    val horizontalClickCancelThreshold = with(density) { 30.dp.toPx() }

    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
    val keyCornerRadius = LocalKeyCornerRadius.current
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }
    
    // 辅助函数：生成更深的颜色（混合黑色）
    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }
    
        Box(
            modifier = modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragActivated = true
                            isPressed = true
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                            hasTriggeredSwipeUp = false
                            hasTriggeredSwipeDown = false
                            isSwiping = false
                            isSwipeDown = false
                        },
                        onDragEnd = {
                            if (!hasTriggeredSwipeUp && !hasTriggeredSwipeDown && abs(dragOffsetX) < horizontalClickCancelThreshold) {
                                currentOnClick()
                            }
                            isPressed = false
                            currentOnRelease?.invoke()
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                            hasTriggeredSwipeUp = false
                            hasTriggeredSwipeDown = false
                            isSwiping = false
                            isSwipeDown = false
                            longPressActivated = false
                            dragActivated = false
                            onSwipeStateChange?.invoke(SwipeState(false, null, false))
                        },
                        onDragCancel = {
                            isPressed = false
                            currentOnRelease?.invoke()
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                            hasTriggeredSwipeUp = false
                            hasTriggeredSwipeDown = false
                            isSwiping = false
                            isSwipeDown = false
                            dragActivated = false
                            onSwipeStateChange?.invoke(SwipeState(false, null, false))
                        },
                        onDrag = { change, dragAmount ->
                            dragOffsetX += dragAmount.x
                            dragOffsetY += dragAmount.y
                            
                            if (dragOffsetY < 0) {
                                if (abs(dragOffsetY) > abs(dragOffsetX) * 1.1f) {
                                    val shouldShowBubble = dragOffsetY < bubbleShowThresholdUp && swipeText != null
                                    if (shouldShowBubble != isSwiping) {
                                        isSwiping = shouldShowBubble
                                        isSwipeDown = false
                                        onSwipeStateChange?.invoke(SwipeState(shouldShowBubble, swipeText, false))
                                    }
                                    
                                    if (dragOffsetY < swipeUpThreshold && !hasTriggeredSwipeUp && swipeText != null && onSwipe != null) {
                                        hasTriggeredSwipeUp = true
                                        onSwipe(swipeText)
                                    }
                                }
                            } else if (dragOffsetY > 0) {
                                if (dragOffsetY > abs(dragOffsetX) * 1.1f) {
                                    val shouldShowBubble = dragOffsetY > bubbleShowThresholdDown && swipeDownText != null
                                    if (shouldShowBubble != isSwipeDown) {
                                        isSwipeDown = shouldShowBubble
                                        isSwiping = shouldShowBubble
                                        onSwipeStateChange?.invoke(SwipeState(shouldShowBubble, swipeDownText, true))
                                    }
                                    
                                    if (dragOffsetY > swipeDownThreshold && !hasTriggeredSwipeDown && swipeDownText != null && onSwipeDown != null) {
                                        hasTriggeredSwipeDown = true
                                        onSwipeDown(swipeDownText)
                                    }
                                }
                            }
                        }
                    )
                }
                .pointerInput(currentOnLongClick != null) {
                    if (currentOnLongClick == null) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                onPress?.invoke()
                                tryAwaitRelease()
                                isPressed = false
                                currentOnRelease?.invoke()
                            },
                            onTap = {
                                if (!dragActivated && !hasTriggeredSwipeUp && !hasTriggeredSwipeDown) onClick()
                            }
                        )
                    } else {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                longPressActivated = false
                                onPress?.invoke()
                                tryAwaitRelease()
                                isPressed = false
                                currentOnRelease?.invoke()
                            },
                            onTap = {
                                if (!dragActivated && !hasTriggeredSwipeUp && !hasTriggeredSwipeDown && !longPressActivated) {
                                    currentOnClick()
                                }
                                longPressActivated = false
                            },
                            onLongPress = {
                                longPressActivated = true
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                currentOnLongClick?.invoke()
                            }
                        )
                    }
                }
            .padding(LocalKeyVisualPadding.current)
            .then(shadowModifier)
            .clip(keyClipShape)
            .background(
                if (isPressed) darkenColor(backgroundColor, 0.2f)
                else if (isHighlighted) backgroundColor.copy(alpha = 0.8f)
                else backgroundColor
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize ?: if (text.length > 2) 14.sp else 16.sp,
            fontWeight = if (text.length > 2) FontWeight.Medium else FontWeight.Normal,
            fontFamily = LocalKeyboardFontFamily.current,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        
        if (!swipeText.isNullOrEmpty()) {
            val displayText = if (swipeText.length <= 4) swipeText else swipeText.take(4)
            Text(
                text = displayText,
                color = textColor.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = LocalKeyboardFontFamily.current,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.offset(y = (-14).dp)
            )
        }

        if (badgeText != null) {
            Text(
                text = badgeText,
                color = textColor.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = LocalKeyboardFontFamily.current,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
            )
        }
    }
}

@Composable
fun SwipeableKeyButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    layoutMode: ButtonLayout = ButtonLayout.STANDARD,
    icon: Painter? = null,
    swipeText: String? = null,
    swipeDownText: String? = null,
    /** 下滑文本显示在按键上（气泡为空，用于 display:key） */
    swipeDownKeyLabel: String? = null,
    /** 上滑文本显示在按键上（气泡则为空，用于 display:bubble） */
    swipeUpKeyLabel: String? = null,
    onSwipe: ((String) -> Unit)? = null,
    onSwipeDown: ((String) -> Unit)? = null,
    /** 上滑执行值：符号提示隐藏（swipeText=null）时仍可触发上滑手势；为空回退 swipeText */
    swipeUpExecuteValue: String? = null,
    /** 下滑执行值：符号提示隐藏（swipeDownText=null）时仍可触发下滑手势 */
    swipeDownExecuteValue: String? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    onLongPressSelect: ((String) -> Unit)? = null,
    longPressItems: List<String>? = null,
    /** 长按直发（display:key）：设置后长按不弹气泡，松手即执行该回调 */
    onDirectLongPress: (() -> Unit)? = null,
    longPressDrawableIds: List<Int>? = null,
    /** 右上角角标文字（如 T9 数字键的数字浮标） */
    badgeText: String? = null,
    /** 长按提示文字，显示在键帽左上角（非气泡模式时） */
    longPressHint: String? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    swipeFontSize: androidx.compose.ui.unit.TextUnit = 9.sp,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var hasTriggeredSwipeUp by remember { mutableStateOf(false) }
    var hasTriggeredSwipeDown by remember { mutableStateOf(false) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var isSwiping by remember { mutableStateOf(false) }
    var isSwipeDown by remember { mutableStateOf(false) }
    var buttonBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var dragActivated by remember { mutableStateOf(false) }
    
    val currentText by rememberUpdatedState(text)
    val currentSwipeText by rememberUpdatedState(swipeText)
    val currentSwipeDownText by rememberUpdatedState(swipeDownText)
    val currentSwipeUpExecute by rememberUpdatedState(swipeUpExecuteValue ?: swipeText)
    val currentSwipeDownExecute by rememberUpdatedState(swipeDownExecuteValue ?: swipeDownText)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val currentOnSwipeStateChange by rememberUpdatedState(onSwipeStateChange)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPressSelect by rememberUpdatedState(onLongPressSelect)
    val currentOnDirectLongPress by rememberUpdatedState(onDirectLongPress)
    val currentLongPressItems by rememberUpdatedState(longPressItems)
    val currentLongPressDrawableIds by rememberUpdatedState(longPressDrawableIds)
    val currentLongPressHint by rememberUpdatedState(longPressHint)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    
    val density = LocalDensity.current
    val swipeUpThreshold = with(density) { (-50).dp.toPx() }
    val swipeDownThreshold = with(density) { 50.dp.toPx() }
    val bubbleShowThresholdUp = swipeUpThreshold
    val bubbleShowThresholdDown = swipeDownThreshold
    // 水平位移超过该值视为横向手势（如键盘区滑动移动光标），不再触发点击
    val horizontalClickCancelThreshold = with(density) { 30.dp.toPx() }

    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
    val keyCornerRadius = LocalKeyCornerRadius.current
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }
    val fallbackFontFamily = LocalKeyboardFontFamily.current ?: AppFonts.chaiPuaFontFamily

    Box(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragActivated = true
                        isPressed = true
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                        hasTriggeredSwipeDown = false
                        isSwiping = false
                        isSwipeDown = false
                    },
                    onDragEnd = {
                        if (!hasTriggeredSwipeUp && !hasTriggeredSwipeDown && abs(dragOffsetX) < horizontalClickCancelThreshold) {
                            currentOnClick()
                        }
                        isPressed = false
                        currentOnRelease?.invoke()
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                        hasTriggeredSwipeDown = false
                        isSwiping = false
                        isSwipeDown = false
                        dragActivated = false
                        currentOnSwipeStateChange?.invoke(SwipeState(false, null, false, emptyList(), false, null), buttonBounds)
                    },
                    onDragCancel = {
                        isPressed = false
                        currentOnRelease?.invoke()
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                        hasTriggeredSwipeDown = false
                        isSwiping = false
                        isSwipeDown = false
                        dragActivated = false
                        currentOnSwipeStateChange?.invoke(SwipeState(false, null, false, emptyList(), false, null), buttonBounds)
                    },
                    onDrag = { change, dragAmount ->
                        dragOffsetX += dragAmount.x
                        dragOffsetY += dragAmount.y
                        
                        if (dragOffsetY < 0) {
                            if (abs(dragOffsetY) > abs(dragOffsetX) * 1.1f) {
                                val shouldShowBubble = dragOffsetY < bubbleShowThresholdUp && currentSwipeText != null
                                if (shouldShowBubble != isSwiping) {
                                    isSwiping = shouldShowBubble
                                    isSwipeDown = false
                                    currentOnSwipeStateChange?.invoke(SwipeState(shouldShowBubble, currentSwipeText, false, emptyList(), false, null), buttonBounds)
                                }
                                
                                val swipeTextValue = currentSwipeUpExecute
                                val onSwipeValue = currentOnSwipe
                                if (dragOffsetY < swipeUpThreshold && !hasTriggeredSwipeUp && swipeTextValue != null && onSwipeValue != null) {
                                    hasTriggeredSwipeUp = true
                                    onSwipeValue(swipeTextValue)
                                }
                            }
                        } else if (dragOffsetY > 0) {
                            if (dragOffsetY > abs(dragOffsetX) * 1.1f) {
                                val shouldShowBubble = dragOffsetY > bubbleShowThresholdDown && currentSwipeDownText != null
                                if (shouldShowBubble != isSwipeDown) {
                                    isSwipeDown = shouldShowBubble
                                    isSwiping = shouldShowBubble
                                    currentOnSwipeStateChange?.invoke(SwipeState(shouldShowBubble, currentSwipeDownText, true, emptyList(), false, null), buttonBounds)
                                }
                                
                                val swipeDownTextValue = currentSwipeDownExecute
                                val onSwipeDownValue = currentOnSwipeDown
                                if (dragOffsetY > swipeDownThreshold && !hasTriggeredSwipeDown && swipeDownTextValue != null && onSwipeDownValue != null) {
                                    hasTriggeredSwipeDown = true
                                    onSwipeDownValue(swipeDownTextValue)
                                }
                            }
                        }
                    }
                )
            }
            .pointerInput(text, currentLongPressItems.isNullOrEmpty()) {
                if (currentLongPressItems.isNullOrEmpty()) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            currentOnSwipeStateChange?.invoke(SwipeState(isPressed = true, pressedText = currentText), buttonBounds)
                            currentOnPress?.invoke()
                            tryAwaitRelease()
                            isPressed = false
                            currentOnRelease?.invoke()
                            currentOnSwipeStateChange?.invoke(SwipeState(false, null, false, emptyList(), false, null), buttonBounds)
                        },
                        onTap = {
                            if (!dragActivated && !hasTriggeredSwipeUp && !hasTriggeredSwipeDown) currentOnClick()
                        }
                    )
                    return@pointerInput
                }
                
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    var localLongPressTriggered = false
                    var selectedIdx = 0
                    val downX = down.position.x
                    val items = currentLongPressItems ?: return@awaitEachGesture
                    
                    currentOnSwipeStateChange?.invoke(
                        SwipeState(isPressed = true, pressedText = currentText), buttonBounds
                    )
                    currentOnPress?.invoke()
                    
                    val longPressJob = scope.launch {
                        delay(180L)
                        localLongPressTriggered = true
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        // 直发模式：不弹气泡，仅震动反馈，松手时直接执行
                        if (currentOnDirectLongPress != null) {
                            currentOnSwipeStateChange?.invoke(
                                SwipeState(isPressed = true, pressedText = currentText),
                                buttonBounds
                            )
                        } else {
                            currentOnSwipeStateChange?.invoke(
                                SwipeState(
                                    isPressed = true,
                                    isLongPress = true,
                                    longPressItems = items,
                                    selectedLongPressIndex = 0,
                                    longPressDrawableIds = currentLongPressDrawableIds ?: emptyList()
                                ),
                                buttonBounds
                            )
                        }
                    }
                    
                    val cancelThresholdPx = with(density) { 5.dp.toPx() }
                    val downY = down.position.y
                    var swipeDetected = false
                    
                    try {
                        var lastReportedIdx = -1
                        var completed = false
                        while (!completed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            
                            if (change.isConsumed) continue
                            
                            if (!localLongPressTriggered) {
                                val deltaX = change.position.x - downX
                                val deltaY = change.position.y - downY
                                if (kotlin.math.abs(deltaX) > cancelThresholdPx || kotlin.math.abs(deltaY) > cancelThresholdPx) {
                                    swipeDetected = true
                                    longPressJob.cancel()
                                }
                            }
                            
                            if (localLongPressTriggered) {
                                val deltaX = change.position.x - downX
                                val itemWidth = buttonBounds.width / items.size
                                selectedIdx = ((deltaX / itemWidth) + if (items.size > 1) 0.5f else 0f).toInt()
                                    .coerceIn(0, items.size - 1)
                                
                                if (selectedIdx != lastReportedIdx) {
                                    lastReportedIdx = selectedIdx
                                    currentOnSwipeStateChange?.invoke(
                                        SwipeState(
                                            isPressed = true,
                                            isLongPress = true,
                                            longPressItems = items,
                                            selectedLongPressIndex = selectedIdx,
                                            longPressDrawableIds = currentLongPressDrawableIds ?: emptyList()
                                        ),
                                        buttonBounds
                                    )
                                }
                                change.consume()
                            }
                            
                            if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Release) {
                                completed = true
                                if (localLongPressTriggered) {
                                    // 直发模式优先：松手直接执行，不读气泡选择
                                    val direct = currentOnDirectLongPress
                                    if (direct != null) {
                                        direct()
                                    } else {
                                        val selected = items.getOrNull(selectedIdx)
                                        if (selected != null) {
                                            currentOnLongPressSelect?.invoke(selected)
                                        }
                                    }
                                } else if (!dragActivated && !swipeDetected) {
                                    currentOnClick()
                                }
                            }
                        }
                    } finally {
                        longPressJob.cancel()
                        isPressed = false
                        currentOnRelease?.invoke()
                        currentOnSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                    }
                }
            }
            .onGloballyPositioned { coordinates ->
                buttonBounds = coordinates.boundsInRoot()
            }
            .padding(LocalKeyVisualPadding.current)
            .then(shadowModifier)
            .clip(keyClipShape)
            .background(
                if (isPressed) backgroundColor.copy(alpha = 0.7f)
                else if (isHighlighted) backgroundColor.copy(alpha = 0.8f)
                else backgroundColor
            ),
        contentAlignment = if (layoutMode == ButtonLayout.COMPACT) Alignment.TopStart else Alignment.Center
    ) {
        if (layoutMode == ButtonLayout.COMPACT) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (icon != null) {
                    Icon(
                        painter = icon,
                        contentDescription = text,
                        tint = textColor,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 2.dp, start = 4.dp)
                            .size(16.dp)
                    )
                } else {
                    Text(
                        text = text,
                        color = textColor,
                        fontSize = if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) fontSize else if (text.length > 2) 13.sp else 16.sp,
                        fontWeight = if (text.length > 2) FontWeight.Medium else FontWeight.Normal,
                        fontFamily = LocalKeyboardFontFamily.current,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        lineHeight = 1.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 2.dp, start = 4.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .padding(top = 4.dp, end = 4.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    val swipeUpHint = swipeUpKeyLabel ?: swipeText
                    if (!swipeUpHint.isNullOrEmpty()) {
                        val displayText = if (swipeUpHint.length <= 2) swipeUpHint else swipeUpHint.take(2)
                        Text(
                            text = displayText,
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = swipeFontSize,
                            fontWeight = FontWeight.Medium,
                            fontFamily = LocalKeyboardFontFamily.current,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            lineHeight = 1.sp
                        )
                    }

                    val swipeDownHint = swipeDownKeyLabel
                    if (!swipeDownHint.isNullOrEmpty()) {
                        val hasChinese = swipeDownHint.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' || it in '\uf900'..'\ufaff' }
                        val adjustedFontSize = if (hasChinese && swipeFontSize > 6.sp) (swipeFontSize.value * 0.85f).sp else swipeFontSize
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            val displayText = if (swipeDownHint.length <= 12) swipeDownHint else swipeDownHint.take(12)
                            Text(
                                text = displayText,
                                color = textColor.copy(alpha = 0.7f),
                                fontSize = adjustedFontSize,
                                fontWeight = FontWeight.Medium,
                                fontFamily = fallbackFontFamily,
                                textAlign = TextAlign.Right,
                                maxLines = 3,
                                lineHeight = adjustedFontSize
                            )
                        }
                    }
                }
            }
        } else {
            if (icon != null) {
                Icon(
                    painter = icon,
                    contentDescription = text,
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = text,
                    color = textColor,
                    fontSize = if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) fontSize else if (text.length > 2) 14.sp else 18.sp,
                    fontWeight = if (text.length > 2) FontWeight.Medium else FontWeight.Normal,
                    fontFamily = LocalKeyboardFontFamily.current,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }

            if (!(swipeUpKeyLabel ?: swipeText).isNullOrEmpty()) {
                val keyLabel = (swipeUpKeyLabel ?: swipeText)!!
                val displayText = if (keyLabel.length <= 4) keyLabel else keyLabel.take(4)
                Text(
                    text = displayText,
                    color = textColor.copy(alpha = 0.6f),
                    fontSize = swipeFontSize,
                    fontWeight = FontWeight.Medium,
                    fontFamily = LocalKeyboardFontFamily.current,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.offset(y = (-14).dp)
                )
            }

            if (!swipeDownKeyLabel.isNullOrEmpty()) {
                val displayText = if (swipeDownKeyLabel.length <= 4) swipeDownKeyLabel else swipeDownKeyLabel.take(4)
                Text(
                    text = displayText,
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = swipeFontSize,
                    fontWeight = FontWeight.Normal,
                    fontFamily = LocalKeyboardFontFamily.current,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.offset(y = (14).dp)
                )
            }

            if (badgeText != null) {
                Text(
                    text = badgeText,
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = LocalKeyboardFontFamily.current,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    lineHeight = 1.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                )
            }

            val longPressHintText = currentLongPressHint
            if (!longPressHintText.isNullOrEmpty()) {
                Text(
                    text = longPressHintText,
                    color = textColor.copy(alpha = 0.45f),
                    fontSize = swipeFontSize,
                    fontWeight = FontWeight.Normal,
                    fontFamily = LocalKeyboardFontFamily.current,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    lineHeight = 1.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 4.dp, start = 4.dp)
                )
            }
        }
    }
}

@Composable
fun KeyboardRow(
    keys: List<String>,
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    isShifted: Boolean,
    modifier: Modifier = Modifier,
    swipeKeys: List<String>? = null,
    swipeDownKeys: List<String>? = null,
    onSwipeKey: ((String) -> Unit)? = null,
    onSwipeDownKey: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    onKeyPressDown: ((String) -> Unit)? = null,
    onKeyRelease: ((String) -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
    ) {
        keys.forEachIndexed { index, key ->
            val swipeText = swipeKeys?.getOrNull(index)
            val swipeDownText = swipeDownKeys?.getOrNull(index)
            val rowOnClick = remember(key, onKeyPress) { { onKeyPress(key) } }
            val rowOnPress: (() -> Unit)? = remember(key, onKeyPressDown) { { onKeyPressDown?.invoke(key); Unit } }
            val rowOnRelease: (() -> Unit)? = remember(key, onKeyRelease) { { onKeyRelease?.invoke(key); Unit } }
            SwipeableKeyButton(
                text = if (isShifted) key.uppercase() else key,
                onClick = rowOnClick,
                backgroundColor = keyBackgroundColor,
                textColor = keyTextColor,
                modifier = Modifier.weight(1f),
                swipeText = swipeText,
                swipeDownText = swipeDownText,
                onSwipe = onSwipeKey,
                onSwipeDown = onSwipeDownKey,
                onSwipeStateChange = onSwipeStateChange,
                onPress = rowOnPress,
                onRelease = rowOnRelease
            )
        }
    }
}

@Composable
fun IconKeyButton(
    icon: Painter,
    onClick: () -> Unit,
    backgroundColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
    val keyCornerRadius = LocalKeyCornerRadius.current
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }
    
    // 辅助函数：生成更深的颜色（混合黑色）
    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }
    
    Box(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPress?.invoke()
                        tryAwaitRelease()
                        isPressed = false
                        onRelease?.invoke()
                    },
                    onTap = {
                        onClick()
                    }
                )
            }
            .padding(LocalKeyVisualPadding.current)
            .then(shadowModifier)
            .clip(keyClipShape)
            .background(
                if (isPressed) darkenColor(backgroundColor, 0.1f)
                else if (isHighlighted) darkenColor(backgroundColor, 0.2f)
                else backgroundColor
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )

        // 右上角小圆点指示 — 仅在 isHighlighted 时显示
        if (isHighlighted) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(iconColor)
            )
        }
    }
}

@Composable
fun SwipeableIconKeyButton(
    icon: Painter,
    onClick: () -> Unit,
    backgroundColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    swipeText: String? = null,
    onSwipe: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    // 上滑/下滑/左滑增强
    swipeUpLabel: String? = null,
    swipeDownLabel: String? = null,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var hasTriggeredSwipe by remember { mutableStateOf(false) }
    var hasTriggeredSwipeDown by remember { mutableStateOf(false) }
    var hasTriggeredSwipeLeft by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var isSwipingUp by remember { mutableStateOf(false) }
    var isSwipingDown by remember { mutableStateOf(false) }
    var isDangerZone by remember { mutableStateOf(false) }
    var hasReachedClearThreshold by remember { mutableStateOf(false) }
    var hasReachedUndoThreshold by remember { mutableStateOf(false) }
    var isLongPress by remember { mutableStateOf(false) }
    var hasTriggeredLongPress by remember { mutableStateOf(false) }
    var buttonBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var dragActivated by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnRelease by rememberUpdatedState(onRelease)

    val density = LocalDensity.current
    val swipeUpThreshold = with(density) { (-50).dp.toPx() }
    val swipeDownThreshold = with(density) { 50.dp.toPx() }
    val swipeLeftThreshold = with(density) { (-50).dp.toPx() }
    val bubbleShowThresholdUp = swipeUpThreshold
    val bubbleShowThresholdDown = swipeDownThreshold
    
    // 上滑清空/下滑撤回需要更大的滑动距离，防止误触
    val clearActionThreshold = with(density) { (-50).dp.toPx() }
    val undoActionThreshold = with(density) { 50.dp.toPx() }
    // 水平位移超过该值视为横向手势（如键盘区滑动移动光标），不再触发点击
    val horizontalClickCancelThreshold = with(density) { 30.dp.toPx() }
    
    LaunchedEffect(isLongPress) {
        if (isLongPress && onLongClick != null) {
            hasTriggeredLongPress = true
            while (isLongPress) {
                onLongClick()
                // 长按重复间隔 30ms：80ms 时退格删除以 12.5Hz 离散更新
                // 候选栏，低于视觉融合阈值，看起来像"一闪一闪"；30ms 时更新更密集更顺滑。
                delay(30)
            }
        }
    }

    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
    val keyCornerRadius = LocalKeyCornerRadius.current
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }
    
    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }
    
    Box(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPress?.invoke()
                        tryAwaitRelease()
                        isPressed = false
                        currentOnRelease?.invoke()
                        isLongPress = false
                    },
                    onTap = {
                        if (!dragActivated && !isDragging && !hasTriggeredLongPress) {
                            onClick()
                        }
                        hasTriggeredLongPress = false
                    },
                    onLongPress = {
                        isLongPress = true
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragActivated = true
                        isDragging = true
                        isPressed = true
                        isLongPress = false
                        dragOffsetY = 0f
                        dragOffsetX = 0f
                        hasTriggeredSwipe = false
                        hasTriggeredSwipeDown = false
                        hasTriggeredSwipeLeft = false
                        isSwipingUp = false
                        isSwipingDown = false
                        isDangerZone = false
                        hasReachedClearThreshold = false
                        hasReachedUndoThreshold = false
                        onSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                        onPress?.invoke()
                    },
                    onDragEnd = {
                        when {
                            hasReachedClearThreshold && onSwipeUp != null -> {
                                onSwipeUp()
                            }
                            hasReachedUndoThreshold && onSwipeDown != null -> {
                                onSwipeDown()
                            }
                            isSwipingUp && !hasTriggeredSwipe && onSwipe != null -> {
                                hasTriggeredSwipe = true
                                onSwipe()
                            }
                            dragOffsetY < swipeUpThreshold && !hasTriggeredSwipe && onSwipe != null -> {
                                hasTriggeredSwipe = true
                                onSwipe()
                            }
                            !hasTriggeredSwipeLeft && abs(dragOffsetX) < horizontalClickCancelThreshold -> {
                                currentOnClick()
                            }
                        }
                        dragActivated = false
                        isPressed = false
                        currentOnRelease?.invoke()
                        dragOffsetY = 0f
                        dragOffsetX = 0f
                        hasTriggeredSwipe = false
                        hasTriggeredSwipeDown = false
                        hasTriggeredSwipeLeft = false
                        isDragging = false
                        isSwipingUp = false
                        isSwipingDown = false
                        isDangerZone = false
                        hasReachedClearThreshold = false
                        hasReachedUndoThreshold = false
                        isLongPress = false
                        onSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                    },
                    onDragCancel = {
                        dragActivated = false
                        isPressed = false
                        currentOnRelease?.invoke()
                        dragOffsetY = 0f
                        dragOffsetX = 0f
                        hasTriggeredSwipe = false
                        hasTriggeredSwipeDown = false
                        hasTriggeredSwipeLeft = false
                        isDragging = false
                        isSwipingUp = false
                        isSwipingDown = false
                        isDangerZone = false
                        hasReachedClearThreshold = false
                        hasReachedUndoThreshold = false
                        isLongPress = false
                        onSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                    },
                    onDrag = { change, dragAmount ->
                        dragOffsetY += dragAmount.y
                        dragOffsetX += dragAmount.x

                        if (dragOffsetX < swipeLeftThreshold && !hasTriggeredSwipeLeft && onSwipeLeft != null) {
                            hasTriggeredSwipeLeft = true
                            onSwipeLeft()
                        }

                        if (dragOffsetY < 0 && dragOffsetX >= swipeLeftThreshold) {
                            val showUp = dragOffsetY < bubbleShowThresholdUp && swipeUpLabel != null
                            if (showUp != isSwipingUp) {
                                isSwipingUp = showUp
                                isSwipingDown = false
                                onSwipeStateChange?.invoke(
                                    SwipeState(isSwiping = showUp, swipeText = swipeUpLabel, isSwipeDown = false),
                                    buttonBounds
                                )
                            }
                            
                            val inDanger = dragOffsetY < clearActionThreshold
                            if (inDanger != isDangerZone) {
                                isDangerZone = inDanger
                                onSwipeStateChange?.invoke(
                                    SwipeState(isSwiping = true, swipeText = swipeUpLabel, isSwipeDown = false, isDanger = inDanger),
                                    buttonBounds
                                )
                            }
                            
                            hasReachedClearThreshold = inDanger
                        }
                        
                        if (dragOffsetY > 0 && dragOffsetX >= swipeLeftThreshold) {
                            val showDown = dragOffsetY > bubbleShowThresholdDown && swipeDownLabel != null
                            if (showDown != isSwipingDown) {
                                isSwipingDown = showDown
                                isSwipingUp = false
                                onSwipeStateChange?.invoke(
                                    SwipeState(isSwiping = showDown, swipeText = swipeDownLabel, isSwipeDown = true),
                                    buttonBounds
                                )
                            }
                            
                            val inDanger = dragOffsetY > undoActionThreshold
                            if (inDanger != isDangerZone) {
                                isDangerZone = inDanger
                                onSwipeStateChange?.invoke(
                                    SwipeState(isSwiping = true, swipeText = swipeDownLabel, isSwipeDown = true, isDanger = inDanger),
                                    buttonBounds
                                )
                            }
                            
                            hasReachedUndoThreshold = inDanger
                        }
                    }
                )
            }
            .onGloballyPositioned { coordinates ->
                buttonBounds = coordinates.boundsInRoot()
            }
            .padding(LocalKeyVisualPadding.current)
            .then(shadowModifier)
            .clip(keyClipShape)
            .background(
                if (isPressed) darkenColor(backgroundColor, 0.2f)
                else if (isHighlighted) backgroundColor.copy(alpha = 0.8f)
                else backgroundColor
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
        
        if (!swipeText.isNullOrEmpty()) {
            Text(
                text = swipeText,
                color = iconColor.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = LocalKeyboardFontFamily.current,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.offset(y = (-14).dp)
            )
        }
    }
}

/**
 * 配置驱动按键：从 KeysConfigHelper 读取 tap/上滑/下滑/长按/临时候选键配置，
 * 并渲染为 SwipeableKeyButton。用于数字键盘、常用符号键盘等硬编码布局中的按键。
 *
 * @param key 配置键 ID，如 "1"、"."、"@"
 * @param defaultLabel 无配置时的默认显示文字
 * @param defaultValue 无配置时的默认上屏值
 */
@Composable
fun ConfigurableKeyButton(
    key: String,
    defaultLabel: String,
    defaultValue: String = defaultLabel,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    textColor: Color,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)? = null,
    onKeyPressRelease: ((String) -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    onCandidateSelect: ((Int) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    isComposing: Boolean = false,
    swipeHintsEnabled: Boolean = true,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    val cfg = KeysConfigHelper.getKeyGesture(key) ?: KeyGestureConfig()
    val tap = cfg.tap
    val tapAction = tap?.action
    val tapValue = tap?.value?.takeIf { it.isNotEmpty() }
        ?: tap?.label?.takeIf { it.isNotEmpty() }
        ?: defaultValue
    val tapLabel = tap?.label?.takeIf { it.isNotEmpty() } ?: tapValue

    // 临时候选键：有候选/编码时覆盖点击行为
    val composingAction = cfg.composing
    val composingDisplay = if (isComposing && composingAction.isNotEmpty()) {
        when (composingAction) {
            "select_2" -> "2选"
            "select_3" -> "3选"
            "Escape" -> "取消"
            else -> ""
        }
    } else ""
    val displayText = composingDisplay.takeIf { it.isNotEmpty() } ?: tapLabel

    // 上滑
    val swipeUpRaw = cfg.swipeUp
    val swipeUpLabel = swipeUpRaw?.label?.takeIf { it.isNotEmpty() }
        ?: swipeUpRaw?.value?.takeIf { it.isNotEmpty() }
        ?: ""
    val swipeUpValue = swipeUpRaw?.value?.takeIf { it.isNotEmpty() } ?: swipeUpLabel
    val swipeUpAction = swipeUpRaw?.action
    val swipeUpDisplay = swipeUpRaw?.display ?: DisplayMode.BOTH

    // 下滑
    val swipeDownRaw = cfg.swipeDown
    val swipeDownLabel = swipeDownRaw?.label?.takeIf { it.isNotEmpty() } ?: ""
    val swipeDownValue = swipeDownRaw?.value
    val swipeDownAction = swipeDownRaw?.action
    val swipeDownDisplay = swipeDownRaw?.display ?: DisplayMode.BOTH

    // 长按
    val longPressConfig = cfg.longPress
    val longPressDisplay = longPressConfig?.display ?: "key"
    val longPressLabels = if (longPressDisplay == "bubble") {
        longPressConfig?.values?.map { it.label }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { null }
    } else null
    val longPressGestureMap = if (longPressDisplay == "bubble") {
        longPressConfig?.values?.associateBy { it.label }
    } else null
    val directLongPressLabels = if (longPressDisplay == "key") {
        longPressConfig?.values?.firstOrNull()?.let { listOf(it.label) }
    } else null
    val directLongPressGesture = if (longPressDisplay == "key") {
        longPressConfig?.values?.firstOrNull()
    } else null
    val longPressHintLabel = KeysConfigHelper.getLongPressHintLabel(key)

    val onClick: () -> Unit = remember(
        key, tapAction, tapValue, isComposing, composingAction,
        onKeyPress, onGestureAction, onCandidateSelect
    ) {
        {
            if (isComposing && composingAction.isNotEmpty()) {
                when (composingAction) {
                    // 选重键走 compose_select 伪键：Router 按方案类型发送编码后缀(;/'，整句)或数字(2/3，普通)：
                    // 整句方案进编码留候选栏继续输入；普通方案经 key_binder 选重上屏。
                    "select_2" -> onKeyPress("compose_select_2")
                    "select_3" -> onKeyPress("compose_select_3")
                    "Escape" -> onKeyPress("clear_composition")
                }
            } else if (tapAction != null && tapAction != GestureAction.COMMIT) {
                onGestureAction?.invoke(tapAction, tapValue)
            } else {
                onKeyPress(tapValue)
            }
        }
    }

    val onSwipe: ((String) -> Unit)? = if (swipeUpValue.isNotEmpty() && swipeUpAction != null && swipeUpAction != GestureAction.NONE) {
        remember(swipeUpAction, swipeUpValue, onKeyPress, onGestureAction) {
            { _: String ->
                if (swipeUpAction == GestureAction.COMMIT) {
                    onKeyPress(swipeUpValue)
                } else {
                    onGestureAction?.invoke(swipeUpAction, swipeUpValue)
                }
            }
        }
    } else null

    val onSwipeDown: ((String) -> Unit)? = if (swipeDownAction != null && swipeDownLabel.isNotEmpty()) {
        remember(swipeDownAction, swipeDownValue, swipeDownLabel, onKeyPress, onGestureAction) {
            val label = swipeDownLabel
            { _: String ->
                if (swipeDownAction == GestureAction.COMMIT) {
                    onKeyPress(swipeDownValue?.ifEmpty { label } ?: label)
                } else {
                    onGestureAction?.invoke(
                        swipeDownAction,
                        swipeDownValue?.ifEmpty { label } ?: label)
                }
            }
        }
    } else null

    val onDirectLongPress: (() -> Unit)? = if (directLongPressGesture != null) {
        remember(key, directLongPressGesture, onGestureAction, onKeyPress) {
            val g = directLongPressGesture
            {
                val action = g.action
                if (action != null && action != GestureAction.COMMIT) {
                    onGestureAction?.invoke(action, g.value.ifEmpty { g.label })
                } else {
                    onKeyPress(g.value.ifEmpty { g.label })
                }
            }
        }
    } else null

    val onLongPressSelect: ((String) -> Unit)? = remember(
        key, longPressGestureMap, onGestureAction, onKeyPress
    ) { { selectedLabel: String ->
        val gesture = longPressGestureMap?.get(selectedLabel)
        val action = gesture?.action
        if (action != null && action != GestureAction.COMMIT) {
            onGestureAction?.invoke(
                action,
                gesture.value.ifEmpty { selectedLabel })
        } else {
            onKeyPress(selectedLabel)
        }
    } }

    SwipeableKeyButton(
        layoutMode = KeysConfigHelper.getButtonLayout(false),
        text = displayText,
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier,
        swipeText = swipeUpLabel.takeIf {
            it.isNotEmpty() && swipeHintsEnabled && swipeUpDisplay != DisplayMode.KEY
        },
        swipeDownText = swipeDownLabel.takeIf {
            it.isNotEmpty() && swipeHintsEnabled && swipeDownDisplay != DisplayMode.KEY
        },
        swipeUpKeyLabel = if (swipeUpDisplay != DisplayMode.BUBBLE && swipeHintsEnabled) swipeUpLabel else null,
        swipeDownKeyLabel = if (
            (swipeDownDisplay == DisplayMode.KEY || swipeDownDisplay == DisplayMode.BOTH) && swipeHintsEnabled
        ) swipeDownLabel else null,
        onSwipe = onSwipe,
        onSwipeDown = onSwipeDown,
        swipeUpExecuteValue = swipeUpValue.takeIf { it.isNotEmpty() },
        swipeDownExecuteValue = swipeDownLabel.takeIf { it.isNotEmpty() },
        onSwipeStateChange = onSwipeStateChange,
        onPress = { onKeyPressDown?.invoke(key) },
        onRelease = { onKeyPressRelease?.invoke(key) },
        onLongPressSelect = onLongPressSelect,
        longPressItems = longPressLabels ?: directLongPressLabels,
        onDirectLongPress = onDirectLongPress,
        longPressHint = if (swipeHintsEnabled) longPressHintLabel else null,
        fontSize = fontSize,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
}

/** 键盘全局自定义字体（null = 系统默认）。由 KeyboardLayout 根部通过 CompositionLocalProvider 提供。 */
val LocalKeyboardFontFamily = staticCompositionLocalOf<FontFamily?> { null }
