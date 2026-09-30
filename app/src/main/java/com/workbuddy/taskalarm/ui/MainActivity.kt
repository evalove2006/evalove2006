package com.workbuddy.taskalarm.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.workbuddy.taskalarm.alarm.AlarmScheduler
import com.workbuddy.taskalarm.model.Task
import com.workbuddy.taskalarm.util.AppUtil

data class UiState(
    val loggedIn: Boolean = false,
    val email: String = "",
    val otpSent: Boolean = false,
    val tasks: List<Task> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

class TaskListViewModel : androidx.lifecycle.ViewModel() {
    private val _ui = kotlinx.coroutines.flow.MutableStateFlow(UiState())
    val state: kotlinx.coroutines.flow.StateFlow<UiState> = _ui

    fun init(ctx: Context) {
        com.workbuddy.taskalarm.cloud.CloudSync.init(ctx)
        if (com.workbuddy.taskalarm.cloud.CloudSync.isLoggedIn()) {
            _ui.value = _ui.value.copy(loggedIn = true)
            kotlinx.coroutines.GlobalScope.launch { refresh(ctx) }
        }
    }

    fun setEmail(e: String) { _ui.value = _ui.value.copy(email = e) }

    fun sendOtp() {
        val email = _ui.value.email
        if (!email.contains("@")) { _ui.value = _ui.value.copy(error = "请输入有效邮箱"); return }
        _ui.value = _ui.value.copy(loading = true, error = null)
        kotlinx.coroutines.GlobalScope.launch {
            runCatching { com.workbuddy.taskalarm.cloud.CloudSync.sendOtp(email) }
                .onSuccess { _ui.value = _ui.value.copy(loading = false, otpSent = true) }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message) }
        }
    }

    fun verify(ctx: Context, code: String) {
        val email = _ui.value.email
        _ui.value = _ui.value.copy(loading = true, error = null)
        kotlinx.coroutines.GlobalScope.launch {
            runCatching { com.workbuddy.taskalarm.cloud.CloudSync.verifyOtp(ctx, email, code) }
                .onSuccess {
                    _ui.value = _ui.value.copy(loading = false, loggedIn = true, otpSent = false)
                    planNow(ctx)
                    refresh(ctx)
                }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message) }
        }
    }

    fun refresh(ctx: Context) {
        if (!com.workbuddy.taskalarm.cloud.CloudSync.isLoggedIn()) return
        _ui.value = _ui.value.copy(loading = true, error = null)
        kotlinx.coroutines.GlobalScope.launch {
            runCatching {
                val today = AppUtil.today()
                val templates = com.workbuddy.taskalarm.cloud.CloudSync.loadTemplates()
                com.workbuddy.taskalarm.cloud.CloudSync.ensureToday(templates, today)
                com.workbuddy.taskalarm.cloud.CloudSync.loadTasks(today)
            }.onSuccess { tasks ->
                _ui.value = _ui.value.copy(loading = false, tasks = tasks)
                com.workbuddy.taskalarm.alarm.AlarmPlanner.plan(ctx)
            }.onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message) }
        }
    }

    fun complete(ctx: Context, id: String) {
        kotlinx.coroutines.GlobalScope.launch {
            runCatching { com.workbuddy.taskalarm.cloud.CloudSync.completeTask(id) }
                .onSuccess { refresh(ctx) }
                .onFailure { _ui.value = _ui.value.copy(error = it.message) }
        }
    }

    fun logout(ctx: Context) {
        com.workbuddy.taskalarm.cloud.CloudSync.logout(ctx)
        _ui.value = UiState()
    }

    private suspend fun planNow(ctx: Context) {
        com.workbuddy.taskalarm.alarm.DailyRefreshScheduler.schedule(ctx)
        com.workbuddy.taskalarm.alarm.AlarmPlanner.plan(ctx)
    }
}

class MainActivity : ComponentActivity() {
    private val vm: TaskListViewModel by viewModels()
    private val exactAlarmLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.init(this)
        requestPermissionsIfNeeded()
        setContent { AppUi(vm) }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)) {
            exactAlarmLauncher.launch(Manifest.permission.SCHEDULE_EXACT_ALARM)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUi(vm: TaskListViewModel) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            if (!state.loggedIn) LoginScreen(state, vm)
            else TaskScreen(state, vm, ctx)
        }
    }
}

@Composable
fun LoginScreen(state: UiState, vm: TaskListViewModel) {
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("任务闹钟", fontSize = 24.sp)
        Text("邮箱验证码登录", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.email, onValueChange = vm::setEmail,
            label = { Text("邮箱") }, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        if (state.otpSent) {
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("验证码") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.verify(LocalContext.current, code) }, modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading) { Text(if (state.loading) "验证中…" else "验证并登录") }
        } else {
            Button(onClick = { vm.sendOtp() }, modifier = Modifier.fillMaxWidth(), enabled = !state.loading) {
                Text(if (state.loading) "发送中…" else "发送验证码")
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(state: UiState, vm: TaskListViewModel, ctx: Context) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("今日任务") }, actions = {
            Text(state.email, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.refresh(ctx) }) { Text("刷新") }
            OutlinedButton(onClick = { vm.logout(ctx) }) { Text("退出") }
        })
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
        LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
            items(state.tasks) { task -> TaskCard(task, vm, ctx) }
        }
    }
}

@Composable
fun TaskCard(task: Task, vm: TaskListViewModel, ctx: Context) {
    val dl = task.deadlineMillis()
    val remain = if (dl != null) dl - System.currentTimeMillis() else null
    val remainText = when {
        task.isDone -> "已完成"
        remain == null -> "无截止时间"
        remain <= 0 -> "已逾期 ${AppUtil.fmtRemain(-remain)}"
        else -> "剩 ${AppUtil.fmtRemain(remain)}"
    }
        Card(Modifier.fillMaxWidth().padding(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.name, fontSize = 16.sp)
                Text(
                    "截止 ${task.deadline ?: "—"} · $remainText",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!task.isDone) {
                Button(onClick = { vm.complete(ctx, task.id) }) { Text("完成") }
            }
        }
    }
}
