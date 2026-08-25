package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CommonSymbolKeyboardLayout(
    onKeyPress: (String) -> Unit,
    isAsciiMode: Boolean,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    fifthRowEnabled: Boolean = false,
    fifthRowHeightWeight: Float = 1f,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    var localAsciiMode by remember { mutableStateOf(isAsciiMode) }

    // 符号键的「配置 ID」固定用半角，显示/默认值随中英文模式变化
    val row2SymbolKeys = listOf("@", "#", "$", "&", "_", "-", "+", "(", ")", "/")
    val row2Symbols = if (localAsciiMode) {
        row2SymbolKeys
    } else {
        listOf("＠", "＃", "＄", "＆", "＿", "－", "＋", "（", "）", "／")
    }

    val row3SymbolKeys = listOf("*", ",", "\"", "'", ".", "!", "?")
    val row3Symbols = if (localAsciiMode) {
        row3SymbolKeys
    } else {
        listOf("＊", "，", "：", "；", "。", "！", "？")
    }


    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.screenWidthDp > configuration.screenHeightDp
    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)
    val suppressCursorMove = LocalSuppressCursorMove.current
    var swipeState by remember { mutableStateOf(SwipeState()) }
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var lastKeyBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        swipeState = state
        lastKeyBounds = Rect(
            left = bounds.left - keyboardBounds.left,
            top = bounds.top - keyboardBounds.top,
            right = bounds.right - keyboardBounds.left,
            bottom = bounds.bottom - keyboardBounds.top,
        )
    }

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeState,
        keyBounds = lastKeyBounds,
        keyBackgroundColor = bubbleBackgroundColor,
        keyTextColor = keyTextColor,
        accentColor = specialKeyTextColor,
        keyWidth = if (swipeState.isSwiping || swipeState.isPressed) lastKeyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width,
    )

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
            .drawWithContent {
                drawContent()
                bubbleData?.let { drawSwipeBubble(it) }
            }
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 0.dp),
    ) {
        if (isLandscape) {
            CommonSymbolLandscapeContent(
                onKeyPress = onKeyPress,
                row2Symbols = row2Symbols,
                row3Symbols = row3Symbols,
                row2SymbolKeys = row2SymbolKeys,
                row3SymbolKeys = row3SymbolKeys,
                keyBackgroundColor = keyBackgroundColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBackgroundColor,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                onKeyPressDown = onKeyPressDown,
                suppressCursorMove = suppressCursorMove,
                onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
                specialKeyTextColor = specialKeyTextColor,
                onGestureAction = onGestureAction,
            )
        } else {
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(horizontal = 2.dp, vertical = 4.dp)
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            (0..9).forEach { n ->
                                val digit = ((n + 1) % 10).toString()
                                ConfigurableKeyButton(
                                    key = digit,
                                    defaultLabel = digit,
                                    defaultValue = digit,
                                    onKeyPress = onKeyPress,
                                    onKeyPressDown = onKeyPressDown,
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    onGestureAction = onGestureAction,
                                    onSwipeStateChange = { state, bounds ->
                                        processSwipeState(state, bounds)
                                    },
                                    fontSize = 20.sp,
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            row2SymbolKeys.zip(row2Symbols).forEach { (key, sym) ->
                                ConfigurableKeyButton(
                                    key = key,
                                    defaultLabel = sym,
                                    defaultValue = sym,
                                    onKeyPress = onKeyPress,
                                    onKeyPressDown = onKeyPressDown,
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    onGestureAction = onGestureAction,
                                    onSwipeStateChange = { state, bounds ->
                                        processSwipeState(state, bounds)
                                    },
                                    fontSize = 20.sp,
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            KeyButton(
                                text = "符号",
                                onClick = { onKeyPress("symbol") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1.3f),
                                onPress = { onKeyPressDown?.invoke("symbol") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 14.sp,
                            )
                            row3SymbolKeys.zip(row3Symbols).forEach { (key, sym) ->
                                ConfigurableKeyButton(
                                    key = key,
                                    defaultLabel = sym,
                                    defaultValue = sym,
                                    onKeyPress = onKeyPress,
                                    onKeyPressDown = onKeyPressDown,
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    onGestureAction = onGestureAction,
                                    onSwipeStateChange = { state, bounds ->
                                        processSwipeState(state, bounds)
                                    },
                                    fontSize = 20.sp,
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                )
                            }
                            SwipeableIconKeyButton(
                                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                                onClick = { onKeyPress("delete") },
                                backgroundColor = specialKeyBackgroundColor,
                                iconColor = specialKeyTextColor,
                                modifier = Modifier.weight(1.2f),
                                swipeText = "清空",
                                onSwipe = { onKeyPress("clear_composition") },
                                onLongClick = { onKeyPress("delete") },
                                onPress = { onKeyPressDown?.invoke("delete") },
                                swipeUpLabel = "上滑清空",
                                swipeDownLabel = "下滑撤回",
                                onSwipeUp = { onKeyPress("clear_all") },
                                onSwipeDown = { onKeyPress("undo_clear") },
                                onSwipeLeft = {
                                    suppressCursorMove.value = true; onKeyPress("clear_composition")
                                },
                                onSwipeStateChange = { state, bounds ->
                                    processSwipeState(state, bounds)
                                },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            KeyButton(
                                text = "返回",
                                onClick = { onKeyPress("abc") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1.2f),
                                onPress = { onKeyPressDown?.invoke("abc") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 14.sp,
                            )
                            KeyButton(
                                text = "123",
                                onClick = { onKeyPress("number") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke("number") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 14.sp,
                            )
                            KeyButton(
                                text = "空格",
                                onClick = { onKeyPress("space") },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(2.5f),
                                onPress = { onKeyPressDown?.invoke("space") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 14.sp,
                            )
                            KeyButton(
                                text = if (localAsciiMode) "中" else "En",
                                onClick = { localAsciiMode = !localAsciiMode },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 12.sp,
                            )
                            KeyButton(
                                text = "确定",
                                onClick = { onKeyPress("enter") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1.2f),
                                onPress = { onKeyPressDown?.invoke("enter") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = 14.sp,
                            )
                        }

                        // ── 增高行：与主键盘同步的底部空白 ──
                        if (fifthRowEnabled) {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(fifthRowHeightWeight)
                            )
                        }
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
fun CommonSymbolLandscapeContent(
    onKeyPress: (String) -> Unit,
    row2Symbols: List<String>,
    row3Symbols: List<String>,
    row2SymbolKeys: List<String>,
    row3SymbolKeys: List<String>,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    suppressCursorMove: androidx.compose.runtime.MutableState<Boolean>,
    onSwipeStateChange: (SwipeState, Rect) -> Unit,
    specialKeyTextColor: Color = Color.White,
    isAsciiMode: Boolean = false,
    onToggleAsciiMode: (() -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    val keyVisualPadding = PaddingValues(horizontal = 1.dp, vertical = 2.dp)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 2.dp, horizontal = 50.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
        ) {
            CompositionLocalProvider(LocalKeyVisualPadding provides keyVisualPadding) {
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    (1..5).forEach { n ->
                        val digit = n.toString()
                        ConfigurableKeyButton(
                            key = digit,
                            defaultLabel = digit,
                            defaultValue = digit,
                            onKeyPress = onKeyPress,
                            onKeyPressDown = onKeyPressDown,
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onGestureAction = onGestureAction,
                            onSwipeStateChange = onSwipeStateChange,
                            fontSize = 16.sp,
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                    }
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    row2SymbolKeys.take(5).zip(row2Symbols.take(5)).forEach { (key, sym) ->
                        ConfigurableKeyButton(
                            key = key,
                            defaultLabel = sym,
                            defaultValue = sym,
                            onKeyPress = onKeyPress,
                            onKeyPressDown = onKeyPressDown,
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onGestureAction = onGestureAction,
                            onSwipeStateChange = onSwipeStateChange,
                            fontSize = 16.sp,
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                    }
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    KeyButton(
                        text = "符号",
                        onClick = { onKeyPress("symbol") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1.3f),
                        onPress = { onKeyPressDown?.invoke("symbol") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    row3SymbolKeys.take(4).zip(row3Symbols.take(4)).forEach { (key, sym) ->
                        ConfigurableKeyButton(
                            key = key,
                            defaultLabel = sym,
                            defaultValue = sym,
                            onKeyPress = onKeyPress,
                            onKeyPressDown = onKeyPressDown,
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onGestureAction = onGestureAction,
                            onSwipeStateChange = onSwipeStateChange,
                            fontSize = 16.sp,
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                    }
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    KeyButton(
                        text = "返回",
                        onClick = { onKeyPress("abc") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1.2f),
                        onPress = { onKeyPressDown?.invoke("abc") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    KeyButton(
                        text = "123",
                        onClick = { onKeyPress("number") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1.2f),
                        onPress = { onKeyPressDown?.invoke("number") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    KeyButton(
                        text = "空格",
                        onClick = { onKeyPress("space") },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1.25f),
                        onPress = { onKeyPressDown?.invoke("space") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.16f))

        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
        ) {
            CompositionLocalProvider(LocalKeyVisualPadding provides keyVisualPadding) {
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    (6..9).forEach { n ->
                        val digit = n.toString()
                        ConfigurableKeyButton(
                            key = digit,
                            defaultLabel = digit,
                            defaultValue = digit,
                            onKeyPress = onKeyPress,
                            onKeyPressDown = onKeyPressDown,
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onGestureAction = onGestureAction,
                            onSwipeStateChange = onSwipeStateChange,
                            fontSize = 16.sp,
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                    }
                    ConfigurableKeyButton(
                        key = "0",
                        defaultLabel = "0",
                        defaultValue = "0",
                        onKeyPress = onKeyPress,
                        onKeyPressDown = onKeyPressDown,
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        onGestureAction = onGestureAction,
                        onSwipeStateChange = onSwipeStateChange,
                        fontSize = 16.sp,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    row2SymbolKeys.drop(5).zip(row2Symbols.drop(5)).forEach { (key, sym) ->
                        ConfigurableKeyButton(
                            key = key,
                            defaultLabel = sym,
                            defaultValue = sym,
                            onKeyPress = onKeyPress,
                            onKeyPressDown = onKeyPressDown,
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onGestureAction = onGestureAction,
                            onSwipeStateChange = onSwipeStateChange,
                            fontSize = 16.sp,
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                    }
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    row3SymbolKeys.drop(4).zip(row3Symbols.drop(4)).forEach { (key, sym) ->
                        ConfigurableKeyButton(
                            key = key,
                            defaultLabel = sym,
                            defaultValue = sym,
                            onKeyPress = onKeyPress,
                            onKeyPressDown = onKeyPressDown,
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onGestureAction = onGestureAction,
                            onSwipeStateChange = onSwipeStateChange,
                            fontSize = 16.sp,
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                        )
                    }
                    SwipeableIconKeyButton(
                        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                        onClick = { onKeyPress("delete") },
                        backgroundColor = specialKeyBackgroundColor,
                        iconColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f),
                        swipeText = "清空",
                        onSwipe = { onKeyPress("clear_composition") },
                        onLongClick = { onKeyPress("delete") },
                        onPress = { onKeyPressDown?.invoke("delete") },
                        swipeUpLabel = "上滑清空",
                        swipeDownLabel = "下滑撤回",
                        onSwipeUp = { onKeyPress("clear_all") },
                        onSwipeDown = { onKeyPress("undo_clear") },
                        onSwipeLeft = {
                            suppressCursorMove.value = true; onKeyPress("clear_composition")
                        },
                        onSwipeStateChange = onSwipeStateChange,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    KeyButton(
                        text = "空格",
                        onClick = { onKeyPress("space") },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1.25f),
                        onPress = { onKeyPressDown?.invoke("space") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    KeyButton(
                        text = if (isAsciiMode) "中" else "En",
                        onClick = { onToggleAsciiMode?.invoke() },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(0.7f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                    KeyButton(
                        text = "确定",
                        onClick = { onKeyPress("enter") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1.2f),
                        onPress = { onKeyPressDown?.invoke("enter") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
