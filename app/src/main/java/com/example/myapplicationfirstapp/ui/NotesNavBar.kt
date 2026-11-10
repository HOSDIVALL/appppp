package com.example.myapplicationfirstapp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplicationfirstapp.ui.i18n.LocalStrings

/** 主页的两个页签：笔记 / 录音 */
enum class HomeTab(val label: String) {
    NOTES("笔记"),
    RECORD("录音")
}

/**
 * 底部导航栏（仿 Rhythm）：
 *
 * 左边一条胶囊，装着两个页签——选中的那个撑开成填色胶囊、图标 + 文字，
 * 没选中的只留图标；右边是个独立圆形按钮。
 *
 * 右边这颗按钮是会变身的：在笔记页是「+」，切到录音页就变成橙色的麦克风。
 * 点它之后按钮会从原位飞到页面中下变成录音键（飞行本身由外层做，
 * 这里只负责把位置报上去、并在飞走后把自己藏起来）。
 */
@Composable
fun NotesNavBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val accentColor = MaterialTheme.colorScheme.primary
    val onAccentColor = MaterialTheme.colorScheme.onPrimary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    val recordingTab = selected == HomeTab.RECORD
    // 录音页那颗圆钮跟录制页的主操作走同一套 primary / onPrimary，
    // 飞下来变成暂停键时颜色不会跳；笔记页的「+」仍是 primaryContainer。
    val actionContainer by animateColorAsState(
        targetValue = if (recordingTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        animationSpec = tween(280),
        label = "navActionContainer"
    )
    val actionContent by animateColorAsState(
        targetValue = if (recordingTab) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
        animationSpec = tween(280),
        label = "navActionContent"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(64.dp)
                .clip(CircleShape)
                .background(containerColor)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            HomeTab.entries.forEach { tab ->
                NavItem(
                    tab = tab,
                    label = when (tab) {
                        HomeTab.NOTES -> s.tabNotes
                        HomeTab.RECORD -> s.tabRecords
                    },
                    selected = selected == tab,
                    accent = accentColor,
                    onAccent = onAccentColor,
                    inactive = inactiveColor,
                    onClick = { onSelect(tab) }
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 独立圆形按钮：笔记页新建，录音页开录
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(actionContainer)
                .clickable(onClick = onAction),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                    targetState = recordingTab,
                    transitionSpec = {
                        ((scaleIn(
                            initialScale = 0.6f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ) + fadeIn(spring(stiffness = Spring.StiffnessMediumLow))) togetherWith
                            (scaleOut(
                                targetScale = 0.6f,
                                animationSpec = spring(stiffness = Spring.StiffnessMedium)
                            ) + fadeOut(spring(stiffness = Spring.StiffnessMediumLow))))
                            .using(SizeTransform(clip = false))
                    },
                    label = "navActionIcon"
                ) { isRecord ->
                    Icon(
                        imageVector = if (isRecord) Icons.Filled.Mic else Icons.Filled.Add,
                        contentDescription = if (isRecord) "开始录音" else "新建笔记",
                        tint = actionContent,
                        modifier = Modifier.size(28.dp)
                    )
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(
    tab: HomeTab,
    label: String,
    selected: Boolean,
    accent: Color,
    onAccent: Color,
    inactive: Color,
    onClick: () -> Unit
) {
    // 选中项的宽度也是动画的：从「只有一个图标」撑开成「图标 + 文字」。
    // 用 spring 而不是 tween：撑开时会带一点点过冲再回落，像有质量的东西
    val weight by animateFloatAsState(
        targetValue = if (selected) 1.75f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "navWeight"
    )

    val container by animateColorAsState(
        targetValue = if (selected) accent else Color.Transparent,
        animationSpec = tween(240),
        label = "navContainer"
    )
    val content by animateColorAsState(
        targetValue = if (selected) onAccent else inactive,
        animationSpec = tween(240),
        label = "navContent"
    )

    Row(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = tabIcon(tab),
            contentDescription = label,
            tint = content,
            modifier = Modifier.size(22.dp)
        )

        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                ((expandHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)))
                    togetherWith
                        (shrinkHorizontally(
                            animationSpec = spring(stiffness = Spring.StiffnessMedium)
                        ) + fadeOut(animationSpec = tween(90))))
            },
            label = "navLabel"
        ) { show ->
            if (show) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = content
                    )
                }
            }
        }
    }
}

private fun tabIcon(tab: HomeTab) = when (tab) {
    HomeTab.NOTES -> Icons.Filled.Description
    HomeTab.RECORD -> Icons.Filled.Mic
}
