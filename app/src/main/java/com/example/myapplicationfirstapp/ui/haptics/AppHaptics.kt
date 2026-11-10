package com.example.myapplicationfirstapp.ui.haptics

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * 全 App 唯一的触觉入口。
 *
 * 设计原则（对齐 Android Haptics design principles）：
 *  1. 只在「语义事件」上反馈，不给滚动、计时、页面跳转加震。
 *  2. 一次操作最多一次反馈，且必须发生在操作真正成功之后。
 *  3. 只用系统预置的语义常量，不自定义波形，不申请 VIBRATE 权限，
 *     不传 FLAG_IGNORE_GLOBAL_SETTING / FLAG_IGNORE_VIEW_SETTING ——
 *     用户在系统里关掉触觉、或这台机器没有马达，行为必须和没加触觉时完全一致。
 *
 * 版本降级（实测 SDK 表，见 platform api-versions.xml）：
 *
 * | 语义     | API >= 34 | API 30..33 | API 24..29 |
 * |----------|-----------|------------|------------|
 * | longPress| LONG_PRESS (1+) | 同左 | 同左 |
 * | toggleOn | TOGGLE_ON  | CLOCK_TICK | CLOCK_TICK |
 * | toggleOff| TOGGLE_OFF | CLOCK_TICK | CLOCK_TICK |
 * | success  | CONFIRM    | CONFIRM    | CLOCK_TICK |
 * | failure  | REJECT     | REJECT     | CONTEXT_CLICK |
 *
 * 说明：TOGGLE_ON / TOGGLE_OFF 是 API 34 才有的常量，CONFIRM / REJECT 是 API 30 起。
 * 低于这些版本时不能把常量硬发下去（部分 ROM 会当成未知常量忽略，等于没反馈），
 * 所以显式换成语义最接近、且该版本一定支持的基础反馈。
 */
class AppHaptics(
    private val haptic: HapticFeedback,
    private val view: View?,
    private val deviceHasVibrator: Boolean
) {

    private val sdk = Build.VERSION.SDK_INT

    /** 长按识别成功。列表里的长按由 Compose 的 combinedClickable 自带一次，不要再调。 */
    fun longPress() {
        compose(HapticFeedbackType.LongPress)
    }

    /** 状态开启：开关打开、模式切到「开」、选项被选中生效。 */
    fun toggleOn() {
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            compose(HapticFeedbackType.ToggleOn)
        } else {
            constant(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** 状态关闭：开关关闭、模式切回「关」。 */
    fun toggleOff() {
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            compose(HapticFeedbackType.ToggleOff)
        } else {
            constant(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** 便捷写法：按新状态自己选开/关。 */
    fun toggle(enabled: Boolean) {
        if (enabled) toggleOn() else toggleOff()
    }

    /** 操作成功：文件真的落盘、删除真的完成之后才发。 */
    fun success() {
        if (sdk >= Build.VERSION_CODES.R) {
            compose(HapticFeedbackType.Confirm)
        } else {
            constant(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** 明确的失败：启动失败、保存失败、操作被拒。失败前的无效点击不发。 */
    fun failure() {
        if (sdk >= Build.VERSION_CODES.R) {
            compose(HapticFeedbackType.Reject)
        } else {
            constant(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    // ---- 两条下发路径：优先 Compose 现成的语义类型，必要时退回 View 常量 ----

    private fun compose(type: HapticFeedbackType) {
        if (!available()) return
        runCatching { haptic.performHapticFeedback(type) }
    }

    private fun constant(feedbackConstant: Int) {
        if (!available()) return
        val target = view ?: return
        // 不带任何 flag：系统触觉开关、视图级开关、静音/勿扰都照常生效
        runCatching { target.performHapticFeedback(feedbackConstant) }
    }

    /** 没有马达就直接不发；系统关掉触觉时由平台自己吞掉，这里不需要也不应该干预。 */
    private fun available(): Boolean = deviceHasVibrator && view?.isHapticFeedbackEnabled != false
}

/**
 * 从 Compose 拿一个 AppHaptics。一个组合树里多处调用得到的是同一个实例，
 * 内部不持有可变状态，重组、页面恢复不会补发任何东西。
 */
@Composable
fun rememberAppHaptics(): AppHaptics {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val context = LocalContext.current
    return remember(haptic, view, context) {
        AppHaptics(
            haptic = haptic,
            view = view,
            deviceHasVibrator = hasVibrator(context)
        )
    }
}

/**
 * 只做「有没有马达」的判断，不碰 VIBRATE 权限。
 * 查询本身出任何问题都按「有」处理：宁可让平台去吞，也不要误判成没有而静默。
 */
private fun hasVibrator(context: Context): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.hasVibrator()
            ?: true
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() ?: true
    }
}.getOrDefault(true)

/**
 * 录制流程里的一次性触觉事件。
 *
 * 由 RecorderState 通过 SharedFlow 发出：没有 replay，UI 侧收集一次消费一次，
 * 重组 / 旋转 / 返回页面都不会把旧事件再放一遍。
 */
enum class HapticCue {
    /** 采音真的停了（MediaRecorder.pause() 已返回） */
    Paused,

    /** 文件真的写完了 */
    Saved,

    /** 确认删除且文件真的删掉了 */
    Discarded,

    /** 明确失败：没启动、没录上、太短、删除失败 */
    Failure
}
