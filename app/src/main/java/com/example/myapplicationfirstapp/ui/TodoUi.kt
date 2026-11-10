package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 待办勾选框。
 *
 * 没用 Material 的 Checkbox：它自带 48dp 的方框内边距，放在清单列表里
 * 会显得笨重。这里按 Material You 的做法画一个圆角小方块——勾上时填主色，
 * 未勾时只留描边，触摸区仍然是 40dp，点起来不会觉得小。
 */
@Composable
fun TodoCheck(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    boxSize: Dp = 22.dp,
    touchSize: Dp = 40.dp,
    enabled: Boolean = true
) {
    val borderColor = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = RoundedCornerShape(boxSize / 3)

    Box(
        modifier = modifier
            .size(touchSize)
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onToggle) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(boxSize)
                .clip(shape)
                .background(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(width = 1.8.dp, color = borderColor, shape = shape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(boxSize * 0.68f)
                )
            }
        }
    }
}

/**
 * 清单完成进度条：细长一条，铺满卡片宽度。
 * 已完成 0 项时只画轨道，不留一小截突兀的色块。
 */
@Composable
fun TodoProgressBar(done: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(4.dp)
            .clip(CircleShape)
            // 轨道用「正文色的低透明度」而不是某个容器色：
            // 卡片底色有灰、紫、蓝三种，写死一个容器色总有两张卡看不清
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
