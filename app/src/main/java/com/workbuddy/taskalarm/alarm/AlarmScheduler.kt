package com.workbuddy.taskalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.workbuddy.taskalarm.cloud.CloudSync
import com.workbuddy.taskalarm.model.Task

/**
 * 用系统级精确闹钟（AlarmManager.setExactAndAllowWhileIdle）在
 * 「截止前 30 分钟」与「截止时刻」各注册一次 —— 这是原生相比网页能锁屏必响的关键。
 */
object AlarmScheduler {
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_TASK_NAME = "task_name"
    const val EXTRA_TYPE = "type"     // "warn" = 预警, "overdue" = 逾期
    private const val LEAD_MS = 30L * 60 * 1000

    fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
    }

    fun scheduleForTask(ctx: Context, task: Task) {
        val dl = task.deadlineMillis() ?: return
        val now = System.currentTimeMillis()
        val warnAt = dl - LEAD_MS
        if (warnAt > now) schedule(ctx, task, "warn", warnAt)
        if (dl > now) schedule(ctx, task, "overdue", dl)
    }

    /** 重排全部（先取消旧的再排新的，避免重复） */
    fun rescheduleAll(ctx: Context, tasks: List<Task>) {
        tasks.forEach { cancel(ctx, it.id) }
        tasks.forEach { scheduleForTask(ctx, it) }
    }

    private fun pi(ctx: Context, task: Task, type: String): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.workbuddy.taskalarm.ALARM"
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_TASK_NAME, task.name)
            putExtra(EXTRA_TYPE, type)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 33) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, (task.id + type).hashCode(), intent, flags)
    }

    private fun schedule(ctx: Context, task: Task, type: String, at: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, task, type))
        } catch (e: SecurityException) {
            // 未授予 SCHEDULE_EXACT_ALARM：降级到 setWindow（仍较准，但非精确）
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60 * 1000, pi(ctx, task, type))
        }
    }

    fun cancel(ctx: Context, taskId: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (type in listOf("warn", "overdue")) {
            val dummy = Task(taskId, null, "", null, null, "", "s0", null, null, null, false, false, emptyList())
            am.cancel(pi(ctx, dummy, type))
        }
    }
}

/** 每日 0 点刷新任务的精确闹钟（排到下一个 00:05） */
object DailyRefreshScheduler {
    private const val REQ = 7777

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 5)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val intent = Intent(ctx, DailyRefreshReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 33) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, REQ, intent, flags)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } catch (e: SecurityException) {
            am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 10 * 60 * 1000, pi)
        }
    }
}

/** 统一排程入口：登录后 / 开机后 / 每日刷新时调用 */
object AlarmPlanner {
    suspend fun plan(ctx: Context) {
        if (!CloudSync.isLoggedIn()) return
        try {
            val today = com.workbuddy.taskalarm.util.AppUtil.today()
            val templates = CloudSync.loadTemplates()
            CloudSync.ensureToday(templates, today)
            val tasks = CloudSync.loadTasks(today)
            AlarmScheduler.rescheduleAll(ctx, tasks)
        } catch (e: Exception) {
            android.util.Log.e("AlarmPlanner", "plan failed: ${e.message}")
        }
    }
}
