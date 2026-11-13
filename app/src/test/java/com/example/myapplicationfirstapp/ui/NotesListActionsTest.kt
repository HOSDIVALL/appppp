package com.example.myapplicationfirstapp.ui

import com.example.myapplicationfirstapp.ui.i18n.AppLanguage
import com.example.myapplicationfirstapp.ui.i18n.EnStrings
import com.example.myapplicationfirstapp.ui.i18n.ZhStrings
import com.example.myapplicationfirstapp.ui.i18n.stringsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-LIST-ACTION-1：列表行「更多操作」入口的文案契约。
 *
 * 为什么是普通 JVM 单测而不是 Compose UI 测试：
 * 本仓库锁定的 espresso-core 3.5.1 在 API 37 上反射
 * `android.hardware.input.InputManager.getInstance()` 会抛 NoSuchMethodException，
 * Compose 测试规则在初始化阶段就崩（7 个用例全 Error，见
 * docs/ux-list-actions-1/build-and-tests.txt 的 androidTest 记录）。
 * 任务不允许为此升级依赖，所以 UI 行为改由模拟器实测覆盖；
 * 这里只锁「入口必须有可读的无障碍名字、中英都要有」这条不会靠肉眼发现的回归。
 */
class NotesListActionsTest {

    /** 无障碍名字必须带上笔记标题，否则读屏只报「按钮」，分不清是哪一行 */
    @Test
    fun zhDescriptionCarriesNoteTitle() {
        assertEquals("笔记操作：周会要点", ZhStrings.noteActions("周会要点"))
    }

    @Test
    fun enDescriptionCarriesNoteTitle() {
        assertEquals("Note actions: Weekly sync", EnStrings.noteActions("Weekly sync"))
    }

    /** 中英不能是同一串——漏翻时最容易出现的就是两边都返回中文 */
    @Test
    fun descriptionsDifferBetweenLanguages() {
        assertNotEquals(
            ZhStrings.noteActions("同名标题"),
            EnStrings.noteActions("同名标题")
        )
    }

    /** 按语言取文案的入口要跟着切，切换语言后读屏名字才会变 */
    @Test
    fun stringsForSwitchesDescription() {
        assertEquals("笔记操作：甲", stringsFor(AppLanguage.ZH).noteActions("甲"))
        assertEquals("Note actions: 甲", stringsFor(AppLanguage.EN).noteActions("甲"))
    }

    /** 标题可以是任何字符（含冒号、引号），只做拼接，不做转义或截断 */
    @Test
    fun titleIsConcatenatedVerbatim() {
        val tricky = "含:冒号 \"引号\" 的标题"
        assertEquals("笔记操作：$tricky", ZhStrings.noteActions(tricky))
        assertTrue(ZhStrings.noteActions(tricky).endsWith(tricky))
    }

    /**
     * 首页提示要和真实入口一致：提示语说「长按或点更多」，
     * 那么两边的 %d 占位符必须还在（列表条数是 String.format 填进去的）。
     */
    @Test
    fun subtitleHintsBothPathsAndKeepsPlaceholder() {
        listOf(ZhStrings, EnStrings).forEach { strings ->
            val rendered = String.format(strings.notesSubtitleTemplate, 3)
            assertTrue("条数占位符没被填进去：$rendered", rendered.contains("3"))
            assertTrue("不该残留 %d：$rendered", !rendered.contains("%d"))
        }
        assertTrue(
            "中文提示应同时提到长按与点更多",
            ZhStrings.notesSubtitleTemplate.contains("长按") &&
                ZhStrings.notesSubtitleTemplate.contains("更多")
        )
        assertTrue(
            "英文提示应同时提到长按与点更多",
            EnStrings.notesSubtitleTemplate.contains("hold") &&
                EnStrings.notesSubtitleTemplate.contains("more")
        )
    }
}
