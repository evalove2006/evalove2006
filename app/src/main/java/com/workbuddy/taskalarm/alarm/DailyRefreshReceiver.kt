package com.workbuddy.taskalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.taskalarm.cloud.CloudSync
import com.workbuddy.taskalarm.util.AppUtil

/** 每日 0:05 触发：先排明天的刷新，再为今天补齐任务并重排闹钟 */
class DailyRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        DailyRefreshScheduler.schedule(ctx)
        CloudSync.init(ctx)
        AppUtil.appScope.launch { AlarmPlanner.plan(ctx) }
    }
}
