package com.example.myapplicationfirstapp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「点正文下方空白」的判定分支。
 *
 * 只覆盖 **该不该接管这次点击** 这个判定，**不覆盖**焦点、光标位置与输入法的实际效果
 * ——那部分只能靠模拟器上的前后对照（截图 + `dumpsys input_method` + UI dump），
 * 本文件不能当作 UI 行为测试。
 * 摆在这里是为了挡住三种回退：空白点不到、点正文也被接管、清单模式被顺手改掉。
 */
class NoteEditorBlankTapPolicyTest {

    private val bodyBottom = 1149f

    @Test
    fun tapBelowLastBlockFocusesBody() {
        assertTrue(
            shouldFocusBodyOnBlankTap(
                checklist = false,
                lastTextIndex = 0,
                tapY = bodyBottom + 200f,
                bodyBottomY = bodyBottom
            )
        )
    }

    @Test
    fun tapInsideTextKeepsOriginalCaretPlacement() {
        // 点正文文字本身：不高过底边，交给 BasicTextField 自己定位光标
        assertFalse(
            shouldFocusBodyOnBlankTap(
                checklist = false,
                lastTextIndex = 0,
                tapY = bodyBottom - 40f,
                bodyBottomY = bodyBottom
            )
        )
    }

    @Test
    fun checklistIsNotHandled() {
        assertFalse(
            shouldFocusBodyOnBlankTap(
                checklist = true,
                lastTextIndex = 0,
                tapY = bodyBottom + 200f,
                bodyBottomY = bodyBottom
            )
        )
    }

    @Test
    fun noTextBlockIsNotHandled() {
        // 正文只剩图片/录音块时没有可聚焦的文字块
        assertFalse(
            shouldFocusBodyOnBlankTap(
                checklist = false,
                lastTextIndex = -1,
                tapY = bodyBottom + 200f,
                bodyBottomY = bodyBottom
            )
        )
    }

    @Test
    fun tapExactlyOnBottomEdgeIsNotHandled() {
        // 边界取严：正好压在底边上仍算「点正文」，不接管
        assertFalse(
            shouldFocusBodyOnBlankTap(
                checklist = false,
                lastTextIndex = 0,
                tapY = bodyBottom,
                bodyBottomY = bodyBottom
            )
        )
    }
}
