package com.workbuddy.taskalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.taskalarm.cloud.CloudSync
import com.workbuddy.taskalarm.util.AppUtil

/** 开机 / 应用更新后：重排每日刷新，并恢复今天的闹钟 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        CloudSync.init(ctx)
        DailyRefreshScheduler.schedule(ctx)
        AppUtil.appScope.launch { AlarmPlanner.plan(ctx) }
    }
}
