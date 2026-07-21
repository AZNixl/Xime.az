package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val keyShape = RoundedCornerShape(8.dp)

@Composable
fun EditKeyboardLayout(
    onAction: (String) -> Unit,
    onBack: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = textColor == Color(0xFFE8EAED)
    val keyBg = if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6)
    var isSelecting by remember { mutableStateOf(false) }

    fun arrowAction(base: String): String = if (isSelecting) "select_${base}" else base

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(start = 8.dp, end = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(keyBg)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = "返回",
                        tint = textColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EditKeyRow {
                EditPlaceholder()
                EditKey("↑", onAction, arrowAction("arrow_up"), textColor, keyBg, accentColor)
                EditPlaceholder()
                EditKey("段首", onAction, "home", textColor, keyBg, accentColor)
            }
            EditKeyRow {
                EditKey("←", onAction, arrowAction("arrow_left"), textColor, keyBg, accentColor)
                EditKey(
                    label = if (isSelecting) "取消" else "选择",
                    onAction = {
                        val newSelecting = !isSelecting
                        isSelecting = newSelecting
                        onAction(if (newSelecting) "select_begin" else "select_end")
                    },
                    action = "",
                    textColor = textColor,
                    keyBg = if (isSelecting) accentColor.copy(alpha = 0.3f) else keyBg,
                    accentColor = accentColor
                )
                EditKey("→", onAction, arrowAction("arrow_right"), textColor, keyBg, accentColor)
                EditKey("段尾", onAction, "end", textColor, keyBg, accentColor)
            }
            EditKeyRow {
                EditPlaceholder()
                EditKey("↓", onAction, arrowAction("arrow_down"), textColor, keyBg, accentColor)
                EditPlaceholder()
                EditKey("删除", onAction, "delete", textColor, keyBg, accentColor)
            }
            EditKeyRow {
                EditKey("复制", onAction, "copy", textColor, keyBg, accentColor)
                EditKey("粘贴", onAction, "paste", textColor, keyBg, accentColor)
                EditKey("全选", onAction, "select_all", textColor, keyBg, accentColor)
                EditKey("回车", onAction, "enter", textColor, keyBg, accentColor)
            }
        }

        Spacer(
            modifier = Modifier.height(
                maxOf(
                    bottomPaddingDp.dp,
                    with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
                )
            )
        )
    }
}

@Composable
private fun EditKeyRow(
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        content()
    }
}

@Composable
private fun RowScope.EditPlaceholder() {
    Spacer(modifier = Modifier.weight(1f).height(48.dp))
}

@Composable
private fun RowScope.EditKey(
    label: String,
    onAction: (String) -> Unit,
    action: String,
    textColor: Color,
    keyBg: Color,
    accentColor: Color,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .let { if (enabled) it.clip(keyShape).background(keyBg).clickable { onAction(action) } else it },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) textColor else Color.Transparent,
            fontSize = if (label.length <= 2) 16.sp else 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
