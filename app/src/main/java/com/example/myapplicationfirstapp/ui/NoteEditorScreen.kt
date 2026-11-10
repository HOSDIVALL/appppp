package com.example.myapplicationfirstapp.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplicationfirstapp.data.ContentBlock
import com.example.myapplicationfirstapp.data.InlineAudio
import com.example.myapplicationfirstapp.data.InlineImage
import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.Recording
import com.example.myapplicationfirstapp.data.TodoItem
import com.example.myapplicationfirstapp.data.blocksToContent
import com.example.myapplicationfirstapp.data.editableBlocksOf
import com.example.myapplicationfirstapp.data.formatDuration
import com.example.myapplicationfirstapp.data.formatNoteTime
import com.example.myapplicationfirstapp.data.formatReminderTime
import com.example.myapplicationfirstapp.data.stripMedia
import com.example.myapplicationfirstapp.ui.haptics.rememberAppHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 笔记编辑页（所见即所得）。
 *
 * 没有「预览」这一层了：正文按块渲染，文字块直接可编辑，
 * 图片块就是图，录音块就是能播的播放器——插进去什么样，看到的就是什么样。
 *
 * 正文仍然以字符串存盘（`![图片](文件名)` / `!audio(id)`），
 * 这样列表摘要、字数、备份都和以前兼容；标记只在存取时存在，界面上不出现。
 *
 * 图片/录音都有两种存在方式：
 *   - 作为附件：挂在正文上方的图片条 / 附件卡片里
 *   - 插入正文：变成正文里的一个块，跟着文字走
 *
 * 相册走系统照片选择器，拍照走 FileProvider，两者都不需要存储权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    note: Note,
    state: NotesState,
    recorder: RecorderState,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onOpenImages: (files: List<File>, index: Int) -> Unit,
    onSetReminder: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    // key 用 note.id：切换到另一条笔记时重新初始化
    var title by remember(note.id) { mutableStateOf(note.title) }

    // 触觉：文本 / 清单 这类状态切换要有一次反馈
    val haptics = rememberAppHaptics()

    // 正文的块模型：文字 / 图片 / 录音，编辑它就是编辑正文
    var blocks by remember(note.id) { mutableStateOf(editableBlocksOf(note.content)) }
    val content = remember(blocks) { blocksToContent(blocks) }

    var showTagDialog by remember { mutableStateOf(false) }
    var showImageSheet by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showReminderSheet by remember { mutableStateOf(false) }
    // 图片：作为附件 or 插入正文
    var insertInline by remember { mutableStateOf(false) }
    // 录音：挂成附件 or 插到正文
    var audioInline by remember { mutableStateOf(false) }

    // 正在展开成「大卡片」的那段录音（同一时间只展开一条）
    var expandedAudioId by remember(note.id) { mutableStateOf<String?>(null) }

    // 插入媒体时插到「最后一次聚焦的文字块」后面；没聚焦过就接到正文末尾
    var focusedBlock by remember(note.id) { mutableStateOf(-1) }
    var focusRequest by remember { mutableStateOf<Int?>(null) }

    // 按回车续行后，把焦点交给新加的那一行
    var focusTodoId by remember(note.id) { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    // 正文里已经引用过的媒体，不再重复显示在附件区
    val inlineNames = remember(content) { InlineImage.namesIn(content).toSet() }
    val inlineAudioIds = remember(content) { InlineAudio.idsIn(content).toSet() }
    val stripImages = note.images.filter { it !in inlineNames }
    val stripAudio = note.audio.filter { it !in inlineAudioIds }

    /**
     * 在最后一次聚焦的文字块后面插入一个块，并留一个能继续打字的位置。
     * [focusAfter] 只有插入文字时才给 true —— 插图/插录音不弹键盘，
     * 否则键盘会把刚插进去的东西顶到屏幕外，等于没看见。
     */
    fun insertBlock(block: ContentBlock, focusAfter: Boolean = false) {
        val list = blocks.toMutableList()
        // 聚焦过就插在那一块后面，否则接到末尾
        val at = if (focusedBlock in list.indices) focusedBlock + 1 else list.size
        list.add(at, block)
        if (list.getOrNull(at + 1) !is ContentBlock.Text) {
            list.add(at + 1, ContentBlock.Text(""))
        }
        blocks = list
        focusedBlock = at + 1
        if (focusAfter) focusRequest = at + 1
    }

    fun removeBlockAt(index: Int) {
        if (index !in blocks.indices) return
        val list = blocks.toMutableList()
        list.removeAt(index)
        if (list.isEmpty() || list.last() !is ContentBlock.Text) {
            list.add(ContentBlock.Text(""))
        }
        blocks = list
        focusedBlock = (index - 1).coerceAtLeast(0)
    }

    fun removeInlineAudioAt(index: Int, recordingId: String) {
        val block = blocks.getOrNull(index) as? ContentBlock.Audio ?: return
        if (block.id != recordingId) return
        removeBlockAt(index)
        state.removeInlineAudioReference(
            id = note.id,
            recordingId = recordingId,
            title = title,
            content = blocksToContent(blocks)
        )
        if (expandedAudioId == recordingId) expandedAudioId = null
    }

    fun updateBlockText(index: Int, text: String) {
        if (index !in blocks.indices) return
        blocks = blocks.toMutableList().also { it[index] = ContentBlock.Text(text) }
    }

    /**
     * 切换「文本 / 清单」。
     * 先把当前输入落库，再让数据层搬内容，最后把块模型同步成搬运后的正文——
     * 否则切走再切回来，编辑区里还是切换前的旧内容。
     */
    fun switchMode(toChecklist: Boolean) {
        val current = state.findNote(note.id) ?: note
        if (toChecklist == current.checklist) return
        if (toChecklist) state.updateNote(note.id, title, content)
        state.setChecklist(note.id, toChecklist)
        val fresh = editableBlocksOf(state.findNote(note.id)?.content.orEmpty())
        blocks = fresh
        focusedBlock = fresh.lastIndex.coerceAtLeast(0)
    }

    fun saveAndLeave() {
        state.updateNote(note.id, title, content)
        // 加了没写的空行不留在清单里
        if (note.checklist) state.pruneBlankTodos(note.id)
        onBack()
    }

    /** 插媒体前先确保在文本模式：清单模式的正文是 todos，插进去的块根本看不见 */
    fun ensureTextMode() {
        if (state.findNote(note.id)?.checklist == true) switchMode(false)
    }

    /** 拖动进度/±5s 之前先确保这段录音已经装进播放器（没装过就装上，不自动播） */
    fun ensurePlayer(recording: Recording) {
        if (recorder.loaded?.id != recording.id) recorder.open(recording)
    }

    /** 点任意一张图：找到它在 note.images 里的位置，交给外层打开全屏浏览器 */
    fun openImage(name: String) {
        val index = note.images.indexOf(name)
        if (index >= 0) onOpenImages(note.images.map(state::imageFile), index)
    }

    /** 把录音文字稿插进正文：带一行来源说明，独占一段 */
    fun insertTranscript(recording: Recording) {
        val body = buildString {
            append("【录音 · ${recording.displayName}】\n")
            append(recording.transcriptText.trim())
        }
        if (state.findNote(note.id)?.checklist == true) switchMode(false)
        insertBlock(ContentBlock.Text(body), focusAfter = true)
        showAudioSheet = false
    }

    // 离开编辑页时停掉播放（播放器是全局的，别让它在别的页面继续响）
    DisposableEffect(Unit) {
        onDispose { recorder.pausePlayback() }
    }

    // 录音列表默认只在切到「录音」页时才加载；编辑页的「插入录音」也要用，
    // 进来就补一次，免得选择器里空空如也
    LaunchedEffect(Unit) {
        if (recorder.recordings.isEmpty()) recorder.load()
    }

    // 拍照相关：先占好文件，再让相机 App 往里写
    var pendingCameraName by remember { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val name = pendingCameraName
        pendingCameraName = null
        if (name == null) return@rememberLauncherForActivityResult

        if (!success) {
            state.discardImageFile(name)
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            // attachCameraImage 里会先压缩再挂到笔记上
            if (state.attachCameraImage(note.id, name) && insertInline) {
                ensureTextMode()
                insertBlock(ContentBlock.Image(name))
            }
        }
    }

    // 系统照片选择器：不用申请存储权限，也不会看到整个相册
    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            val name = state.importImage(note.id, uri)
            if (name != null && insertInline) {
                ensureTextMode()
                insertBlock(ContentBlock.Image(name))
            }
        }
    }

    // 新建的空清单：进来就把光标放到第一项上，省一次点击
    LaunchedEffect(note.id) {
        val first = note.todos.firstOrNull()
        if (note.checklist && note.todos.size == 1 && first != null &&
            first.text.isBlank() && title.isBlank()
        ) {
            focusTodoId = first.id
        }
    }

    LaunchedEffect(note.id, title, content) {
        if (title == note.title && content == note.content) return@LaunchedEffect
        delay(500)
        state.updateNote(note.id, title, content)
    }

    BackHandler { saveAndLeave() }

    Column(
        modifier = modifier
            .fillMaxSize()
            // 从底部推上来时像一只抽屉：顶上两个大圆角，
            // 后面的列表页从圆角两侧露出来一点
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(MaterialTheme.colorScheme.surface)
            .imePadding()
    ) {

        TopAppBar(
            navigationIcon = {
                IconButton(onClick = { saveAndLeave() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            title = {},
            actions = {
                IconButton(onClick = { showReminderSheet = true }) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = if (note.remindAt == null) "设置提醒" else "提醒已设置",
                        tint = if (note.remindAt == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            // 抽屉层没有 Scaffold 帮忙避开状态栏，这里自己加上；
            // 顶栏高度 64dp 与主页设置按钮那一行对齐
            windowInsets = WindowInsets.statusBars
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
        ) {

            // ---------- 标题 ----------
            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("标题", style = MaterialTheme.typography.headlineSmall) },
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                singleLine = true,
                colors = transparentTextFieldColors(),
                modifier = Modifier.fillMaxWidth().then(rememberEditorFocusVisibility())
            )

            EditorStatus(content = content, note = note)

            // ---------- 标签 ----------
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(note.tags, key = { it }) { tag ->
                    FilterChip(
                        selected = true,
                        onClick = { state.removeTag(note.id, tag) },
                        label = { Text("#$tag") },
                        shape = CircleShape,
                        trailingIcon = {
                            Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    )
                }
                item(key = "__add_tag__") {
                    AssistChip(
                        onClick = { showTagDialog = true },
                        label = { Text("+ 标签") },
                        shape = CircleShape
                    )
                }
            }

            // ---------- 已设的提醒 ----------
            val remindAt = note.remindAt
            if (remindAt != null) {
                AssistChip(
                    onClick = { showReminderSheet = true },
                    label = {
                        Text(
                            text = "提醒 · ${formatReminderTime(remindAt)}",
                            style = MaterialTheme.typography.labelLarge
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清除提醒",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onSetReminder(null) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---------- 文本 / 清单 模式 ----------
            // 胶囊分段控件：一篇笔记要么是文字，要么是待办清单
            PillToggle(
                labels = listOf("文本", "清单"),
                selectedIndex = if (note.checklist) 1 else 0,
                onSelect = { index ->
                    val toChecklist = index == 1
                    // 只有真的切换了才反馈；点当前模式不震
                    if (toChecklist != note.checklist) haptics.toggle(toChecklist)
                    switchMode(toChecklist)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ---------- 附件入口 ----------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { showImageSheet = true },
                    label = { Text("添加图片") },
                    shape = CircleShape,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                AudioInsertChip(onClick = { showAudioSheet = true })
            }

            // ---------- 图片附件条 ----------
            if (stripImages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                val carouselState = rememberCarouselState { stripImages.size }
                HorizontalMultiBrowseCarousel(
                    state = carouselState,
                    preferredItemWidth = 186.dp,
                    itemSpacing = 8.dp,
                    flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(state = carouselState),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(152.dp)
                ) { index ->
                    val name = stripImages[index]
                    val file = state.imageFile(name)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .maskClip(MaterialTheme.shapes.extraLarge)
                    ) {
                        AttachmentImage(
                            file = file,
                            modifier = Modifier.fillMaxSize(),
                            targetPx = 500
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { openImage(name) }
                        )
                        Surface(
                            onClick = { state.removeImage(note.id, name) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "移除图片",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 录音附件 ----------
            // 挂在笔记上、但没插进正文的录音：点播放原地听，× 从笔记里摘掉（不删原文件）
            if (stripAudio.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                stripAudio.forEach { audioId ->
                    val rec = recorder.recordings.firstOrNull { it.id == audioId }
                    val isCurrent = rec != null && recorder.loaded?.id == audioId
                    AudioAttachmentCard(
                        recording = rec,
                        playing = isCurrent && recorder.isPlaying,
                        progress = if (isCurrent && recorder.playDurationMs > 0) {
                            (recorder.positionMs.toFloat() / recorder.playDurationMs)
                                .coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        positionMs = if (isCurrent) recorder.positionMs else 0L,
                        expanded = expandedAudioId == audioId,
                        onToggleExpand = {
                            expandedAudioId = if (expandedAudioId == audioId) null else audioId
                        },
                        onTogglePlay = { rec?.let(recorder::togglePlayInline) },
                        onSeek = { fraction ->
                            rec?.let {
                                ensurePlayer(it)
                                recorder.seekTo((fraction * recorder.playDurationMs).toLong())
                            }
                        },
                        onSkip = { delta ->
                            rec?.let {
                                ensurePlayer(it)
                                recorder.skipBy(delta)
                            }
                        },
                        onRemove = { state.removeAudio(note.id, audioId) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---------- 正文 / 待办清单 ----------
            if (note.checklist) {
                ChecklistBody(
                    todos = note.todos,
                    focusTodoId = focusTodoId,
                    onFocusHandled = { focusTodoId = null },
                    onToggle = { todoId -> state.toggleTodo(note.id, todoId) },
                    onTextChange = { todoId, text ->
                        state.updateTodoText(note.id, todoId, text)
                    },
                    onRemove = { todoId -> state.removeTodo(note.id, todoId) },
                    onEnter = { todoId ->
                        // 回车续行：只有当前行写了字才新起一行，空行按回车不做事
                        val current = note.todos.firstOrNull { it.id == todoId }
                        if (current != null && current.text.isNotBlank()) {
                            focusTodoId = state.addTodo(note.id, afterId = todoId)?.id
                        }
                    },
                    onAdd = { focusTodoId = state.addTodo(note.id)?.id },
                    onClearDone = { state.clearCompletedTodos(note.id) },
                    modifier = Modifier
                        .fillMaxWidth()

                )
            } else {
                // 块编辑区：文字块可编辑，图片/录音块就是它们本身
                Column(
                    modifier = Modifier
                        .fillMaxWidth()

                        .heightIn(min = 160.dp)
                ) {
                    blocks.forEachIndexed { index, block ->
                        when (block) {
                            is ContentBlock.Text -> EditableBlock(
                                index = index,
                                text = block.text,
                                placeholder = if (index == 0) "开始写点什么…" else null,
                                focusRequested = focusRequest == index,
                                onFocusHandled = {
                                    if (focusRequest == index) focusRequest = null
                                },
                                onFocusGained = { focusedBlock = index },
                                onTextChange = { text -> updateBlockText(index, text) }
                            )

                            is ContentBlock.Image -> InlineImageBlock(
                                file = state.imageFile(block.name),
                                onOpen = { openImage(block.name) },
                                onRemove = { removeBlockAt(index) }
                            )

                            is ContentBlock.Audio -> {
                                val inlineRec = recorder.recordings.firstOrNull { it.id == block.id }
                                val isCurrent = recorder.loaded?.id == block.id
                                Box(modifier = Modifier.padding(vertical = 6.dp)) {
                                    AudioAttachmentCard(
                                        recording = inlineRec,
                                        playing = isCurrent && recorder.isPlaying,
                                        progress = if (isCurrent && recorder.playDurationMs > 0) {
                                            (recorder.positionMs.toFloat() / recorder.playDurationMs)
                                                .coerceIn(0f, 1f)
                                        } else {
                                            0f
                                        },
                                        positionMs = if (isCurrent) recorder.positionMs else 0L,
                                        expanded = expandedAudioId == block.id,
                                        onToggleExpand = {
                                            expandedAudioId =
                                                if (expandedAudioId == block.id) null else block.id
                                        },
                                        onTogglePlay = {
                                            inlineRec?.let(recorder::togglePlayInline)
                                        },
                                        onSeek = { fraction ->
                                            inlineRec?.let {
                                                ensurePlayer(it)
                                                recorder.seekTo(
                                                    (fraction * recorder.playDurationMs).toLong()
                                                )
                                            }
                                        },
                                        onSkip = { delta ->
                                            inlineRec?.let {
                                                ensurePlayer(it)
                                                recorder.skipBy(delta)
                                            }
                                        },
                                        onRemove = { removeInlineAudioAt(index, block.id) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }


        }
    }

    // ---------- 添加标签弹窗 ----------
    if (showTagDialog) {
        TagDialog(
            existingTags = state.allTags.filter { it !in note.tags },
            onDismiss = { showTagDialog = false },
            onConfirm = { tag ->
                state.addTag(note.id, tag)
                showTagDialog = false
            }
        )
    }

    // ---------- 插入录音 ----------
    if (showAudioSheet) {
        AudioPickSheet(
            recordings = recorder.recordings,
            inline = audioInline,
            onInlineChange = { audioInline = it },
            onDismiss = { showAudioSheet = false },
            onInsertAudio = { rec ->
                state.addAudio(note.id, rec.id)
                if (audioInline) {
                    ensureTextMode()
                    insertBlock(ContentBlock.Audio(rec.id))
                }
                showAudioSheet = false
            },
            onInsertTranscript = { rec -> insertTranscript(rec) }
        )
    }

    // ---------- 图片来源 / 位置选择 ----------
    if (showImageSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImageSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Text(
                text = "添加图片",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            PillToggle(
                labels = listOf("作为附件", "插入正文"),
                selectedIndex = if (insertInline) 1 else 0,
                onSelect = { index -> insertInline = index == 1 },
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Text(
                text = if (insertInline) {
                    "图片会直接显示在正文里，点一下看大图"
                } else {
                    "图片挂在正文上方的图片条里"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            ListItem(
                headlineContent = { Text("从相册选择") },
                modifier = Modifier.clickable {
                    showImageSheet = false
                    pickLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )
            ListItem(
                headlineContent = { Text("拍照") },
                modifier = Modifier.clickable {
                    showImageSheet = false
                    val target = state.prepareCameraTarget()
                    if (target != null) {
                        pendingCameraName = target.first
                        cameraLauncher.launch(target.second)
                    }
                }
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // ---------- 提醒设置 ----------
    if (showReminderSheet) {
        ReminderSheet(
            current = note.remindAt,
            onDismiss = { showReminderSheet = false },
            onPick = { at ->
                showReminderSheet = false
                onSetReminder(at)
            }
        )
    }
}

@Composable
private fun rememberEditorFocusVisibility(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, imeBottom) {
        if (focused) requester.bringIntoView()
    }
    return Modifier.bringIntoViewRequester(requester)
        .onFocusChanged { focused = it.isFocused }
}

// ============ 块编辑器 ============

/**
 * 一个可编辑的文字块。
 *
 * 自己保存 [TextFieldValue]（而不是每次从字符串重建），光标和选区才不会在
 * 每次按键后被重置；[text] 变化来自外部（换笔记、切模式）时才同步回来。
 */
@Composable
private fun EditableBlock(
    index: Int,
    text: String,
    placeholder: String?,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onFocusGained: () -> Unit,
    onTextChange: (String) -> Unit
) {
    var value by remember(index) { mutableStateOf(TextFieldValue(text)) }
    val focusRequester = remember(index) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(text) {
        if (text != value.text) {
            value = TextFieldValue(text, TextRange(text.length))
        }
    }

    LaunchedEffect(focusRequested) {
        if (focusRequested) {
            focusRequester.requestFocus()
            keyboard?.show()
            onFocusHandled()
        }
    }

    BasicTextField(
        value = value,
        onValueChange = { next ->
            value = next
            onTextChange(next.text)
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .focusRequester(focusRequester)
            .then(rememberEditorFocusVisibility())
            .onFocusChanged { state -> if (state.isFocused) onFocusGained() },
        decorationBox = { innerTextField ->
            Box {
                if (value.text.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                innerTextField()
            }
        }
    )
}

/** 正文里的图片块：真的把图画出来，右上角一枚 × 从正文移除（文件仍作为附件保留） */
@Composable
private fun InlineImageBlock(
    file: File,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        InlineAttachmentImage(
            file = file,
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth()
        )
        Surface(
            onClick = onRemove,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(26.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "从正文移除",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ============ 底部状态栏 ============

@Composable
private fun EditorStatus(content: String, note: Note) {
    val images = (note.images + InlineImage.namesIn(content)).distinct().size
    val audios = (note.audio + InlineAudio.idsIn(content)).distinct().size
    val summary = buildList {
        add(if (note.checklist) "${note.doneCount}/${note.todos.size} 已完成" else "${stripMedia(content).length} 字")
        if (images > 0) add("$images 张图片")
        if (audios > 0) add("$audios 段录音")
    }.joinToString("  ·  ")
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Text(summary, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("最近编辑 · ${formatNoteTime(note.updatedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============ 添加标签弹窗 ============

@Composable
private fun TagDialog(
    existingTags: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加标签") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text("标签名") },
                    placeholder = { Text("比如：工作") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(input) }),
                    modifier = Modifier.fillMaxWidth()
                )

                if (existingTags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "已有标签",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        items(existingTags, key = { it }) { tag ->
                            AssistChip(
                                onClick = { onConfirm(tag) },
                                label = {
                                    Text(
                                        text = "#$tag",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input) }, enabled = input.isNotBlank()) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 去掉输入框的底色和下划线，看起来像一张纸 */
@Composable
private fun transparentTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent
)

// ============ 清单编辑区 ============

/**
 * 清单模式的正文区：顶部进度，下面一行一条待办。
 *
 * 每一行是独立的输入框（不是一整段文本），所以点哪行改哪行，
 * 勾选和改字不会互相干扰。
 */
@Composable
private fun ChecklistBody(
    todos: List<TodoItem>,
    focusTodoId: String?,
    onFocusHandled: () -> Unit,
    onToggle: (String) -> Unit,
    onTextChange: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onEnter: (String) -> Unit,
    onAdd: () -> Unit,
    onClearDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val done = todos.count { it.done }
    Column(modifier = modifier) {

        // ---------- 进度 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$done / ${todos.size} 已完成",
                style = MaterialTheme.typography.labelLarge,
                color = if (done == todos.size && todos.isNotEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.weight(1f))
            if (done > 0) {
                PillButton(
                    text = "清除已完成",
                    onClick = onClearDone,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        TodoProgressBar(
            done = done,
            total = todos.size,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
        )

        // ---------- 待办行 ----------
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            todos.forEach { todo ->
                key(todo.id) {
                    TodoEditRow(
                        todo = todo,
                        focusRequested = focusTodoId == todo.id,
                        onFocusHandled = onFocusHandled,
                        onToggle = { onToggle(todo.id) },
                        onTextChange = { text -> onTextChange(todo.id, text) },
                        onRemove = { onRemove(todo.id) },
                        onEnter = { onEnter(todo.id) }
                    )
                }
            }
        }

        // The add action follows the checklist in the shared editor scroller.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable {
                    onAdd()
                }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "添加待办",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 一行可编辑的待办。
 *
 * 文字先存在本地（[text]）再延迟 400ms 写库：沿用编辑页「停手才存」的做法，
 * 免得每敲一个字都写一次 notes.json。勾选是立即生效的，手感不能等。
 */
@Composable
private fun TodoEditRow(
    todo: TodoItem,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onToggle: () -> Unit,
    onTextChange: (String) -> Unit,
    onRemove: () -> Unit,
    onEnter: () -> Unit
) {
    var text by remember(todo.id) { mutableStateOf(todo.text) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 外部改动了这一项（切换模式、导入备份）时同步回来
    LaunchedEffect(todo.text) {
        if (todo.text != text) text = todo.text
    }

    // 刚加的新行自动聚焦并弹出键盘，接着敲下一件事
    LaunchedEffect(focusRequested) {
        if (focusRequested) {
            focusRequester.requestFocus()
            keyboard?.show()
            onFocusHandled()
        }
    }

    LaunchedEffect(todo.id, text) {
        if (text == todo.text) return@LaunchedEffect
        delay(400)
        onTextChange(text)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TodoCheck(checked = todo.done, onToggle = onToggle)

        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = if (todo.done) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textDecoration = if (todo.done) TextDecoration.LineThrough else null
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onEnter() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
            .then(rememberEditorFocusVisibility()),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.padding(vertical = 10.dp)) {
                    if (text.isEmpty()) {
                        Text(
                            text = "待办事项",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    innerTextField()
                }
            }
        )

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "删除这一项",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ============ 录音附件 ============

/** 附件区里的「插入录音」胶囊入口 */
@Composable
private fun AudioInsertChip(onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text("插入录音") },
        shape = CircleShape,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    )
}

/**
 * 笔记里的录音卡片：收起时紧凑一行，点标题区向下展开操作区。
 *
 * 展开区有 WavyScrubber（可拖进度）、当前/总时长、±5 秒快进快退；
 * 播放/暂停一直在左侧圆钮上。
 *
 * [recording] 为 null 表示原录音已被删掉——卡片降级成占位提示，
 * 播放禁用、不能展开，但笔记正文和其余附件不受影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioAttachmentCard(
    recording: Recording?,
    playing: Boolean,
    progress: Float,
    positionMs: Long,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkip: (Long) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var scrubPreview by remember(recording?.id) { mutableStateOf<Float?>(null) }
    val scheme = MaterialTheme.colorScheme
    val available = recording != null
    val shownMs = scrubPreview?.let { (it * (recording?.durationMs ?: 0L)).toLong() } ?: positionMs
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200), label = "audioCardChevron"
    )
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = onTogglePlay, enabled = available,
                    shape = CircleShape, modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "暂停录音" else "播放录音",
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier.weight(1f).clickable(enabled = available, onClick = onToggleExpand)
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        recording?.displayName ?: "录音不可用",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (recording == null) "原文件已不在，可移除此引用" else buildString {
                            append(formatDuration(recording.durationMs))
                            append(if (playing) " · 正在播放" else " · 录音")
                            if (recording.hasTranscript) append(" · 已转写")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                if (available) {
                    IconButton(onClick = onToggleExpand) {
                        Icon(Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "收起播放器" else "展开播放器",
                            modifier = Modifier.rotate(chevron))
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "录音选项")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("从笔记移除", color = scheme.error) },
                            onClick = { menuOpen = false; confirmRemove = true }
                        )
                    }
                }
            }
            if (available && !expanded && (playing || progress > 0f)) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(start = 60.dp, end = 12.dp).height(3.dp),
                    color = scheme.primary, trackColor = scheme.surfaceContainerHighest,
                    drawStopIndicator = {}, gapSize = 0.dp
                )
                Spacer(Modifier.height(4.dp))
            }
            if (recording != null) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Spacer(Modifier.height(8.dp))
                        WavyScrubber(
                            progress = { progress },
                            onScrub = { scrubPreview = it },
                            onScrubFinished = { fraction ->
                                scrubPreview = null
                                onSeek(fraction)
                            },
                            activeColor = scheme.primary,
                            trackColor = scheme.onSurfaceVariant.copy(alpha = 0.24f),
                            isPlaying = playing,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatDuration(shownMs), style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant)
                            Text(formatDuration(recording.durationMs), style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { onSkip(-5_000L) }) {
                                Icon(Icons.Default.Replay5, contentDescription = "后退5秒")
                            }
                            Text("快速定位", style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                            IconButton(onClick = { onSkip(5_000L) }) {
                                Icon(Icons.Default.Forward5, contentDescription = "前进5秒")
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("移除录音引用？") },
            text = { Text("仅从当前笔记移除这一处引用，不删除录音库中的原文件。") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 插入录音的底部选择器。
 *
 * 顶部先定位置：「挂成附件」/「插到正文」。
 * 每行右侧是一对相连的胶囊（Material 3 Expressive 的 split button 几何）：
 * 「文字」把文字稿插到光标处，「音频」按上面选的方式插入录音本身。
 */
// Audio picker moved to AudioPickSheet.kt (R3.2).
