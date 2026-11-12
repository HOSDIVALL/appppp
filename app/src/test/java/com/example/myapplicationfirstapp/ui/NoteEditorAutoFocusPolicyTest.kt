package com.example.myapplicationfirstapp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落焦点策略的判定分支。
 *
 * 只覆盖「该不该聚焦」这个判定，**不覆盖**焦点与输入法的实际效果
 * （那部分靠模拟器上的前后截图对照，见 evidence 索引）。
 * 摆在这里是为了挡住两种回退：新建不聚焦，以及已有笔记也被抢焦点。
 */
class NoteEditorAutoFocusPolicyTest {

    @Test
    fun newTextNoteFocusesItsBody() {
        assertTrue(shouldAutoFocusBody(isNewNote = true, checklist = false))
    }

    @Test
    fun newChecklistKeepsItsOwnFirstItemFocus() {
        // 清单已有「进来聚焦第一项」的逻辑，正文块在这里根本不存在
        assertFalse(shouldAutoFocusBody(isNewNote = true, checklist = true))
    }

    @Test
    fun existingNoteDoesNotStealFocus() {
        assertFalse(shouldAutoFocusBody(isNewNote = false, checklist = false))
    }
}
