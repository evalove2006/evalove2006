package com.workbuddy.taskalarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台服务：闹钟响起时循环播放系统默认闹钟铃声（TYPE_ALARM，锁屏也响），
 * 弹出常驻通知并启动全屏 AlarmActivity，直到用户「标记完成」或「仅停铃」才停止。
 */
class AlarmService : Service() {
    private var ringtone: android.media.Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(AlarmScheduler.EXTRA_TASK_ID) ?: ""
        val name = intent?.getStringExtra(AlarmScheduler.EXTRA_TASK_NAME) ?: "任务"
        val type = intent?.getStringExtra(AlarmScheduler.EXTRA_TYPE) ?: "warn"

        startForeground(NOTIF_ID, buildNotification(taskId, name, type))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "taskalarm:alarm"
        ).apply { acquire(10 * 60 * 1000L) }

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(this, uri)?.also {
            it.isLooping = true
            runCatching { it.play() }.onFailure { Log.e("AlarmService", "play failed", it) }
        }

        val act = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AlarmScheduler.EXTRA_TASK_ID, taskId)
            putExtra(AlarmScheduler.EXTRA_TASK_NAME, name)
            putExtra(AlarmScheduler.EXTRA_TYPE, type)
        }
        startActivity(act)
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "任务闹钟", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableLights(true)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(taskId: String, name: String, type: String): Notification {
        val open = Intent(this, AlarmActivity::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_TASK_ID, taskId)
            putExtra(AlarmScheduler.EXTRA_TASK_NAME, name)
            putExtra(AlarmScheduler.EXTRA_TYPE, type)
        }
        val pi = PendingIntent.getActivity(
            this, 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 33) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val title = if (type == "warn") "⏰ 任务预警" else "🚨 任务逾期"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("「$name」未完成，请尽快处理")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
    }

    override fun onDestroy() {
        ringtone?.stop()
        ringtone = null
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "taskalarm_channel"
    }
}
