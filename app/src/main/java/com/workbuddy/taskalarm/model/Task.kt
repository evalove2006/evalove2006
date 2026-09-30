package com.workbuddy.taskalarm.model

import java.util.Calendar

/**
 * 与云端 tasks 表一一对应的任务实体。
 * status: s0 未开始 / s1 进行中 / s2 按时完成 / s3 延期完成 / s4 逾期未完成
 */
data class Task(
    val id: String,
    val templateId: String?,
    val name: String,
    val planStart: String?,   // "HH:mm"
    val deadline: String?,    // "HH:mm"
    val taskDate: String,     // "YYYY-MM-DD"
    val status: String,
    val completedAt: String?,
    val completedBy: String?,
    val completedNote: String?,
    val warn: Boolean,
    val alarm: Boolean,
    val evidence: List<String>
) {
    /** 是否已打卡完成 */
    val isDone: Boolean get() = status == "s2" || status == "s3"

    /** 截止时间对应的 epoch 毫秒（按 taskDate 当天计算） */
    fun deadlineMillis(): Long? {
        if (deadline.isNullOrEmpty()) return null
        val p = deadline.split(":")
        if (p.size < 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val cal = Calendar.getInstance()
        val d = taskDate.split("-")
        if (d.size == 3) {
            cal.set(Calendar.YEAR, d[0].toIntOrNull() ?: cal.get(Calendar.YEAR))
            cal.set(Calendar.MONTH, (d[1].toIntOrNull() ?: 1) - 1)
            cal.set(Calendar.DAY_OF_MONTH, d[2].toIntOrNull() ?: 1)
        }
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

/** 任务模板（每日据此生成当天任务） */
data class Template(
    val id: String,
    val name: String,
    val planStart: String?,
    val deadline: String?,
    val active: Boolean
)
