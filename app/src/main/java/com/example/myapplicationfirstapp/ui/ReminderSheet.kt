package com.example.myapplicationfirstapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplicationfirstapp.data.formatReminderTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 提醒设置面板。
 *
 * 只给「随手记」用得上的几个时间点，外加自定义日期时间；
 * 不做一个只能滚轮盘的完整日历，少两次点击更重要。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSheet(
    current: Long?,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<Long?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Text(
            text = "提醒",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp)
        )
        Text(
            text = if (current != null) {
                "现在定在 ${formatReminderTime(current)}"
            } else {
                "到点会发一条通知，点通知直接跳回这条笔记"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
        )

        ReminderOption("一小时后") { onPick(inAnHour()) }
        ReminderOption("今晚 20:00") { onPick(todayAt(20)) }
        ReminderOption("明天 09:00") { onPick(tomorrowAt(9)) }
        ReminderOption("选择日期和时间…") { showDatePicker = true }

        if (current != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            ReminderOption(
                text = "清除提醒",
                color = MaterialTheme.colorScheme.error
            ) { onPick(null) }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // ---------- 选日期 ----------
    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = pickedDate ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickedDate = dateState.selectedDateMillis
                        showDatePicker = false
                        // 等日期对话框的窗口真正关掉再弹时间框：
                        // 同一帧里关一个 Dialog 再开另一个，新窗口会被系统连带关掉
                        scope.launch {
                            delay(280)
                            showTimePicker = true
                        }
                    },
                    enabled = dateState.selectedDateMillis != null
                ) {
                    Text("下一步")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    // ---------- 选时间 ----------
    if (showTimePicker) {
        val base = Calendar.getInstance().apply {
            pickedDate?.let { timeInMillis = it }
        }
        val now = Calendar.getInstance()
        val timeState = rememberTimePickerState(
            initialHour = if (pickedDate == null) {
                (now.get(Calendar.HOUR_OF_DAY) + 1) % 24
            } else {
                base.get(Calendar.HOUR_OF_DAY)
            },
            initialMinute = 0,
            is24Hour = true
        )

        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择时间") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = combine(pickedDate, timeState.hour, timeState.minute)
                        showTimePicker = false
                        // 选到了过去的时间就顺延到明天，别设一个立刻爆炸的提醒
                        onPick(if (millis > System.currentTimeMillis()) millis else millis + DAY_MILLIS)
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ReminderOption(
    text: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(text, color = color) },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = if (color == MaterialTheme.colorScheme.error) color else MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

private fun inAnHour(): Long = System.currentTimeMillis() + 60L * 60 * 1000

/** 今天的某个整点；已经过了就顺延到明天同一时刻 */
private fun todayAt(hour: Int): Long {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (calendar.timeInMillis <= System.currentTimeMillis()) {
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    return calendar.timeInMillis
}

private fun tomorrowAt(hour: Int): Long {
    val calendar = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return calendar.timeInMillis
}

/** 把「选中的日期」和「选中的时分」拼成一个时间戳 */
private fun combine(dateMillis: Long?, hour: Int, minute: Int): Long {
    val calendar = Calendar.getInstance()
    if (dateMillis != null) calendar.timeInMillis = dateMillis
    calendar.set(Calendar.HOUR_OF_DAY, hour)
    calendar.set(Calendar.MINUTE, minute)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}
