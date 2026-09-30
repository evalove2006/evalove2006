package com.workbuddy.taskalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.taskalarm.cloud.CloudSync
import com.workbuddy.taskalarm.util.AppUtil

/**
 * 闹钟触发入口。被 AlarmManager 在「截止前 30 分钟」或「截止时刻」唤醒。
 * 触发前再核验一次任务是否已完成（用户可能刚打卡），已完成则放弃响铃。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_ID) ?: return
        val name = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_NAME) ?: "任务"
        val type = intent.getStringExtra(AlarmScheduler.EXTRA_TYPE) ?: "warn"

        com.workbuddy.taskalarm.util.AppUtil.appScope.launch {
            val done = runCatching {
                CloudSync.loadTasks(AppUtil.today()).any { it.id == taskId && it.isDone }
            }.getOrDefault(false)
            if (done) return@launch

            // 标记已触发，避免重复响（与网页端 alerted 防刷屏一致）
            runCatching { CloudSync.markFired(taskId, type == "warn") }

            val svc = Intent(ctx, AlarmService::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_TASK_ID, taskId)
                putExtra(AlarmScheduler.EXTRA_TASK_NAME, name)
                putExtra(AlarmScheduler.EXTRA_TYPE, type)
            }
            ctx.startForegroundService(svc)
        }
    }
}
