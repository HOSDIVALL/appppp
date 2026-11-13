package com.example.myapplicationfirstapp.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.SortMode
import com.example.myapplicationfirstapp.data.formatNoteTime
import com.example.myapplicationfirstapp.data.formatReminderTime
import com.example.myapplicationfirstapp.ui.i18n.AppStrings
import com.example.myapplicationfirstapp.ui.i18n.LocalStrings

/**
 * 笔记首页（Notes GO 2.0）。
 *
 * 这一版按 Material 3 的「列表优先」重做：
 * - LargeTopAppBar + 滚动收起
 * - DockedSearchBar 做全局搜索，展开时给标签快筛
 * - FilterChip 做类型筛选（全部 / 待办 / 置顶 / 提醒）
 * - 新建入口统一收到右下角官方 FAB Menu（新建笔记 / 新建待办），本页不再放 SplitButton
 * - ListItem + Divider 取代原来的彩色大卡片，信息密度更高
 * - 待办进度的波浪条用 1.5 的 WavyProgressIndicator
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    state: NotesState,
    onOpen: (String) -> Unit,
    onDelete: (Note) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val notes = state.visibleNotes
    val isFiltering = state.query.isNotBlank() ||
        state.selectedTag != null ||
        state.filter != NoteFilter.ALL

    var searchActive by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 收起搜索 = 关面板 + 清焦点 + 收键盘；点外部、点搜索键、点标签都走这一条路
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun collapseSearch() {
        searchActive = false
        keyboard?.hide()
        focusManager.clearFocus()
    }

    // 打开已有笔记的交接点：先把搜索输入结束掉（只收键盘与焦点），再交给上层切页面。
    // 与 collapseSearch 的区别是这里不动 searchActive——搜索面板、query、匹配结果、
    // 列表滚动位置都原样留在抽屉背后；打开的是阅读态，编辑页本身不申请焦点，
    // 所以键盘也不会被带进去。
    fun openNote(id: String) {
        keyboard?.hide()
        focusManager.clearFocus()
        onOpen(id)
    }

    BackHandler(enabled = searchActive) {
        collapseSearch()
    }

    val pinned = notes.filter { it.pinned }
    val regular = notes.filterNot { it.pinned }
    val showSections = !isFiltering && pinned.isNotEmpty() && regular.isNotEmpty()

    // LazyColumn 显示项 -> 笔记的映射（筛选行/分区标题/空状态记为 null）。
    // 快速定位气泡按这份映射解析目标笔记，不拿 index 直接当笔记下标；
    // 顺序与下面 items/item 的添加顺序逐项对应。
    val displayNotes: List<Note?> = buildList {
        add(null) // __filters__
        when {
            notes.isEmpty() -> add(null) // __empty__
            showSections -> {
                add(null) // __h_pin__
                pinned.forEach { add(it) }
                add(null) // __h_recent__
                regular.forEach { add(it) }
            }
            else -> notes.forEach { add(it) }
        }
    }
    // 数据/排序/筛选/置顶任一变化的指纹：拖动中变了就终止旧映射
    val listDataKey = notes.hashCode() * 31 +
        state.query.hashCode() +
        state.filter.ordinal * 7 +
        (state.selectedTag?.hashCode() ?: 0) +
        state.sortMode.ordinal * 13

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (searchActive) {
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { collapseSearch() }
                } else Modifier
            )
    ) {

        // ---------- 顶栏：标题 + 回收站 + 设置 ----------
        LargeTopAppBar(
            title = {
                // 顶栏内置 16dp 起始内边距；再补 6dp = 22dp，文字字形自带约 2dp 侧距，
                // 视觉左缘正好落在 24dp 页面上（与列表卡片左缘同一条线）
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.padding(start = AppSpacing.pageText - 16.dp)
                ) {
                    Text(
                        text = s.appName,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "2.0",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            },
            actions = {
                // 顶栏只留一个入口：设置。回收站收进设置页，
                // 回收站里的条数用徽标挂在齿轮上，提示「有东西待清理」。
                val trashCount = state.trashedNotes.size
                IconButton(
                    onClick = onOpenSettings,
                    // 图标按钮默认贴边 8dp（4dp 顶栏内边距 + 4dp 圆底内缩）；
                    // 补 16dp 让 40dp 圆底的右缘落在 24dp 基线上，与列表卡片右缘对齐
                    modifier = Modifier.padding(end = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        if (trashCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge { Text(if (trashCount > 99) "99+" else "$trashCount") }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = s.settings,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = s.settings,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = MaterialTheme.colorScheme.surface
            ),
            expandedHeight = 116.dp,
            scrollBehavior = scrollBehavior,
            windowInsets = WindowInsets(0, 0, 0, 0)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AppSpacing.pageText,
                    end = AppSpacing.page - AppSpacing.iconButtonInset,
                    bottom = 10.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isFiltering) s.matchingNotes(notes.size) else s.notesSubtitle(notes.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            SortMenu(current = state.sortMode, strings = s, onSelect = state::changeSortMode)
        }

        // ---------- 搜索 ----------
        // 不用 DockedSearchBar：它展开时把内容放进 focusable Popup（SearchBarKt 反编译确认，
        // PopupProperties(focusable=true)），Android 的焦点 Popup 会吞掉面板以外的一切触摸，
        // 点列表区域永远收不到 —— 这就是「点外部取消不了输入」的根因。
        // 改用官方 SearchBarDefaults.InputField 组进自己的 Surface（M3 文档支持的自定义容器用法），
        // 展开内容画在布局里而不是 Popup 里，外部点击交给列表区的 scrim。
        val searchElevation by animateDpAsState(
            targetValue = if (searchActive) 8.dp else 0.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "searchElevation"
        )
        val searchTonalElevation by animateDpAsState(
            targetValue = if (searchActive) 3.dp else 0.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "searchTonalElevation"
        )
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = searchTonalElevation,
            shadowElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.page)
                .zIndex(if (searchActive) 1f else 0f)
                .shadow(searchElevation, RoundedCornerShape(28.dp), clip = false)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
        ) {
            Column {
                SearchBarDefaults.InputField(
                    query = state.query,
                    onQueryChange = state::search,
                    onSearch = { collapseSearch() },
                    expanded = searchActive,
                    onExpandedChange = { if (it) searchActive = true else collapseSearch() },
                    placeholder = { Text(s.searchNotes) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { state.search("") }) {
                                Icon(Icons.Filled.Close, contentDescription = s.searchClear)
                            }
                        }
                    }
                )
                if (searchActive) {
                    // 展开区：标签快筛，点一下收起并过滤
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        Text(
                            text = s.filterTags,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item(key = "__suggest_all__") {
                                FilterChip(
                                    selected = state.selectedTag == null,
                                    onClick = {
                                        state.selectTag(null)
                                        collapseSearch()
                                    },
                                    label = { Text(s.filterAll) }
                                )
                            }
                            items(state.allTags, key = { "suggest_$it" }) { tag ->
                                FilterChip(
                                    selected = state.selectedTag == tag,
                                    onClick = {
                                        state.selectTag(tag)
                                        collapseSearch()
                                    },
                                    label = { Text("#$tag") }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------- 筛选 chip + 新建按钮 + 笔记列表，同一个 LazyColumn ----------
        // 以前筛选行和 SplitButton 在列表外面，LazyColumn 自己只剩几张卡片，
        // 内容高度和视口几乎相同；标题栏一收起视口变大，官方滚动条判定
        // 「内容不满一屏」就直接不画了 —— 这就是「滚动条不动 + 滑一下就消失」。
        // 现在整页是一块可滚动内容，滚动条的长度、位置、可视性都由真实内容算。
        val listState = rememberLazyListState()
        val bg = MaterialTheme.colorScheme.surface
        val bottomFade by animateFloatAsState(
            targetValue = if (listState.canScrollForward) 1f else 0f,
            animationSpec = tween(180),
            label = "notesBottomFade"
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // 底部让出 96dp：列表止于悬浮导航栏上方，
                    // 否则滚到底时最后一条会被导航栏压住。
                    // 滚动条已换成自定义可拖动版本（见下方 NotesListScrollbar），
                    // 这里不再挂 material3 的 nonInteractiveScrollbar——
                    // 同一列表不允许同时出现两套指示器。
                    .padding(bottom = ListVisualTokens.listBottomReserve)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    // 卡片左右缘压在 24dp 页面上；右侧不再额外留 28dp，
                    // 滚动条交给 crossAxisTrackInset 处理，避免「左右不等宽」
                    start = AppSpacing.page,
                    end = AppSpacing.page,
                    top = 2.dp,
                    // 末尾留一段可滚动余量：最后一条笔记能滚到悬浮导航栏上方，
                    // 滚动条也才有真实行程（否则内容刚好一屏，滑块无处可走）
                    bottom = 120.dp
                ),
                // 行距不再交给全局 rowGap：分组后组内 4 / 组间 12 是两种间距，
                // 统一 spacedBy 会把筛选行、分区标题也挤成同一个值。
                verticalArrangement = Arrangement.Top
            ) {
                // 类型筛选
                item(key = "__filters__") {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "__f_all__") {
                            FilterChip(
                                selected = state.filter == NoteFilter.ALL,
                                onClick = { state.selectFilter(NoteFilter.ALL) },
                                label = { Text(s.filterAll) },
                                leadingIcon = if (state.filter == NoteFilter.ALL) {
                                    { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                        item(key = "__f_todo__") {
                            FilterChip(
                                selected = state.filter == NoteFilter.TODO,
                                onClick = {
                                    state.selectFilter(
                                        if (state.filter == NoteFilter.TODO) NoteFilter.ALL
                                        else NoteFilter.TODO
                                    )
                                },
                                label = { Text(s.filterTodo) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Checklist, null, Modifier.size(16.dp))
                                }
                            )
                        }
                        item(key = "__f_pin__") {
                            FilterChip(
                                selected = state.filter == NoteFilter.PINNED,
                                onClick = {
                                    state.selectFilter(
                                        if (state.filter == NoteFilter.PINNED) NoteFilter.ALL
                                        else NoteFilter.PINNED
                                    )
                                },
                                label = { Text(s.filterPinned) },
                                leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) }
                            )
                        }
                        item(key = "__f_remind__") {
                            FilterChip(
                                selected = state.filter == NoteFilter.REMINDER,
                                onClick = {
                                    state.selectFilter(
                                        if (state.filter == NoteFilter.REMINDER) NoteFilter.ALL
                                        else NoteFilter.REMINDER
                                    )
                                },
                                label = { Text(s.filterReminder) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Notifications, null, Modifier.size(16.dp))
                                }
                            )
                        }
                        items(state.allTags, key = { "__t_$it" }) { tag ->
                            FilterChip(
                                selected = state.selectedTag == tag,
                                onClick = {
                                    state.selectTag(if (state.selectedTag == tag) null else tag)
                                },
                                label = { Text("#$tag") }
                            )
                        }
                    }
                }

                // 新建入口已统一到右下角官方 FAB Menu（见 NotesNavBar / MainActivity）

                if (notes.isEmpty()) {
                    item(key = "__empty__") {
                        EmptyState(
                            isSearching = isFiltering,
                            strings = s,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = ListVisualTokens.listTopGap)
                                .height(300.dp)
                        )
                    }
                } else if (showSections) {
                    // 两个分区各自是一条逻辑组：首条外角、末条外角各自算，
                    // 分区标题不参与组内接缝，也不改 visibleNotes 的顺序。
                    item(key = "__h_pin__") {
                        ListSectionHeader(
                            text = s.pinnedSection,
                            modifier = Modifier.padding(top = ListVisualTokens.interGroupGap)
                        )
                    }
                    itemsIndexed(pinned, key = { _, note -> note.id }) { index, note ->
                        NoteRowWithSwipe(
                            note = note,
                            thumbnail = note.images.firstOrNull()?.let(state::imageFile),
                            strings = s,
                            onClick = { openNote(note.id) },
                            onDelete = { onDelete(note) },
                            onTogglePin = { state.togglePin(note.id) },
                            topGap = ListVisualTokens.intraGroupGap,
                            shape = ListGrouping.shapeOf(
                                ListGrouping.slotOf(indexInGroup = index, groupSize = pinned.size)
                            )
                        )
                    }
                    item(key = "__h_recent__") {
                        ListSectionHeader(
                            text = s.recentSection,
                            modifier = Modifier.padding(top = ListVisualTokens.interGroupGap)
                        )
                    }
                    itemsIndexed(regular, key = { _, note -> note.id }) { index, note ->
                        NoteRowWithSwipe(
                            note = note,
                            thumbnail = note.images.firstOrNull()?.let(state::imageFile),
                            strings = s,
                            onClick = { openNote(note.id) },
                            onDelete = { onDelete(note) },
                            onTogglePin = { state.togglePin(note.id) },
                            topGap = ListVisualTokens.intraGroupGap,
                            shape = ListGrouping.shapeOf(
                                ListGrouping.slotOf(indexInGroup = index, groupSize = regular.size)
                            )
                        )
                    }
                } else {
                    // 搜索/筛选视图是一整组：组内顺序仍是 visibleNotes 的原顺序
                    itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                        NoteRowWithSwipe(
                            note = note,
                            thumbnail = note.images.firstOrNull()?.let(state::imageFile),
                            strings = s,
                            onClick = { openNote(note.id) },
                            onDelete = { onDelete(note) },
                            onTogglePin = { state.togglePin(note.id) },
                            topGap = if (index == 0) ListVisualTokens.listTopGap
                            else ListVisualTokens.intraGroupGap,
                            shape = ListGrouping.shapeOf(
                                ListGrouping.slotOf(indexInGroup = index, groupSize = notes.size)
                            )
                        )
                    }
                }
            }

            // 底部渐变：内容滚到悬浮导航栏时化开
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .align(Alignment.BottomCenter)
                    .offset(y = (-96).dp)
                    .alpha(bottomFade)
                    .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg)))
            )

            // 右侧快速定位：替换掉原来的 nonInteractiveScrollbar。
            // 整层只画，接收手势的只有手柄那一段 40dp；高度扣掉 96dp 导航保留区，
            // 所以不会盖住顶栏/搜索框，也不会伸进悬浮导航。
            NotesListScrollbar(
                listState = listState,
                displayNotes = displayNotes,
                sortMode = state.sortMode,
                strings = s,
                contentKey = listDataKey,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxSize()
                    .padding(bottom = ListVisualTokens.listBottomReserve)
            )

        }
    }
}

// ============ 列表行 ============

@Composable
private fun ListSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        // 已在 LazyColumn 的 24dp contentPadding 里，不再叠加起始内边距，
        // 文字字形侧距本身让视觉左缘落在 24dp 上。
        // 上沿留白由调用点的 interGroupGap 给，这里只留 2dp 作为标题与首条卡的呼吸。
        modifier = modifier.padding(bottom = 2.dp)
    )
}

@Composable
private fun NoteRowWithSwipe(
    note: Note,
    thumbnail: java.io.File?,
    strings: AppStrings,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    topGap: androidx.compose.ui.unit.Dp = ListVisualTokens.intraGroupGap,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(ListVisualTokens.groupOuterCorner)
) {
    // 用 remember(note.id)：撤销恢复后卡片状态也跟着重建
    val dismissState = remember(note.id) {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = { distance -> distance * 0.35f }
        )
    }

    // 按压缩放与点击用的是同一个 InteractionSource：长按、单击仍然走原有那条路，
    // 缩放只是绘制变换（drawWithContent），不参与测量，所以命中框、行高、
    // 滑动判定都不会跟着动；松手/取消/开始滚动时 pressed 自然回落。
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) ListVisualTokens.pressScale else 1f,
        animationSpec = tween(ListVisualTokens.pressAnimationMillis),
        label = "noteRowPressScale"
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        // 背景与卡片必须共用同一个 shape：只改卡片会让滑动时露出的红底还是直角。
        // 缩放放在这一层，背景跟着一起缩，按下时不会在边缘漏出一圈红色。
        modifier = Modifier
            .padding(top = topGap)
            .drawWithContent {
                if (pressScale == 1f) {
                    drawContent()
                } else {
                    scale(scaleX = pressScale, scaleY = pressScale) {
                        this@drawWithContent.drawContent()
                    }
                }
            },
        onDismiss = { onDelete() },
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    modifier = Modifier.padding(end = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.deleteToTrash,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    ) {
        NoteRow(
            note = note,
            thumbnail = thumbnail,
            strings = strings,
            onClick = onClick,
            onTogglePin = onTogglePin,
            interactionSource = interactionSource,
            shape = shape
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoteRow(
    note: Note,
    thumbnail: java.io.File?,
    strings: AppStrings,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    interactionSource: MutableInteractionSource,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(ListVisualTokens.groupInnerCorner)
) {
    var menuOpen by remember { mutableStateOf(false) }
    val cookie = MaterialShapes.Cookie12Sided.toShape()

    val icon = when {
        note.checklist -> Icons.Filled.Checklist
        note.images.isNotEmpty() -> Icons.Filled.Image
        note.remindAt != null -> Icons.Filled.Notifications
        else -> Icons.Filled.Description
    }

    val coverColor = if (note.pinned) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val coverIconColor = if (note.pinned) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val done = note.doneCount
    val total = note.todos.size

    Box {
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = if (note.pinned) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                // 72dp 是下限而不是硬高度：两行摘要时行可以长高，不裁字
                .heightIn(min = ListVisualTokens.rowMinHeight)
                // 点击与长按仍是原来那一套；沿用同一个 interactionSource，
                // 上面那层的按压反馈才不会变成第二套手势/第二套组件
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                    onLongClick = { menuOpen = true }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ListVisualTokens.cardInset,
                        end = ListVisualTokens.cardInset,
                        top = 8.dp,
                        bottom = 8.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 保留十二瓣曲奇图形遮罩（48dp 图标/缩略图），嵌在行高里垂直居中
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(cookie)
                        .background(coverColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnail != null) {
                        AttachmentImage(
                            file = thumbnail,
                            modifier = Modifier.fillMaxSize(),
                            targetPx = 240
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = coverIconColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = note.displayTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (note.pinned) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    // 摘要：正文/清单项的一句话预览。没有正文也没有媒体时不占行，
                    // 免得每张卡都挂一句「暂无内容」把层级拉平。
                    val hasPreview = note.content.isNotBlank() ||
                        note.hasTodos ||
                        note.images.isNotEmpty() ||
                        note.audio.isNotEmpty()
                    if (hasPreview) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = note.preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    // 元信息（时间/清单进度/图片/录音/标签）降到第三层级：
                    // 字号比摘要更小，颜色仍是 onSurfaceVariant，保持对比度可读
                    Text(
                        text = buildString {
                            append(formatNoteTime(note.updatedAt, strings.english))
                            if (note.checklist && total > 0) {
                                append(" · $done/$total")
                            }
                            if (note.images.isNotEmpty()) {
                                append(" · ${note.images.size} 张图片")
                            }
                            if (note.audio.isNotEmpty()) {
                                append(" · ${note.audio.size} 段录音")
                            }
                            if (note.tags.isNotEmpty()) {
                                append(" · " + note.tags.take(3).joinToString(" ") { "#$it" })
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 尾部「更多操作」入口：按源（RH-09）为 32×44dp，图标 20dp。
                // 点它只把本行的 menuOpen 置真，不调用 onClick、不触发删除或置顶；
                // 菜单仍是一套——长按与点这里打开的是同一个 menuOpen。
                // 上一版按任务书写成 48×48，被判定与源不符，这里改回 32×44。
                Box(modifier = Modifier.padding(end = ListVisualTokens.moreTrailingClearance)) {
                    Box(
                        modifier = Modifier
                            .size(
                                width = ListVisualTokens.moreButtonWidth,
                                height = ListVisualTokens.moreButtonHeight
                            )
                            // 源 .more{border-radius:50px} → 32×44 上是胶囊，不是 12dp 圆角方块
                            .clip(RoundedCornerShape(percent = 50))
                            .clickable { menuOpen = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = strings.noteActions(note.displayTitle),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 分区菜单：16/4 圆角 + 行间 3dp，不做成一整块大胶囊
                    GroupedMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                        GroupedMenuItem(
                            slot = ListGrouping.Slot.SINGLE,
                            label = if (note.pinned) strings.unpin else strings.pin,
                            icon = Icons.Filled.Star,
                            onClick = {
                                menuOpen = false
                                onTogglePin()
                            }
                        )
                    }
                }
            }
        }
    }
}

// ============ 排序菜单 ============

@Composable
private fun SortMenu(current: SortMode, strings: AppStrings, onSelect: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.Sort,
                contentDescription = sortLabel(current, strings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        GroupedMenu(expanded = expanded, onDismiss = { expanded = false }) {
            SortMode.entries.forEachIndexed { index, mode ->
                GroupedMenuItem(
                    slot = ListGrouping.slotOf(index, SortMode.entries.size),
                    label = sortLabel(mode, strings),
                    trailing = {
                        if (mode == current) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    }
                )
            }
        }
    }
}

// ============ 16/4 分组菜单（源：RhythmGroupedMenu） ============

/**
 * 菜单容器：外层 16dp，行与行之间 3dp；单选行四角全 16，多行时首/中/尾按 16/4 收口。
 *
 * 与列表行的 24/6 分开：源里这是两套形状，不能统一成大胶囊。
 */
@Composable
private fun GroupedMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    // 源（RhythmGroupedMenu / .menu）：容器是纸面 paper，padding 8、1px 描边、软阴影；
    // 条目才是浅一档的 surface 托盘。所以容器不能用 surfaceContainer——它和条目同色，
    // 3dp 缝隙就读不出来，整块会糊成一个大胶囊。
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(ListVisualTokens.menuOuterCorner),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 180.dp)
                .padding(
                    horizontal = ListVisualTokens.menuContainerPadding,
                    vertical = ListVisualTokens.menuContainerPadding
                ),
            verticalArrangement = Arrangement.spacedBy(ListVisualTokens.menuRowGap)
        ) { content() }
    }
}

@Composable
private fun GroupedMenuItem(
    slot: ListGrouping.Slot,
    label: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        shape = ListGrouping.menuShapeOf(slot),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ListVisualTokens.menuContentHorizontal,
                    vertical = ListVisualTokens.menuContentVertical
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                // 源 .menubadge：28dp 圆形底 + primaryContainer 填充 + 16dp 图标。
                // 只留一个裸图标时，条目看起来像缩进错位的纯文字行。
                Box(
                    modifier = Modifier
                        .size(ListVisualTokens.menuIconPlate)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null, // 标题已提供可读内容
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(ListVisualTokens.menuIconSize)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (trailing != null) {
                Spacer(modifier = Modifier.width(10.dp))
                trailing()
            }
        }
    }
}

private fun sortLabel(mode: SortMode, strings: AppStrings): String = when (mode) {
    SortMode.UPDATED -> strings.sortUpdated
    SortMode.CREATED -> strings.sortCreated
    SortMode.TITLE -> strings.sortTitle
}

// ============ 空状态 ============

@Composable
private fun EmptyState(isSearching: Boolean, strings: AppStrings, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSearching) Icons.Filled.Search else Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (isSearching) strings.emptySearchTitle else strings.emptyTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isSearching) strings.emptySearchHint else strings.emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
