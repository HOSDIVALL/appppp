package com.example.myapplicationfirstapp

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.example.myapplicationfirstapp.data.AppSettings
import com.example.myapplicationfirstapp.data.Note
import com.example.myapplicationfirstapp.data.NotesRepository
import com.example.myapplicationfirstapp.data.ReminderScheduler
import com.example.myapplicationfirstapp.data.ThemeMode
import com.example.myapplicationfirstapp.data.formatReminderTime
import com.example.myapplicationfirstapp.reminder.ReminderNotifications
import com.example.myapplicationfirstapp.ui.AppearanceDialog
import com.example.myapplicationfirstapp.ui.FontSettingsDialog
import com.example.myapplicationfirstapp.ui.FontState
import com.example.myapplicationfirstapp.ui.HomeTab
import com.example.myapplicationfirstapp.ui.ImagePager
import com.example.myapplicationfirstapp.ui.NoteEditorScreen
import com.example.myapplicationfirstapp.ui.NotePickerSheet
import com.example.myapplicationfirstapp.ui.NotesListScreen
import com.example.myapplicationfirstapp.ui.NotesNavBar
import com.example.myapplicationfirstapp.ui.NotesState
import com.example.myapplicationfirstapp.ui.RecordScreen
import com.example.myapplicationfirstapp.ui.RecordingActionSheet
import com.example.myapplicationfirstapp.ui.recorderPalette
import com.example.myapplicationfirstapp.ui.recorderPalette
import com.example.myapplicationfirstapp.ui.RecordingDetailScreen
import com.example.myapplicationfirstapp.ui.RecorderState
import com.example.myapplicationfirstapp.ui.SettingsScreen
import com.example.myapplicationfirstapp.ui.TrashScreen
import com.example.myapplicationfirstapp.ui.i18n.AppLanguage
import com.example.myapplicationfirstapp.ui.i18n.LocalStrings
import com.example.myapplicationfirstapp.ui.i18n.stringsFor
import com.example.myapplicationfirstapp.ui.haptics.HapticCue
import com.example.myapplicationfirstapp.ui.haptics.rememberAppHaptics
import com.example.myapplicationfirstapp.ui.theme.PixelTheme
import com.example.myapplicationfirstapp.ui.theme.appTypography
import com.example.myapplicationfirstapp.ui.theme.customFontFamily
import com.example.myapplicationfirstapp.ui.theme.themePresetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** 从通知点进来时要打开的笔记 id；消费掉就置空 */
    private var pendingNoteId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 真正的边到边显示：内容铺到状态栏和导航栏后面，和系统应用一致
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        pendingNoteId = intent?.getStringExtra(ReminderNotifications.EXTRA_OPEN_NOTE)
        ReminderNotifications.ensureChannel(this)

        setContent {
            NotesApp(
                openNoteId = pendingNoteId,
                onOpenNoteHandled = { pendingNoteId = null }
            )
        }
    }

    // App 在后台时点通知，走这里而不是重新创建 Activity
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(ReminderNotifications.EXTRA_OPEN_NOTE)?.let { pendingNoteId = it }
    }
}

/**
 * App 的根节点：负责「现在该显示哪个页面」。
 *
 * 三个页面：列表 / 编辑 / 回收站。
 * 仍然不用 Navigation 库：三个页面两个变量就够了，
 * 等页面多起来、需要转场动画和深链接时再引入 navigation-compose。
 *
 * 最外层的 Box 只为一件事：全屏看图要盖住所有内容（含 FAB、Snackbar），
 * 又必须铺到状态栏和导航栏后面，所以放在 Scaffold 外面单独叠一层。
 */
@Composable
fun NotesApp(
    openNoteId: String? = null,
    onOpenNoteHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings = remember { AppSettings(context) }

    val state = remember {
        NotesState(
            repository = NotesRepository(context),
            settings = settings,
            scope = scope,
            scheduler = ReminderScheduler(context)
        )
    }

    // 主题字体：选项 + 已导入的自定义字体（立即持久化）
    val fontState = remember { FontState(context) }

    // 录音页的状态：录音机 / 播放器 / 文件列表
    val recorder = remember { RecorderState(context, settings, scope) }

    // 全局触觉入口：语义化、单次触发，不跟重组绑定
    val haptics = rememberAppHaptics()

    // 录音流程的一次性结果事件（暂停 / 保存 / 删除 / 失败）在这里消费。
    // SharedFlow 没有 replay：重组、旋转、从别的页面回来都不会重放旧事件。
    LaunchedEffect(recorder, haptics) {
        recorder.hapticCues.collect { cue ->
            when (cue) {
                HapticCue.Paused -> haptics.toggleOff()
                HapticCue.Saved -> haptics.success()
                HapticCue.Discarded -> haptics.success()
                HapticCue.Failure -> haptics.failure()
            }
        }
    }

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var newNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var showFontSettings by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Notes GO 2.0：主题预设 + 界面语言（都立即持久化）
    var themePresetId by remember { mutableStateOf(settings.themePresetId) }
    var language by remember { mutableStateOf(AppLanguage.fromTag(settings.appLanguage)) }
    val strings = remember(language) { stringsFor(language) }
    val themePreset = themePresetOf(themePresetId)

    // 主页的两个页签：笔记 / 录音
    var homeTab by rememberSaveable { mutableStateOf(HomeTab.NOTES) }

    // 正在查看的录音（详情页），null 表示在录音列表
    var openedRecordingId by rememberSaveable { mutableStateOf<String?>(null) }

    // 全局深浅色：跟随系统 / 浅色 / 深色
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // 选好备份文件后先弹确认框，确认了才真的覆盖
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // 系统没给「精确闹钟」时提示一次，别每次都弹
    var showExactAlarmHint by remember { mutableStateOf(false) }

    // 三点抽屉正在操作的那段录音 / 重命名对话框
    var sheetRecordingId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    // 「添加到笔记」：正在挑笔记的那段录音
    var notePickerRecordingId by rememberSaveable { mutableStateOf<String?>(null) }


    // 编辑页在退场动画期间 editingId 已经被清空，
    // 这里留一份最后可见的快照，抽屉滑下去的过程才不会突然变空白。
    // 放在根作用域是因为编辑页现在是叠在 Scaffold 外面的一层
    var editorMemo by remember { mutableStateOf(state.findNote(editingId)) }
    LaunchedEffect(editingId) {
        if (editingId != null) editorMemo = state.findNote(editingId)
    }

    // 全屏看图：图片清单 + 从第几张开始
    var viewerFiles by remember { mutableStateOf<List<File>?>(null) }
    var viewerIndex by remember { mutableStateOf(0) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { state.load() }

    /** 新建笔记 / 待办：导航栏的 + 和菜单里的「新建待办」共用这一处 */
    fun createNoteNow(checklist: Boolean = false) {
        val note = state.createNote(checklist)
        newNoteId = note.id
        editingId = note.id
    }

    // ---------- 录音：权限 + 起飞 ----------

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            recorder.start()
        } else {
            scope.launch { snackbarHostState.showSnackbar("没有麦克风权限就没法录音") }
        }
    }

    /** 点导航栏那颗橙色麦克风 */
    fun startRecording() {
        if (recorder.isRecording) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            recorder.start()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** 把录音当音频文件分享出去 */
    fun shareRecording(recording: com.example.myapplicationfirstapp.data.Recording) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                recording.file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享录音"))
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("分享失败：${it.message}") }
        }
    }

    // 切到录音页时刷新文件列表；切走就停掉播放，正在录的也一并收尾
    LaunchedEffect(homeTab, state.loaded) {
        if (homeTab == HomeTab.RECORD) {
            if (state.loaded) recorder.load()
        } else {
            recorder.pausePlayback()
            if (recorder.isRecording) recorder.stop()
        }
    }

    // 退出时释放麦克风和扬声器
    DisposableEffect(Unit) {
        onDispose { recorder.release() }
    }

    // 录音中按返回：先收尾保存，别让用户觉得「按一下键就丢了」
    BackHandler(enabled = recorder.isRecording) {
        recorder.stop(discard = false)
    }

    // 在录音页按系统返回：退回笔记页，而不是直接退出 App
    BackHandler(
        enabled = homeTab == HomeTab.RECORD &&
            !recorder.isRecording &&
            openedRecordingId == null &&
            editingId == null &&
            !showTrash
    ) {
        homeTab = HomeTab.NOTES
    }

    // 录音详情页：退回录音列表。
    // 之前这里没有任何拦截——上面那条要求 openedRecordingId == null，
    // 人在详情页时不成立；录音中那条也不成立。两条都不管，
    // 系统就按默认行为直接把 Activity 结束掉，看起来就是「按返回 App 退了」。
    // Compose 的 BackHandler 后注册的先响应，所以这条写在最后，优先级最高。
    BackHandler(enabled = openedRecordingId != null && !recorder.isRecording) {
        recorder.releasePlayer()
        openedRecordingId = null
    }

    // 导出：系统文件选择器给一个可写位置，不需要存储权限
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            val count = state.exportBackup(uri)
            snackbarHostState.showSnackbar(
                if (count >= 0) "已导出 $count 条笔记和图片" else "导出失败，换个位置再试"
            )
        }
    }

    // 导入：先挑文件，确认后再覆盖
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    // 字体导入：挑 .ttf / .otf → 拷进内部存储 → 校验可用 → 立即切换
    val fontImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val font = runCatching {
                withContext(Dispatchers.IO) { importFont(context, uri) }
            }
            showFontSettings = false
            font
                .onSuccess { (file, name) ->
                    fontState.importSucceeded(file, name)
                    snackbarHostState.showSnackbar("已导入字体「$name」")
                }
                .onFailure {
                    snackbarHostState.showSnackbar("这个文件不是有效的 .ttf / .otf 字体")
                }
        }
    }

    // 把当前字体选项换算成 Typography；选项或文件路径变化时整个 App 字体一起换
    val appFontFamily = remember(fontState.option, fontState.customFontPath) {
        fontState.resolveFamily()
    }
    val customPreviewFamily = remember(fontState.customFontPath) {
        customFontFamily(fontState.customFontPath?.let(::File))
    }

    // Android 13+ 发通知要用户点同意；设提醒时顺手申请，被拒也不影响闹钟本身
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 系统是否允许我们排「精确到分钟」的闹钟（Android 12+ 需要用户单独授权） */
    fun canScheduleExactReminders(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    // 点通知进来：等笔记加载完，直接跳到那一条
    LaunchedEffect(openNoteId, state.loaded) {
        val target = openNoteId
        if (target != null && state.loaded) {
            if (state.findNote(target) != null) {
                showTrash = false
                editingId = target
            }
            onOpenNoteHandled()
        }
    }

    // 当前在哪个页面；底部导航只挂在主页（笔记 / 录音）上
    val page = when {
        showSettings -> AppPage.Settings
        showTrash -> AppPage.Trash
        editingId != null -> AppPage.Editor
        openedRecordingId != null -> AppPage.Recording
        else -> AppPage.List
    }

    // 录音时整页切到录制界面，导航栏一起让位——和参考稿一样，录制时不该有别的东西
    val showNavBar = page == AppPage.List && state.loaded && !recorder.isRecording

    // 导航栏浮在底部，Snackbar 得跟着抬高，否则会被它盖住
    val snackbarBottom by animateDpAsState(
        targetValue = if (showNavBar) 92.dp else 0.dp,
        animationSpec = spring(dampingRatio = 1f, stiffness = 320f),
        label = "snackbarBottom"
    )

    PixelTheme(
        darkTheme = darkTheme,
        preset = themePreset,
        typography = remember(appFontFamily) { appTypography(appFontFamily) }
    ) {
    CompositionLocalProvider(LocalStrings provides strings) {
    Box(modifier = Modifier.fillMaxSize()) {

        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(bottom = snackbarBottom)
                )
            }
        ) { padding ->
            if (!state.loaded) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            // ---------- 页面切换动画（Material 共享轴 X） ----------
            // 前进（列表 → 编辑 / 回收站）：新页从右侧滑入，旧页往左淡出；
            // 返回则反向。深度用来判断方向：列表是根，编辑和回收站都是一层。
            // 编辑页不参与这层切换：它单独叠在上面当抽屉。
            // 所以底层的目标页在编辑状态下保持在列表页，列表页会一直留在抽屉背后
            val basePage = if (page == AppPage.Editor) AppPage.List else page

            AnimatedContent(
                targetState = basePage,
                transitionSpec = {
                    val dir = if (targetState.depth >= initialState.depth) 1 else -1
                    (slideInHorizontally(
                        animationSpec = spring(dampingRatio = 1f, stiffness = 340f)
                    ) { dir * it / 3 } +
                        fadeIn(spring(stiffness = Spring.StiffnessMediumLow))) togetherWith
                        (fadeOut(tween(100)) +
                            slideOutHorizontally(
                                animationSpec = spring(dampingRatio = 1f, stiffness = 460f)
                            ) { -dir * it / 4 })
                },
                label = "pageSwitch"
            ) { current ->
                val contentModifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .zIndex(if (current == page) 2f else 1f)

                when (current) {
                    AppPage.Settings -> SettingsScreen(
                        preset = themePreset,
                        language = language,
                        themeMode = themeMode,
                        trashedCount = state.trashedNotes.size,
                        dark = darkTheme,
                        onBack = { showSettings = false },
                        onSelectPreset = { p ->
                            themePresetId = p.id
                            settings.themePresetId = p.id
                        },
                        onSelectLanguage = { l ->
                            language = l
                            settings.appLanguage = l.tag
                        },
                        onSelectThemeMode = { mode ->
                            // 真的换了模式才给一次反馈；点已选中的那一项不震
                            if (mode != themeMode) haptics.toggleOn()
                            themeMode = mode
                            settings.themeMode = mode
                        },
                        onOpenFontSettings = { showFontSettings = true },
                        onOpenTrash = {
                            showSettings = false
                            showTrash = true
                        },
                        onExport = {
                            val stamp = SimpleDateFormat(
                                "yyyyMMdd-HHmm",
                                Locale.getDefault()
                            ).format(Date())
                            exportLauncher.launch("notes-backup-$stamp.zip")
                        },
                        onImport = {
                            importLauncher.launch(
                                arrayOf("application/zip", "application/octet-stream", "*/*")
                            )
                        },
                        modifier = contentModifier
                    )

                    AppPage.Trash -> TrashScreen(
                        notes = state.trashedNotes,
                        onRestore = { note ->
                            state.restoreFromTrash(note.id)
                            scope.launch {
                                snackbarHostState.showSnackbar("已恢复「${note.displayTitle}」")
                            }
                        },
                        onDeleteForever = { note ->
                            state.deleteForever(note.id)
                            scope.launch {
                                snackbarHostState.showSnackbar("已彻底删除「${note.displayTitle}」")
                            }
                        },
                        onEmpty = {
                            val count = state.emptyTrash()
                            if (count > 0) {
                                scope.launch { snackbarHostState.showSnackbar("已清空 $count 条笔记") }
                            }
                        },
                        onBack = { showTrash = false },
                        modifier = contentModifier
                    )

                    AppPage.Recording -> {
                        val target = recorder.recordings.firstOrNull { it.id == openedRecordingId }
                        if (target != null) {
                            RecordingDetailScreen(
                                recording = target,
                                state = recorder,
                                dark = darkTheme,
                                onBack = {
                                    recorder.releasePlayer()
                                    openedRecordingId = null
                                },
                                onDelete = {
                                    recorder.delete(target)
                                    openedRecordingId = null
                                },
                                onMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                },
                                onTranscribe = { recorder.transcribe(target) },
                                modelReady = recorder.modelReady,
                                transcribing = recorder.transcribingId == target.id,
                                transcribeProgress = recorder.transcribeProgress,
                                modifier = contentModifier
                            )
                        } else {
                            Box(modifier = contentModifier)
                        }
                    }

                    else -> AnimatedContent(
                        targetState = homeTab,
                        // 参考 Rhythm：新页淡入的同时轻微放大、上移一点点，旧页快速淡出。
                        // 不用左右滑——两个平级页签横向滑动会让人以为有层级关系。
                        transitionSpec = {
                            // 页签之间横着走：旧页向左推出，新页从右边推进来，
                            // 两页同速，像一张连续的纸在滑
                            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (slideInHorizontally(
                                animationSpec = spring(dampingRatio = 1f, stiffness = 380f)
                            ) { width -> direction * width } +
                                fadeIn(spring(stiffness = Spring.StiffnessMediumLow))) togetherWith
                                (slideOutHorizontally(
                                    animationSpec = spring(dampingRatio = 1f, stiffness = 420f)
                                ) { width -> -direction * width } + fadeOut(tween(120)))
                        },
                        label = "homeTab"
                    ) { tab ->
                        when (tab) {
                            HomeTab.NOTES -> NotesListScreen(
                                state = state,
                                onOpen = { id -> editingId = id },
                                onDelete = { note ->
                                    trashNote(state, note, snackbarHostState, scope, strings)
                                },
                                onOpenSettings = { showSettings = true },
                                modifier = contentModifier
                            )

                            HomeTab.RECORD -> RecordScreen(
                                state = recorder,
                                dark = darkTheme,
                                onOpen = { recording ->
                                    recorder.open(recording)
                                    openedRecordingId = recording.id
                                },
                                onOpenActions = { recording -> sheetRecordingId = recording.id },
                                modifier = contentModifier
                            )
                        }
                    }
                }
            }
        }

        // ---------- 编辑页：抽屉式叠加在列表之上 ----------
        // 单独一层而不是塞进 AnimatedContent 的 when 里，
        // 这样列表页会一直留在底下不动，抽屉顶上的圆角才能透出后面的内容
        AnimatedVisibility(
            visible = page == AppPage.Editor,
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 1f, stiffness = 320f)
            ) { height -> height } + fadeIn(tween(110)),
            exit = slideOutVertically(
                animationSpec = spring(dampingRatio = 1f, stiffness = 380f)
            ) { height -> height } + fadeOut(tween(120)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(4f)
        ) {
            // 退场动画期间 editingId 已被清空，退回 memo 里最后一份快照
            // 活跃时仍优先取实时数据，保证输入/自动保存正常渲染
            val editingNote = state.findNote(editingId) ?: editorMemo
            if (editingNote != null) {
                NoteEditorScreen(
                    note = editingNote,
                    state = state,
                    recorder = recorder,
                    onDelete = {
                        val trashed = state.moveToTrash(editingNote.id)
                        editingId = null
                        newNoteId = null
                        if (trashed != null) {
                            scope.launch {
                                showUndo(
                                    host = snackbarHostState,
                                    message = "已移到回收站「${trashed.displayTitle}」",
                                    onUndo = { state.restoreFromTrash(trashed.id) }
                                )
                            }
                        }
                    },
                    onBack = {
                        // 新建后什么都没写就返回：直接丢掉，不污染回收站
                        val current = state.findNote(editingNote.id)
                        if (current != null &&
                            current.id == newNoteId &&
                            current.title.isBlank() &&
                            current.content.isBlank() &&
                            current.images.isEmpty() &&
                            current.todos.none { it.text.isNotBlank() }
                        ) {
                            state.deleteForever(current.id)
                        }
                        newNoteId = null
                        editingId = null
                    },
                    onOpenImages = { files, index ->
                        viewerFiles = files
                        viewerIndex = index
                    },
                    onSetReminder = { at ->
                        if (at != null) ensureNotificationPermission()
                        state.setReminder(editingNote.id, at)

                        // 第一次设提醒时提示开精确闹钟：不开也能响，只是可能晚几分钟
                        if (at != null && !canScheduleExactReminders() &&
                            !settings.exactAlarmHintShown
                        ) {
                            settings.exactAlarmHintShown = true
                            showExactAlarmHint = true
                        }

                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (at == null) {
                                    "已清除提醒"
                                } else {
                                    "提醒已设到 ${formatReminderTime(at)}"
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    // 刚由「+」新建出来的这条才自动落焦点；从列表点开的已有笔记不抢
                    autoFocusBody = editingNote.id == newNoteId
                )
            }
        }

        // ---------- 底部导航：笔记 / 录音 + 圆形加号 ----------
        AnimatedVisibility(
            visible = showNavBar,
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 1f, stiffness = 360f)
            ) { it } + fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = slideOutVertically(
                animationSpec = spring(dampingRatio = 1f, stiffness = 480f)
            ) { it } + fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(2f)
        ) {
            NotesNavBar(
                selected = homeTab,
                onSelect = { homeTab = it },
                onAction = {
                    if (homeTab == HomeTab.NOTES) createNoteNow() else startRecording()
                },
            )
        }

        // ---------- 全屏看图 ----------
        val files = viewerFiles
        if (files != null) {
            val visibility = remember(files) {
                MutableTransitionState(false).apply { targetState = true }
            }
            val viewerKeyboard = LocalSoftwareKeyboardController.current
            val viewerFocus = LocalFocusManager.current
            LaunchedEffect(files) {
                // Viewing a photo is not a text-input operation.
                viewerKeyboard?.hide()
                viewerFocus.clearFocus(force = true)
            }
            LaunchedEffect(visibility.isIdle, visibility.currentState, visibility.targetState) {
                // Keep the content mounted until the exit transition has finished.
                if (visibility.isIdle && !visibility.currentState && !visibility.targetState) {
                    viewerFiles = null
                }
            }
            Box(modifier = Modifier.fillMaxSize().zIndex(5f)) {
                AnimatedVisibility(
                    visibleState = visibility,
                    modifier = Modifier.fillMaxSize(),
                    enter = fadeIn(animationSpec = tween(220)) + scaleIn(
                        initialScale = 0.97f,
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ),
                    exit = fadeOut(animationSpec = tween(160)) + scaleOut(
                        targetScale = 0.98f,
                        animationSpec = tween(160, easing = FastOutSlowInEasing)
                    )
                ) {
                    ImagePager(
                        files = files,
                        initialIndex = viewerIndex,
                        onDismiss = { visibility.targetState = false }
                    )
                }
            }
        }
    }

    // ---------- 录音操作抽屉 ----------
    val sheetRecording = sheetRecordingId?.let { id ->
        recorder.recordings.firstOrNull { it.id == id }
    }
    if (sheetRecording != null) {
        RecordingActionSheet(
            recording = sheetRecording,
            palette = recorderPalette(darkTheme),
            playing = recorder.isPlaying && recorder.loaded?.id == sheetRecording.id,
            transcribing = recorder.transcribingId == sheetRecording.id,
            modelReady = recorder.modelReady,
            onDismiss = { sheetRecordingId = null },
            onTogglePlay = { recorder.togglePlayInline(sheetRecording) },
            onAddToNote = {
                notePickerRecordingId = sheetRecording.id
                sheetRecordingId = null
            },
            onOpenDetail = {
                recorder.open(sheetRecording)
                openedRecordingId = sheetRecording.id
                sheetRecordingId = null
            },
            onTranscribe = { recorder.transcribe(sheetRecording) },
            onRename = {
                renameTargetId = sheetRecording.id
                renameText = sheetRecording.title
                sheetRecordingId = null
            },
            onToggleStar = { recorder.toggleStar(sheetRecording) },
            onShare = { shareRecording(sheetRecording) },
            onDelete = {
                recorder.delete(sheetRecording)
                sheetRecordingId = null
            }
        )
    }

    // ---------- 添加到笔记：先挑一条笔记 ----------
    notePickerRecordingId?.let { id ->
        val rec = recorder.recordings.firstOrNull { it.id == id }
        if (rec != null) {
            NotePickerSheet(
                recording = rec,
                notes = state.notes
                    .filter { it.deletedAt == null }
                    .sortedByDescending { it.updatedAt },
                onDismiss = { notePickerRecordingId = null },
                onPick = { note, inline ->
                    if (inline) {
                        state.addAudioToBody(note.id, rec.id)
                    } else {
                        state.addAudio(note.id, rec.id)
                    }
                    notePickerRecordingId = null
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (inline && !note.checklist) {
                                "已插到「${note.displayTitle}」正文末尾"
                            } else {
                                "已添加到「${note.displayTitle}」"
                            }
                        )
                    }
                }
            )
        }
    }

    // ---------- 重命名 ----------
    renameTargetId?.let { id ->
        val target = recorder.recordings.firstOrNull { it.id == id }
        if (target != null) {
            AlertDialog(
                onDismissRequest = { renameTargetId = null },
                title = { Text("重命名") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        shape = CircleShape,
                        placeholder = { Text("留空就用日期") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            recorder.rename(target, renameText)
                            renameTargetId = null
                        }
                    ) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { renameTargetId = null }) { Text("取消") }
                }
            )
        }
    }

    // ---------- 导入确认 ----------
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("导入备份") },
            text = {
                Text("备份会覆盖当前的 ${state.notes.size} 条笔记和全部图片，确定导入吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImportUri = null
                        scope.launch {
                            val count = state.importBackup(uri)
                            editingId = null
                            newNoteId = null
                            showTrash = false
                            snackbarHostState.showSnackbar(
                                if (count >= 0) "已导入 $count 条笔记"
                                else "这个文件不是有效的备份"
                            )
                        }
                    }
                ) {
                    Text("覆盖导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text("取消") }
            }
        )
    }

    // ---------- 精确闹钟提示 ----------
    if (showExactAlarmHint) {
        AlertDialog(
            onDismissRequest = { showExactAlarmHint = false },
            title = { Text("让提醒更准时") },
            text = {
                Text("系统为了省电，默认会把提醒往后挪几分钟到几十分钟。允许「闹钟和提醒」权限后，到点就会准时响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExactAlarmHint = false
                        openExactAlarmSettings(context)
                    }
                ) {
                    Text("去开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExactAlarmHint = false }) { Text("先这样") }
            }
        )
    }

    // ---------- 字体设置 ----------
    if (showFontSettings) {
        FontSettingsDialog(
            selected = fontState.option,
            customName = fontState.customFontName,
            customFamily = customPreviewFamily,
            onSelect = { fontState.select(it) },
            onImportFont = { fontImportLauncher.launch(arrayOf("*/*")) },
            onClearCustom = {
                fontState.clearCustom()
                showFontSettings = false
                scope.launch { snackbarHostState.showSnackbar("已清除自定义字体") }
            },
            onDismiss = { showFontSettings = false }
        )
    }
    }
    }
}

/**
 * 跳到系统的「闹钟和提醒」授权页。
 * 部分 ROM 没有这个页面，就退回应用详情页让用户自己找。
 */
private fun openExactAlarmSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    }

    runCatching { context.startActivity(direct) }
        .recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            )
        }
}

/** 移到回收站 + 底部「撤销」 */
private fun trashNote(
    state: NotesState,
    note: Note,
    host: SnackbarHostState,
    scope: CoroutineScope,
    strings: com.example.myapplicationfirstapp.ui.i18n.AppStrings
) {
    val trashed = state.moveToTrash(note.id) ?: return
    scope.launch {
        showUndo(
            host = host,
            message = strings.movedToTrash(trashed.displayTitle),
            undoLabel = strings.undo,
            onUndo = { state.restoreFromTrash(trashed.id) }
        )
    }
}

private suspend fun showUndo(
    host: SnackbarHostState,
    message: String,
    undoLabel: String = "撤销",
    onUndo: () -> Unit
) {
    val result = host.showSnackbar(
        message = message,
        actionLabel = undoLabel,
        withDismissAction = true,
        duration = SnackbarDuration.Short
    )
    if (result == SnackbarResult.ActionPerformed) onUndo()
}

/**
 * 把用户挑的字体文件拷进内部存储（覆盖上一份），
 * 并用系统 Typeface 试解析——解不开就抛异常，让调用方提示格式无效。
 * 返回 [文件, 展示名]（展示名去掉扩展名）。
 */
private fun importFont(context: Context, uri: Uri): Pair<File, String> {
    val name = queryDisplayName(context, uri)
        ?: uri.lastPathSegment
        ?: "字体.ttf"
    val ext = if (name.endsWith(".otf", ignoreCase = true)) ".otf" else ".ttf"
    val dir = File(context.filesDir, "fonts").apply { mkdirs() }
    val target = File(dir, "custom_font$ext")
    target.delete()
    context.contentResolver.openInputStream(uri)!!.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    Typeface.createFromFile(target) // 校验，失败抛异常
    return target to name.substringBeforeLast('.')
}

/** SAF 文件的真实文件名（选择器里看到的名字） */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

/**
 * 页面枚举：[AnimatedContent] 的目标状态。
 * [depth] 决定切换方向：列表是根页面（depth 0），编辑 / 回收站都是第二层，
 * 目标比出发页深 → 前进（新页从右进），否则 → 返回（新页从左进）。
 */
private enum class AppPage {
    List, Editor, Trash, Recording, Settings;

    val depth: Int
        get() = if (this == List) 0 else 1
}
