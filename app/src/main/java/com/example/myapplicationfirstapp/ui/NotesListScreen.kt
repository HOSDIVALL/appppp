package com.example.myapplicationfirstapp.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.nonInteractiveScrollbar
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

    BackHandler(enabled = searchActive) {
        collapseSearch()
    }

    val pinned = notes.filter { it.pinned }
    val regular = notes.filterNot { it.pinned }
    val showSections = !isFiltering && pinned.isNotEmpty() && regular.isNotEmpty()

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
                    // 底部让出 96dp：列表和滚动条都止于悬浮导航栏上方，
                    // 否则滚到底时最后一条会被导航栏压住、滚动条也穿进导航栏
                    .padding(bottom = 96.dp)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    // material3 1.5 官方滚动条
                    .nonInteractiveScrollbar(
                        state = listState.scrollIndicatorState!!,
                        orientation = Orientation.Vertical,
                        thumbColor = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                        thickness = 8.dp,
                        thumbMinLength = 48.dp,
                        thumbMaxLengthFraction = 0.5f,
                        isFadeEnabled = false,
                        mainAxisTrackInset = 10.dp,
                        crossAxisTrackInset = 6.dp
                    ),
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
                verticalArrangement = Arrangement.spacedBy(AppSpacing.rowGap)
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
                                .height(300.dp)
                        )
                    }
                } else if (showSections) {
                    item(key = "__h_pin__") { ListSectionHeader(s.pinnedSection) }
                    items(pinned, key = { it.id }) { note ->
                        NoteRowWithSwipe(
                            note = note,
                            thumbnail = note.images.firstOrNull()?.let(state::imageFile),
                            strings = s,
                            onClick = { onOpen(note.id) },
                            onDelete = { onDelete(note) },
                            onTogglePin = { state.togglePin(note.id) }
                        )
                    }
                    item(key = "__h_recent__") { ListSectionHeader(s.recentSection) }
                    items(regular, key = { it.id }) { note ->
                        NoteRowWithSwipe(
                            note = note,
                            thumbnail = note.images.firstOrNull()?.let(state::imageFile),
                            strings = s,
                            onClick = { onOpen(note.id) },
                            onDelete = { onDelete(note) },
                            onTogglePin = { state.togglePin(note.id) }
                        )
                    }
                } else {
                    items(notes, key = { it.id }) { note ->
                        NoteRowWithSwipe(
                            note = note,
                            thumbnail = note.images.firstOrNull()?.let(state::imageFile),
                            strings = s,
                            onClick = { onOpen(note.id) },
                            onDelete = { onDelete(note) },
                            onTogglePin = { state.togglePin(note.id) }
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

        }
    }
}

// ============ 列表行 ============

@Composable
private fun ListSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        // 已在 LazyColumn 的 24dp contentPadding 里，不再叠加起始内边距，
        // 文字字形侧距本身让视觉左缘落在 24dp 上
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
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
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp)
) {
    // 用 remember(note.id)：撤销恢复后卡片状态也跟着重建
    val dismissState = remember(note.id) {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = { distance -> distance * 0.35f }
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
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
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp)
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
                .height(72.dp)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 保留十二瓣曲奇图形遮罩（48dp 图标/缩略图），嵌入 72dp 行高
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
                    Spacer(modifier = Modifier.height(2.dp))
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (note.pinned) strings.unpin else strings.pin) },
                leadingIcon = { Icon(Icons.Filled.Star, null) },
                onClick = {
                    menuOpen = false
                    onTogglePin()
                }
            )
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
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(sortLabel(mode, strings)) },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                    trailingIcon = {
                        if (mode == current) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
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
