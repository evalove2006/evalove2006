package com.workbuddy.taskalarm.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppUtil {
    /** 全局 IO 协程作用域（用于云端同步、闹钟排程等网络/耗时操作） */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 今天的日期字符串 YYYY-MM-DD（设备时区） */
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 把毫秒差格式化为「X 分钟 / X 小时」 */
    fun fmtRemain(ms: Long): String {
        val min = (ms / 60000).toInt()
        if (min < 60) return "$min 分钟"
        val h = min / 60
        val m = min % 60
        return if (m == 0) "$h 小时" else "$h 小时 $m 分"
    }
}
