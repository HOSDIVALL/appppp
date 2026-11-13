package com.example.myapplicationfirstapp.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/** 界面语言。持久化在 AppSettings 里。 */
enum class AppLanguage(val tag: String) {
    ZH("zh"),
    EN("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: ZH
    }
}

/**
 * 界面文案表。
 *
 * 为什么不用 Android 的 strings.xml + per-app language：
 * 这个项目的语言是 App 内自选（不跟随系统），用一份 Kotlin 表 + CompositionLocal
 * 切换更直接——改一个状态整棵树立刻重组，不需要重建 Activity。
 *
 * 覆盖范围：首页、底部导航、设置页、回收站。编辑页与录音详情的文案
 * 还在逐步迁入（下一轮补齐），未覆盖的部分继续显示中文。
 */
data class AppStrings(
    val english: Boolean,

    // ---------- 通用 ----------
    val appName: String,
    val versionTag: String,
    val back: String,
    val cancel: String,
    val confirm: String,
    val settings: String,
    val done: String,

    // ---------- 页签 ----------
    val tabNotes: String,
    val tabRecords: String,

    // ---------- 首页 ----------
    val notesSubtitleTemplate: String,
    val searchNotes: String,
    val searchClear: String,
    val filterAll: String,
    val filterTodo: String,
    val filterPinned: String,
    val filterReminder: String,
    val filterTags: String,
    val matchingNotesTemplate: String,
    val newNote: String,
    val newTodo: String,
    val voiceNote: String,
    val pinnedSection: String,
    val recentSection: String,
    val emptyTitle: String,
    val emptyHint: String,
    val emptySearchTitle: String,
    val emptySearchHint: String,
    val trash: String,
    val deleteToTrash: String,
    val pin: String,
    val unpin: String,
    val importBackup: String,
    val exportBackup: String,
    val movedToTrashTemplate: String,
    val undo: String,
    val progressDoneTemplate: String,
    val allDone: String,
    val sortUpdated: String,
    val sortCreated: String,
    val sortTitle: String,
    val noteCountTemplate: String,

    // ---------- 设置 ----------
    val settingsTitle: String,
    val sectionAppearance: String,
    val themeLabel: String,
    val languageLabel: String,
    val darkModeLabel: String,
    val modeSystem: String,
    val modeLight: String,
    val modeDark: String,
    val sectionData: String,
    val fontLabel: String,
    val sectionAbout: String,
    val versionLabel: String,
    val versionValue: String,
    val aboutHint: String,
    val themePageTitle: String,
    val themePageHint: String,
    val languagePageTitle: String,
    val languagePageHint: String,
    val languageZh: String,
    val languageEn: String,

    // ---------- 回收站 ----------
    val trashTitle: String,
    val restore: String,
    val deleteForever: String,
    val emptyTrashAction: String,
    val trashEmpty: String,
    val trashHint: String,
    val trashCountTemplate: String,
    val confirmEmptyTrash: String,
    val confirmEmptyTrashBodyTemplate: String,
    val deletedAt: String,

    // ---------- 录音 ----------
    /** 录制中主按钮：暂停。 */
    val recPause: String,
    /** 暂停中主按钮：继续。 */
    val recResume: String,
    /** 底部按钮组：保存。 */
    val recSave: String,
    /** 底部按钮组：删除。 */
    val recDelete: String,
    /** 删除二次确认标题。 */
    val recDiscardTitle: String,
    /** 删除二次确认正文。 */
    val recDiscardBody: String,

    // ---------- 录音操作按钮（按钮可见文案 / 无障碍说明） ----------
    /** 中央按钮可见文案：Pause（各语言下均保持英文）。 */
    val recActionPause: String,
    /** 中央按钮可见文案：Resume（各语言下均保持英文）。 */
    val recActionResume: String,
    /** 左侧按钮无障碍说明。 */
    val recActionDeleteA11y: String,
    /** 右侧按钮无障碍说明。 */
    val recActionSaveA11y: String
) {
    fun notesSubtitle(count: Int): String = String.format(notesSubtitleTemplate, count)
    fun matchingNotes(count: Int): String = String.format(matchingNotesTemplate, count)
    fun noteCount(count: Int): String = String.format(noteCountTemplate, count)
    fun trashCount(count: Int): String = String.format(trashCountTemplate, count)
    fun progressDone(done: Int, total: Int): String = String.format(progressDoneTemplate, done, total)
    fun movedToTrash(title: String): String = String.format(movedToTrashTemplate, title)
    fun confirmEmptyTrashBody(count: Int): String = String.format(confirmEmptyTrashBodyTemplate, count)
    /**
     * 列表行尾部「更多操作」按钮的无障碍说明。
     *
     * 成员函数而不是构造参数：不动两个实例的构造点，也不新增字段。
     */
    fun noteActions(title: String): String =
        if (english) "Note actions: $title" else "笔记操作：$title"
}

val ZhStrings = AppStrings(
    english = false,
    appName = "Notes GO",
    versionTag = "2.0 · Expressive",
    back = "返回",
    cancel = "取消",
    confirm = "确定",
    settings = "设置",
    done = "完成",

    tabNotes = "笔记",
    tabRecords = "录音",

    notesSubtitleTemplate = "共 %d 条 · 左滑删除，长按或点更多",
    searchNotes = "搜索笔记",
    searchClear = "清除",
    filterAll = "全部",
    filterTodo = "待办",
    filterPinned = "置顶",
    filterReminder = "提醒",
    filterTags = "标签",
    matchingNotesTemplate = "匹配 %d 条笔记",
    newNote = "新建笔记",
    newTodo = "新建待办",
    voiceNote = "语音速记",
    pinnedSection = "置顶",
    recentSection = "最近",
    emptyTitle = "还没有笔记",
    emptyHint = "点「新建笔记」写下第一条",
    emptySearchTitle = "没有匹配的笔记",
    emptySearchHint = "换个关键词或标签试试",
    trash = "回收站",
    deleteToTrash = "移到回收站",
    pin = "置顶",
    unpin = "取消置顶",
    importBackup = "从备份导入",
    exportBackup = "导出备份",
    movedToTrashTemplate = "已移到回收站「%s」",
    undo = "撤销",
    progressDoneTemplate = "%1\$d/%2\$d 已完成",
    allDone = "全部完成",
    sortUpdated = "最近修改",
    sortCreated = "创建时间",
    sortTitle = "标题",
    noteCountTemplate = "共 %d 条笔记",

    settingsTitle = "设置",
    sectionAppearance = "外观",
    themeLabel = "主题",
    languageLabel = "语言",
    darkModeLabel = "深色模式",
    modeSystem = "跟随系统",
    modeLight = "浅色",
    modeDark = "深色",
    sectionData = "数据",
    fontLabel = "字体",
    sectionAbout = "关于",
    versionLabel = "版本",
    versionValue = "Notes GO 2.0",
    aboutHint = "Material 3 Expressive · Jetpack Compose",
    themePageTitle = "主题",
    themePageHint = "选择一套配色，全局立即生效",
    languagePageTitle = "语言",
    languagePageHint = "界面语言",
    languageZh = "简体中文",
    languageEn = "English",

    trashTitle = "回收站",
    restore = "恢复",
    deleteForever = "彻底删除",
    emptyTrashAction = "清空",
    trashEmpty = "回收站是空的",
    trashHint = "删除的笔记会在这里保留 30 天",
    trashCountTemplate = "%d 条笔记",
    confirmEmptyTrash = "清空回收站？",
    confirmEmptyTrashBodyTemplate = "里面的 %d 条笔记会被彻底删除，无法恢复。",
    deletedAt = "删除于 %s",

    recPause = "暂停",
    recResume = "继续",
    recSave = "保存",
    recDelete = "删除",
    recDiscardTitle = "删除这段录音？",
    recDiscardBody = "这段录音会被丢弃，无法恢复。",
    // 设计稿要求中央按钮可见文案固定为英文 Pause / Resume
    recActionPause = "Pause",
    recActionResume = "Resume",
    recActionDeleteA11y = "删除录音",
    recActionSaveA11y = "保存录音"
)

val EnStrings = AppStrings(
    english = true,
    appName = "Notes GO",
    versionTag = "2.0 · Expressive",
    back = "Back",
    cancel = "Cancel",
    confirm = "OK",
    settings = "Settings",
    done = "Done",

    tabNotes = "Notes",
    tabRecords = "Recordings",

    notesSubtitleTemplate = "%d notes · swipe to delete, hold or tap more",
    searchNotes = "Search notes",
    searchClear = "Clear",
    filterAll = "All",
    filterTodo = "To-do",
    filterPinned = "Pinned",
    filterReminder = "Alerts",
    filterTags = "Tags",
    matchingNotesTemplate = "%d matching notes",
    newNote = "New note",
    newTodo = "New to-do",
    voiceNote = "Voice note",
    pinnedSection = "Pinned",
    recentSection = "Recent",
    emptyTitle = "No notes yet",
    emptyHint = "Tap “New note” to write your first one",
    emptySearchTitle = "No matching notes",
    emptySearchHint = "Try another keyword or tag",
    trash = "Trash",
    deleteToTrash = "Move to trash",
    pin = "Pin",
    unpin = "Unpin",
    importBackup = "Import backup",
    exportBackup = "Export backup",
    movedToTrashTemplate = "Moved “%s” to trash",
    undo = "Undo",
    progressDoneTemplate = "%1\$d of %2\$d done",
    allDone = "All done",
    sortUpdated = "Last edited",
    sortCreated = "Created",
    sortTitle = "Title",
    noteCountTemplate = "%d notes",

    settingsTitle = "Settings",
    sectionAppearance = "Appearance",
    themeLabel = "Theme",
    languageLabel = "Language",
    darkModeLabel = "Dark mode",
    modeSystem = "System",
    modeLight = "Light",
    modeDark = "Dark",
    sectionData = "Data",
    fontLabel = "Font",
    sectionAbout = "About",
    versionLabel = "Version",
    versionValue = "Notes GO 2.0",
    aboutHint = "Material 3 Expressive · Jetpack Compose",
    themePageTitle = "Theme",
    themePageHint = "Pick a palette — it applies across the app",
    languagePageTitle = "Language",
    languagePageHint = "Interface language",
    languageZh = "简体中文",
    languageEn = "English",

    trashTitle = "Trash",
    restore = "Restore",
    deleteForever = "Delete forever",
    emptyTrashAction = "Empty",
    trashEmpty = "Trash is empty",
    trashHint = "Deleted notes stay here for 30 days",
    trashCountTemplate = "%d notes",
    confirmEmptyTrash = "Empty trash?",
    confirmEmptyTrashBodyTemplate = "The %d notes inside will be permanently deleted. This cannot be undone.",
    deletedAt = "Deleted %s",

    recPause = "Pause",
    recResume = "Resume",
    recSave = "Save",
    recDelete = "Delete",
    recDiscardTitle = "Delete this recording?",
    recDiscardBody = "The recording will be discarded and can't be recovered.",
    recActionPause = "Pause",
    recActionResume = "Resume",
    recActionDeleteA11y = "Delete recording",
    recActionSaveA11y = "Save recording"
)

fun stringsFor(language: AppLanguage): AppStrings =
    if (language == AppLanguage.EN) EnStrings else ZhStrings

val LocalStrings = staticCompositionLocalOf { ZhStrings }
