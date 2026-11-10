package com.example.myapplicationfirstapp.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
private val dateFormat = SimpleDateFormat("M月d日", Locale.CHINA)
private val yearFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
private val monthDayTimeFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

// 英文界面用的日期格式（月份用缩写，和系统英文日期习惯一致）
private val dateFormatEn = SimpleDateFormat("MMM d", Locale.ENGLISH)
private val yearFormatEn = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)
private val monthDayTimeFormatEn = SimpleDateFormat("MMM d, HH:mm", Locale.ENGLISH)

/**
 * 把时间戳变成人话：今天 14:32 / 昨天 09:10 / 10月28日 / 2025年3月1日
 * [english] 为 true 时输出 Today / Yesterday / Oct 28 / Mar 1, 2025。
 */
fun formatNoteTime(millis: Long, english: Boolean = false): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = millis }
    val clock = timeFormat.format(Date(millis))

    return when {
        target.get(Calendar.YEAR) != now.get(Calendar.YEAR) ->
            if (english) yearFormatEn.format(Date(millis)) else yearFormat.format(Date(millis))

        target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) ->
            if (english) "Today $clock" else "今天 $clock"

        target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) - 1 ->
            if (english) "Yesterday $clock" else "昨天 $clock"

        else -> if (english) dateFormatEn.format(Date(millis)) else dateFormat.format(Date(millis))
    }
}

/**
 * 提醒时间的说法，比 [formatNoteTime] 多一个「明天」：
 * 今天 20:00 / 明天 09:00 / 11月3日 08:30。
 * 已经过点的一律说「已到时间」，避免卡片上还挂着「今天 14:30」像是没响。
 */
fun formatReminderTime(millis: Long, english: Boolean = false): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = millis }

    if (millis <= System.currentTimeMillis()) {
        return if (english) "Overdue" else "已到时间"
    }

    val sameYear = target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val dayDiff = target.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR)
    val clock = timeFormat.format(Date(millis))

    return when {
        !sameYear -> (if (english) yearFormatEn else yearFormat).format(Date(millis)) + " " + clock
        dayDiff == 0 -> if (english) "Today $clock" else "今天 $clock"
        dayDiff == 1 -> if (english) "Tomorrow $clock" else "明天 $clock"
        else -> (if (english) monthDayTimeFormatEn else monthDayTimeFormat).format(Date(millis))
    }
}
