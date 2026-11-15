package com.example.myapplicationfirstapp.ui

import androidx.compose.ui.unit.dp

/**
 * 全局间距规格（依据 4 张 Material 主题参考图实测得出）。
 *
 * 测量前提：参考图与设备同源，1220x2712 px、density 3.0，即 406.7x904 dp，
 * 所以截图像素 / 3 就是 dp，可以直接照搬。
 *
 * 四条规律：
 *  1. 全屏只有一条 24dp 水平基线：大标题、区块标题、卡片、搜索框全部压在 24 上，
 *     只有底部导航自己用 16。
 *  2. 卡片内部再向内 16dp 放前导图/文字 —— 这是「卡内缩进」，不是第二层页面边距。
 *  3. 行高 72 + 行距 4；所有间距都落在 4 的倍数上（24 / 16 / 12 / 8 / 4）。
 *  4. 图形和文字对齐方式不同：文字自带约 2dp 字形侧距（left side bearing），图形没有。
 *     所以「视觉 24」在代码里是：标题 22、卡片 24；
 *     「视觉 24」的图标则是 48dp IconButton 里的 24dp 图标再内缩 12dp。
 */
object AppSpacing {

    /** 页面水平基线：卡片、搜索框、列表内容左右（图形/容器用） */
    val page = 24.dp

    /** 文字专用基线：22 + 约 2dp 字形侧距 = 视觉 24 */
    val pageText = 22.dp

    /** 卡片内部左右缩进（前导图形） */
    val cardInset = 16.dp

    /** 卡片内部右侧：12 + 图标在 40dp 按钮里的 10dp 内缩 = 视觉 22 */
    val cardInsetTrailing = 12.dp

    /** 48dp IconButton 中 24dp 图标的水平内缩，用来把图标视觉边缘对到基线上 */
    val iconButtonInset = 12.dp

    /** 列表行高 */
    val rowHeight = 72.dp

    /** 相邻行间距 */
    val rowGap = 4.dp

    /** 区块之间（标题 -> 内容） */
    val sectionGap = 40.dp

    /** 标题 -> 列表 */
    val titleToContent = 16.dp
}
