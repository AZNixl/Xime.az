package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Assignment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.twotone.Assignment
import androidx.compose.material.icons.twotone.Bolt
import androidx.compose.material.icons.twotone.BorderTop
import androidx.compose.material.icons.twotone.DarkMode
import androidx.compose.material.icons.twotone.ElectricBolt
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material.icons.twotone.LightMode
import androidx.compose.material.icons.twotone.Padding
import androidx.compose.material.icons.twotone.PictureInPicture
import androidx.compose.material.icons.twotone.Quickreply
import androidx.compose.material.icons.twotone.Rotate90DegreesCcw
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.SettingsOverscan
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MenuItem(
    val icon: Painter,
    val label: String,
    val action: () -> Unit
)

@Composable
fun MenuBar(
    isVisible: Boolean,
    isDarkTheme: Boolean,
    darkMode: Int = 2,
    backgroundColor: Color,
    onDismiss: () -> Unit,
    onClipboard: () -> Unit,
    onQuickSend: () -> Unit,
    onKeyboardResize: () -> Unit,
    onEmoji: () -> Unit,
    onReloadConfig: () -> Unit,
    onSettings: () -> Unit,
    onSchemaList: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onFloatingModeToggle: (() -> Unit)? = null,
    isFloatingMode: Boolean = false,
    onToolbarCustomize: () -> Unit = {},
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return
    
    val textColor = if (isDarkTheme) Color(0xFFE8EAED) else Color(0xFF202124)
    val itemBgColor = if (isDarkTheme) Color(0xFF45474A) else Color.White
    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    
    val clipboardIcon = rememberVectorPainter(Icons.AutoMirrored.TwoTone.Assignment)
    val quickSendIcon = rememberVectorPainter(Icons.TwoTone.Quickreply)
    val keyboardResizeIcon = rememberVectorPainter(Icons.TwoTone.SettingsOverscan)
    val emojiIcon = rememberVectorPainter(Icons.TwoTone.EmojiEmotions)
    val darkModeIcon = when (darkMode) {
        0 -> rememberVectorPainter(Icons.TwoTone.DarkMode)
        1 -> rememberVectorPainter(Icons.TwoTone.LightMode)
        else -> rememberVectorPainter(if (isDarkTheme) Icons.TwoTone.LightMode else Icons.TwoTone.DarkMode)
    }
    val deployIcon = rememberVectorPainter(Icons.TwoTone.Rotate90DegreesCcw)
    val customizeIcon = rememberVectorPainter(Icons.TwoTone.Padding)
    val schemaIcon = rememberVectorPainter(Icons.TwoTone.Keyboard)
    val settingsIcon = rememberVectorPainter(Icons.TwoTone.Settings)

    val darkModeLabel = when (darkMode) {
        0 -> "深色模式"
        1 -> "浅色模式"
        else -> "跟随系统"
    }

    val floatingIcon = rememberVectorPainter(Icons.TwoTone.PictureInPicture)
    val floatingLabel = if (isFloatingMode) "退出悬浮" else "悬浮模式"
    val floatingAction = onFloatingModeToggle ?: {}

    val menuItems = remember(darkModeIcon, darkModeLabel, isFloatingMode) {
        listOf(
            MenuItem(clipboardIcon, "剪贴板", onClipboard),
            MenuItem(quickSendIcon, "快捷发送", onQuickSend),
            MenuItem(keyboardResizeIcon, "键盘调节", onKeyboardResize),
            MenuItem(emojiIcon, "表情", onEmoji),
            MenuItem(floatingIcon, floatingLabel, floatingAction),
            MenuItem(darkModeIcon, darkModeLabel, onToggleDarkMode),
            MenuItem(deployIcon, "部署方案", onReloadConfig),
            MenuItem(customizeIcon, "定制工具栏", onToolbarCustomize),
            MenuItem(schemaIcon, "输入方案", onSchemaList),
            MenuItem(settingsIcon, "设置", onSettings)
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 导航区（与候选栏高度一致）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = if (isLandscape) 50.dp else 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isDarkTheme) Color(0xFF374151) else Color(0xFFF3F4F6))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "关闭菜单",
                    tint = textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 内容区（菜单项）
        if (isLandscape) {
            // 横屏：一行 8 列
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 50.dp)
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                menuItems.forEach { item ->
                    MenuItemButton(
                        item = item,
                        bgColor = itemBgColor,
                        textColor = textColor,
                        modifier = Modifier.weight(1f),
                        isLandscape = true
                    )
                }
            }
        } else {
            // 竖屏：每页最多 8 项（2 行 × 4 列），支持横向翻页
            val itemsPerPage = 8
            val pages = menuItems.chunked(itemsPerPage).map { page ->
                page + List(itemsPerPage - page.size) { null }
            }
            val pagerState = rememberPagerState(pageCount = { pages.size })
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceAround
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxItemsInEachRow = 4
                    ) {
                        pages[page].forEach { item ->
                            if (item != null) {
                                MenuItemButton(
                                    item = item,
                                    bgColor = itemBgColor,
                                    textColor = textColor,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                            }
                        }
                    }
                }

                if (pages.size > 1) {

                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(pages.size) { index ->
                            Box(
                                modifier = Modifier
                                    .size(if (index == pagerState.currentPage) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == pagerState.currentPage) textColor
                                        else textColor.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun MenuItemButton(
    item: MenuItem,
    bgColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    Column(
        modifier = modifier
            .then(if (isLandscape) Modifier.height(72.dp) else Modifier.aspectRatio(1f))
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { item.action() }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = item.icon,
            contentDescription = item.label,
            tint = textColor.copy(alpha = 0.7f),
            modifier = Modifier.size(if (isLandscape) 18.dp else 24.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.label,
            color = textColor,
            fontSize = if (isLandscape) 9.sp else 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}