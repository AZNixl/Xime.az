package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.ui.theme.KeyboardColorScheme

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showArrow: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标圆角背景
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
    showArrow: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier.clickable { onCheckedChange(!checked) }
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

@Composable
fun SchemaItem(
    schema: SchemaInfo,
    isSelected: Boolean,
    isDownloaded: Boolean,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onUpdate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isDownloaded && !isLoading, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = schema.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (!isDownloaded) MaterialTheme.colorScheme.outline
                    else if (isSelected) MaterialTheme.colorScheme.primary 
                    else MaterialTheme.colorScheme.onSurface
            )
            if (schema.description.isNotEmpty()) {
                Text(
                    text = schema.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (schema.version.isNotEmpty()) {
                    Text(
                        text = "版本: ${schema.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (schema.author.isNotEmpty()) {
                    Text(
                        text = "作者: ${schema.author}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (!isDownloaded) {
                    Text(
                        text = "未下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        
        if (!isDownloaded) {
            OutlinedButton(
                onClick = onDownload,
                enabled = !isLoading,
                shape = RoundedCornerShape(50)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(if (isLoading) "下载中" else "下载")
            }
        } else {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            OutlinedButton(
                onClick = onUpdate,
                enabled = !isLoading,
                shape = RoundedCornerShape(50)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(if (isLoading) "更新中" else "更新")
            }
        }
    }
}

@Composable
fun SchemaSelectDialog(
    schemas: List<SchemaInfo>,
    currentSchema: String,
    onSchemaSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择输入方案") },
        text = {
            Column {
                schemas.forEach { schema ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (currentSchema != schema.schemaId) {
                                    onSchemaSelected(schema.schemaId)
                                    onDismiss()
                                } else {
                                    onDismiss()
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = schema.schemaId == currentSchema,
                            onClick = {
                                if (currentSchema != schema.schemaId) {
                                    onSchemaSelected(schema.schemaId)
                                    onDismiss()
                                } else {
                                    onDismiss()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = schema.name, fontWeight = FontWeight.Medium)
                            Text(
                                text = schema.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}

@Composable
fun ThemeCard(
    title: String,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSystem: Boolean = false
) {
    val backgroundColor = if (isDark) Color(0xFF202124) else Color(0xFFE8EAED)
    val keyColor = if (isDark) Color(0xFF35363A) else Color(0xFFFFFFFF)
    val specialKeyColor = if (isDark) Color(0xFF4A4A4A) else Color(0xFFD3E3FD)
    val textColor = if (isDark) Color(0xFFE8EAED) else Color(0xFF202124)
    val candidateBarColor = if (isDark) Color(0xFF2D2D2D) else Color(0xFFF8F9FA)
    
    Column(
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp,
            onClick = onClick
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                if (isSystem) {
                    // 跟随系统: 左半浅色 + 右半深色
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // 左半浅色
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                                    .background(Color(0xFFE8EAED))
                                    .padding(3.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFFF8F9FA))
                                            .padding(horizontal = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(12.dp)
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(Color(0xFF1A73E8))
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color.White)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(1.dp))
                            // 右半深色
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                                    .background(Color(0xFF202124))
                                    .padding(3.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFF2D2D2D))
                                            .padding(horizontal = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(12.dp)
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(Color(0xFF8AB4F8))
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFF35363A))
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(backgroundColor)
                            .padding(4.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(candidateBarColor)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (isDark) Color(0xFF8AB4F8) else Color(0xFF1A73E8))
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            repeat(3) { rowIndex ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(vertical = 1.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    val keysInRow = if (rowIndex == 2) 4 else 10
                                    repeat(keysInRow) { keyIndex ->
                                        val isSpecialKey = (rowIndex == 0 && keyIndex == 0) ||
                                                (rowIndex == 2 && (keyIndex == 0 || keyIndex == 3))
                                        Box(
                                            modifier = Modifier
                                                .weight(if (isSpecialKey) 1.5f else 1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(if (isSpecialKey) specialKeyColor else keyColor)
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
}

@Composable
fun CodeDisplayCard(
    title: String,
    isSelected: Boolean,
    showCodeInInputBox: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val selectedBg = primary.copy(alpha = 0.15f)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = primary,
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
                onClick = onClick
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFF0F2F4))
                            .padding(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                                .border(0.5.dp, Color(0xFFDADCE0), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (showCodeInInputBox) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "曦码 shu ru fa",
                                        fontSize = 12.sp,
                                        color = onSurface,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.width(1.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(13.dp)
                                            .background(primary)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.Center, modifier = Modifier.padding(vertical = 0.dp)) {
                                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text("shu ru fa", fontSize = 9.sp, lineHeight = 1.sp, color = onSurface.copy(alpha = 0.6f))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(selectedBg)
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "输入法",
                                            fontSize = 13.sp,
                                            color = primary,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "力学",
                                        fontSize = 13.sp,
                                        color = onSurface,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) primary else onSurface
            )
        }
    }
}

// ========== Previews ==========

@Preview(name = "CodeDisplayCard - 选中+显示码")
@Composable
fun CodeDisplayCardPreview_SelectedWithCode() {
    XimeTheme {
        CodeDisplayCard(
            title = "曦码",
            isSelected = true,
            showCodeInInputBox = true,
            onClick = {},
            modifier = Modifier.padding(16.dp).height(160.dp)
        )
    }
}

@Preview(name = "CodeDisplayCard - 不显示码")
@Composable
fun CodeDisplayCardPreview_NotSelectedNoCode() {
    XimeTheme {
        CodeDisplayCard(
            title = "曦码",
            isSelected = true,
            showCodeInInputBox = false,
            onClick = {},
            modifier = Modifier.padding(16.dp).height(160.dp)
        )
    }
}

@Composable
fun CommentDisplayCard(
    title: String,
    isSelected: Boolean,
    showComment: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(modifier = modifier) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onClick() }
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 2.dp,
                                color = primary,
                                shape = RoundedCornerShape(12.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF0F2F4))
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "输入法",
                                        fontSize = 14.sp,
                                        color = primary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    if (showComment) {
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "ltif",
                                            fontSize = 9.sp,
                                            color = onSurface.copy(alpha = 0.5f),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "力学",
                                fontSize = 14.sp,
                                color = onSurface,
                                maxLines = 1
                            )
                            if (showComment) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "lixue",
                                    fontSize = 9.sp,
                                    color = onSurface.copy(alpha = 0.5f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) primary else onSurface,
                    maxLines = 1
                )
            }
        }
    }
}

@Preview(name = "CommentDisplayCard - 显示注释", heightDp = 120)
@Composable
fun CommentDisplayCardPreview_Show() {
    XimeTheme {
        CommentDisplayCard(
            title = "显示",
            isSelected = true,
            showComment = true,
            onClick = {},
            modifier = Modifier.fillMaxWidth().height(100.dp).padding(10.dp)
        )
    }
}

@Preview(name = "CommentDisplayCard - 隐藏注释", heightDp = 120)
@Composable
fun CommentDisplayCardPreview_Hide() {
    XimeTheme {
        CommentDisplayCard(
            title = "隐藏",
            isSelected = false,
            showComment = false,
            onClick = {},
            modifier = Modifier.fillMaxWidth().height(100.dp).padding(10.dp)
        )
    }
}


@Composable
fun KeyboardThemeCard(
    theme: KeyboardColorScheme,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isDark) Color(0xFF202124) else Color(0xFFE8EAED)
    val keyColor = if (isDark) Color(0xFF35363A) else Color(0xFFFFFFFF)
    val specialKeyColor = if (isDark) theme.specialKeyDark else theme.specialKeyLight
    val accentColor = if (isDark) theme.accentDark else theme.accentLight
    val candidateBarColor = if (isDark) Color(0xFF2D2D2D) else Color(0xFFF8F9FA)
    
    Column(
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = accentColor,
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp,
            onClick = onClick
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(backgroundColor)
                        .padding(4.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(candidateBarColor)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(16.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(accentColor)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        repeat(3) { rowIndex ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                val keysInRow = if (rowIndex == 2) 4 else 10
                                repeat(keysInRow) { keyIndex ->
                                    val isSpecialKey = (rowIndex == 0 && keyIndex == 0) ||
                                            (rowIndex == 2 && (keyIndex == 0 || keyIndex == 3))
                                    Box(
                                        modifier = Modifier
                                            .weight(if (isSpecialKey) 1.5f else 1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(if (isSpecialKey) specialKeyColor else keyColor)
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(specialKeyColor)
                            .padding(end = 4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = theme.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface
                    )
//                    if (isSelected) {
//                        Spacer(modifier = Modifier.width(4.dp))
//                        Icon(
//                            imageVector = Icons.Default.Check,
//                            contentDescription = null,
//                            tint = accentColor,
//                            modifier = Modifier.size(16.dp)
//                        )
//                    }
                }
            }
        }
    }
}