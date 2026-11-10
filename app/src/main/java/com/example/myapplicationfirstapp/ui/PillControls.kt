package com.example.myapplicationfirstapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 胶囊按钮：Rhythm 那种「文字包在一个填色胶囊里」的按钮。
 * 底部导航、顶栏操作、回收站操作统一用它，避免各处 TextButton 观感不一。
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}

/**
 * 胶囊分段选择：文本 / 清单、主题模式这类「二选一」用它。
 * 选中项自己变成一颗填色胶囊，前面带一个勾——和参考图里的「✓ 系统」一致。
 */
@Composable
fun PillToggle(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    selectedColor: Color = MaterialTheme.colorScheme.primary,
    onSelectedColor: Color = MaterialTheme.colorScheme.onPrimary,
    unselectedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val container by animateColorAsState(
                targetValue = if (selected) selectedColor else Color.Transparent,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                label = "segmentContainer"
            )
            val content by animateColorAsState(
                targetValue = if (selected) onSelectedColor else unselectedColor,
                animationSpec = tween(200),
                label = "segmentContent"
            )

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(container)
                    .clickable { if (index != selectedIndex) onSelect(index) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(
                        visible = selected,
                        enter = expandHorizontally(
                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f)
                        ) + fadeIn(tween(140)),
                        exit = shrinkHorizontally(
                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
                        ) + fadeOut(tween(100))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = content,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = content
                    )
                }
            }
        }
    }
}
