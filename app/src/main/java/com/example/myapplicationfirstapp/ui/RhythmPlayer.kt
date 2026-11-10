package com.example.myapplicationfirstapp.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 星形按钮的轮廓。
 *
 * 不是尖角星，是"印章"那种圆润的花瓣边——半径按 cos 调制，
 * 采样成一条闭合曲线，和参考稿里播放器两侧那两枚按钮一个形状。
 */
class StarBurstShape(
    private val points: Int = 8,
    private val innerRatio: Float = 0.87f
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(cx, cy)
        val path = Path()

        val steps = 360
        for (index in 0..steps) {
            val angle = index.toFloat() / steps * PI.toFloat() * 2f
            // |cos(points/2 · θ)| 一圈正好 points 个波峰，波谷收到 innerRatio
            val modulation = abs(cos(points / 2f * angle))
            val r = radius * (innerRatio + (1f - innerRatio) * modulation)
            val x = cx + r * cos(angle)
            val y = cy + r * kotlin.math.sin(angle)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

val StarBurst: Shape = StarBurstShape()

/**
 * 播放器主体：一枚大胶囊 + 两侧星形按钮。
 *
 * 胶囊只在按下时缩一点点（spring 回弹），不做别的花活，
 * 因为播放/暂停是最高频的操作，动效越克制越稳。
 */
@Composable
fun CapsulePlayButton(
    playing: Boolean,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "capsulePress"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .height(72.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = content,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (playing) "暂停" else "播放",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = content
            )
        }
    }
}

/** 星形次级按钮，两侧的 ±5 秒用它 */
@Composable
fun StarActionButton(
    container: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 62.dp,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "starPress"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(StarBurst)
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
