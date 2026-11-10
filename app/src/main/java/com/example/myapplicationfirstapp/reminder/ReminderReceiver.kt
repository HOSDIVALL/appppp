package com.example.myapplicationfirstapp.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.myapplicationfirstapp.MainActivity
import com.example.myapplicationfirstapp.R
import com.example.myapplicationfirstapp.data.ReminderContract
import com.example.myapplicationfirstapp.data.ReminderScheduler
import com.example.myapplicationfirstapp.data.NotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟到点后由系统拉起这个接收器。
 *
 * 它不碰界面，只负责发一条通知。点通知会带着 note_id 打开 App，
 * 由 MainActivity 直接跳到那条笔记。
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getStringExtra(ReminderContract.EXTRA_NOTE_ID) ?: return
        val title = intent.getStringExtra(ReminderContract.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: "笔记提醒"
        val text = intent.getStringExtra(ReminderContract.EXTRA_TEXT).orEmpty()

        ReminderNotifications.show(context, noteId, title, text)
    }
}

/**
 * 开机 / 更新安装后把还没到点的提醒重新排一遍。
 * 系统闹钟不会跨重启保留，不重排就等于提醒全丢。
 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                val scheduler = ReminderScheduler(context)
                NotesRepository(context).load()
                    .filter { it.deletedAt == null && (it.remindAt ?: 0) > now }
                    .forEach(scheduler::schedule)
            } catch (_: Throwable) {
                // 开机流程里不能崩，排不上就等 App 下次启动再补
            } finally {
                pending.finish()
            }
        }
    }
}

/** 通知的渠道、样式和点击跳转都收在这里 */
object ReminderNotifications {

    const val CHANNEL_ID = "reminders"

    /** 点通知进 App 时带的额外数据，MainActivity 读它来定位笔记 */
    const val EXTRA_OPEN_NOTE = ReminderContract.EXTRA_OPEN_NOTE

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "笔记提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "到点提醒你笔记里记下的事"
                enableVibration(true)
            }
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun show(context: Context, noteId: String, title: String, text: String) {
        ensureChannel(context)
        if (!canPost(context)) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_NOTE, noteId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            noteId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(noteId.hashCode(), notification)
        }
    }
}
