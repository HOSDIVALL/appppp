package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一组相连的胶囊按钮（Material 3 Expressive 的 split button / button group 几何）。
 *
 * 规范来自 material3 1.5.0-beta02 的 `ButtonGroupDefaults`：
 *  - 外侧圆角是整圆（[ButtonGroupDefaults.connectedLeadingButtonShape] 左边那一侧）
 *  - 靠近中间的两条边是「小圆角」，所以两块拼在一起像一颗被切开的胶囊
 *  - 中间的缝就是 [ButtonGroupDefaults.ConnectedSpaceBetween]（2dp）
 *
 * 1.5.0-beta02 只放出 low-level 的 `SplitButtonLayout`，没有带配色的成品组件，
 * 所以这里用官方 shape / spacing 令牌自己排一行；两段等宽，和参考图一致。
 */
@Composable
fun CapsulePair(
    leadingText: String,
    trailingText: String,
    onLeading: () -> Unit,
    onTrailing: () -> Unit,
    modifier: Modifier = Modifier,
    leadingEnabled: Boolean = true,
    trailingEnabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    minHeight: Dp = 38.dp
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CapsuleSegment(
            text = leadingText,
            onClick = onLeading,
            enabled = leadingEnabled,
            shape = ButtonGroupDefaults.connectedLeadingButtonShape,
            // 按下时内侧圆角收得更小，接近直角——M3 的按压反馈就是换 shape
            pressedShape = ButtonGroupDefaults.connectedLeadingButtonPressShape,
            containerColor = containerColor,
            contentColor = contentColor,
            minHeight = minHeight,
            modifier = Modifier.weight(1f)
        )
        CapsuleSegment(
            text = trailingText,
            onClick = onTrailing,
            enabled = trailingEnabled,
            shape = ButtonGroupDefaults.connectedTrailingButtonShape,
            pressedShape = ButtonGroupDefaults.connectedTrailingButtonPressShape,
            containerColor = containerColor,
            contentColor = contentColor,
            minHeight = minHeight,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CapsuleSegment(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    shape: Shape,
    pressedShape: Shape,
    containerColor: Color,
    contentColor: Color,
    minHeight: Dp,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Surface(
        onClick = onClick,
        enabled = enabled,
        // 交互源自持一份，才能读到 pressed 来切换 shape
        interactionSource = interaction,
        shape = if (pressed) pressedShape else shape,
        color = containerColor,
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
        modifier = modifier.height(minHeight)
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
