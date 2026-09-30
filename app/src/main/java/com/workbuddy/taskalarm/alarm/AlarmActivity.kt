package com.workbuddy.taskalarm.alarm

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbuddy.taskalarm.cloud.CloudSync
import kotlinx.coroutines.launch

/**
 * 全屏响铃界面：锁屏也能覆盖显示。用户「标记完成」会写云端并停铃；「仅停铃」只停铃。
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_ID) ?: ""
        val name = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_NAME) ?: "任务"
        val type = intent.getStringExtra(AlarmScheduler.EXTRA_TYPE) ?: "warn"

        setContent {
            val scope = rememberCoroutineScope()
            val bg = if (type == "warn") Color(0xFFFDF0E2) else Color(0xFFFCEBEB)
            Surface(modifier = Modifier.fillMaxSize(), color = bg) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(if (type == "warn") "⏰ 任务预警" else "🚨 任务逾期", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(12.dp))
                    Text("「$name」", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (type == "warn") "距截止不足 30 分钟，请尽快完成" else "已超过截止时间，仍未完成",
                        fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { CloudSync.completeTask(taskId) }
                                    .onFailure { Toast.makeText(this@AlarmActivity, "打卡失败：${it.message}", Toast.LENGTH_SHORT).show() }
                                stopService(Intent(this@AlarmActivity, AlarmService::class.java))
                                finish()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F9D55))
                    ) { Text("标记完成并停铃", fontSize = 18.sp) }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        stopService(Intent(this@AlarmActivity, AlarmService::class.java))
                        finish()
                    }) { Text("仅停铃", fontSize = 16.sp) }
                }
            }
        }
    }
}
