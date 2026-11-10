package com.example.myapplicationfirstapp.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.myapplicationfirstapp.MainActivity
import com.example.myapplicationfirstapp.reminder.ReminderReceiver

/** 闹钟 Intent 里带的字段，调度器和接收器共用 */
object ReminderContract {
    const val EXTRA_NOTE_ID = "note_id"
    const val EXTRA_TITLE = "note_title"
    const val EXTRA_TEXT = "note_text"

    /** 点通知（或系统闹钟图标）进 App 时带的目标笔记 */
    const val EXTRA_OPEN_NOTE = "open_note_id"

    /** 每条笔记一个 action，取消时才能精确匹配到自己的闹钟 */
    const val ACTION_PREFIX = "com.example.myapplicationfirstapp.REMINDER/"
}

/**
 * 用系统的 AlarmManager 排提醒，而不是在 App 里开个定时器。
 *
 * 原因很实际：笔记 App 退到后台、被系统杀掉、甚至手机关机都不该漏提醒，
 * 只有系统闹钟能保证这一点。代价是要处理两件麻烦事：
 * 1. Android 12+ 的「精确闹钟」需要用户授权，没授权就退回不精确的
 *    setAndAllowWhileIdle（可能晚几分钟，但一定会响）。
 * 2. 关机重启后闹钟全部丢失，需要 [com.example.myapplicationfirstapp.reminder.ReminderBootReceiver] 重新排。
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    /** 给一条笔记排提醒；已经过点的时间直接跳过 */
    fun schedule(note: Note) {
        val at = note.remindAt ?: return
        if (at <= System.currentTimeMillis()) return

        val manager = alarmManager ?: return
        val pending = broadcast(note, at, PendingIntent.FLAG_UPDATE_CURRENT)

        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)

        if (exact) {
            val ok = runCatching {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                true
            }.getOrDefault(false)
            if (ok) return
        }

        // 没有精确闹钟权限：退到「闹钟」通道。
        // setAlarmClock 不受 Doze 和精确闹钟限制影响，代价是状态栏会出现闹钟图标。
        // 之前用 setAndAllowWhileIdle 兜底，实测会被系统塞进将近一小时的窗口里，
        // 对一个「几点提醒我」的功能来说等于没提醒。
        val clockOk = runCatching {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, showAppIntent(note.id)), pending)
            true
        }.getOrDefault(false)
        if (clockOk) return

        // 最后兜底：至少保证会响
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
    }

    /** 状态栏闹钟图标被点开时，直接跳到对应笔记 */
    private fun showAppIntent(noteId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(ReminderContract.EXTRA_OPEN_NOTE, noteId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            noteId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 取消一条笔记的提醒：换时间、清除提醒、删除笔记时都要调 */
    fun cancel(noteId: String) {
        val manager = alarmManager ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(noteId),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderContract.ACTION_PREFIX + noteId
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.cancel(pending)
        pending.cancel()
    }

    private fun broadcast(note: Note, at: Long, flags: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderContract.ACTION_PREFIX + note.id
            putExtra(ReminderContract.EXTRA_NOTE_ID, note.id)
            putExtra(ReminderContract.EXTRA_TITLE, note.displayTitle)
            putExtra(ReminderContract.EXTRA_TEXT, reminderBody(note))
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(note.id),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(noteId: String): Int = noteId.hashCode()

    /** 通知副标题：清单报进度，普通笔记给前几十个字 */
    private fun reminderBody(note: Note): String = when {
        note.checklist && note.todos.isNotEmpty() -> {
            val pending = note.todos.firstOrNull { !it.done }?.text
            if (pending != null) {
                "还有 ${note.todos.count { !it.done }} 项待办 · $pending"
            } else {
                "清单已全部完成"
            }
        }

        else -> note.preview.take(80)
    }
}
