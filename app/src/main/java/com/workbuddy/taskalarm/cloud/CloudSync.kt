package com.workbuddy.taskalarm.cloud

import android.content.Context
import com.workbuddy.taskalarm.model.Task
import com.workbuddy.taskalarm.model.Template
import com.workbuddy.taskalarm.util.AppUtil
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 直连 WorkBuddy 云（Supabase 兼容 REST），复用与网页版同一份 tasks / templates 数据。
 * 使用 publishableKey 作为 anon key；登录走邮箱验证码（GoTrue OTP）。
 */
object CloudSync {
    const val ENDPOINT = "https://work-supervisor.app.workbuddy.host"
    const val KEY = "wbpk_LPtDbPNMjwthC9z6FaNCJz_LgZblA2stg4va5XVAWNC8Vkh1B1NfbRd"
    private const val PREF = "taskalarm_prefs"
    private const val K_TOKEN = "access_token"

    private var accessToken: String? = null

    fun init(ctx: Context) {
        accessToken = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_TOKEN, null)
    }

    fun isLoggedIn(): Boolean = !accessToken.isNullOrEmpty()

    fun logout(ctx: Context) {
        accessToken = null
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(K_TOKEN).apply()
    }

    private fun headers(auth: Boolean) = mutableMapOf(
        "apikey" to KEY,
        "Content-Type" to "application/json"
    ).also { if (auth && accessToken != null) it["Authorization"] = "Bearer $accessToken" }

    private fun request(method: String, path: String, body: String? = null, auth: Boolean = true): String {
        val conn = URL(ENDPOINT + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        headers(auth).forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw RuntimeException("HTTP $code $method $path: $text")
        return text
    }

    private fun JSONObject.putOpt(key: String, value: String?) =
        if (value == null) put(key, JSONObject.NULL) else put(key, value)

    // —— 登录：邮箱验证码 ——
    fun sendOtp(email: String) {
        request("POST", "/auth/v1/otp",
            JSONObject().put("email", email).put("should_create_user", true).toString(),
            auth = false)
    }

    fun verifyOtp(ctx: Context, email: String, token: String) {
        val resp = request("POST", "/auth/v1/verify",
            JSONObject().put("email", email).put("token", token).put("type", "email").toString(),
            auth = false)
        val obj = JSONObject(resp)
        val t = obj.optString("access_token")
            .ifEmpty { obj.optJSONObject("session")?.optString("access_token").orEmpty() }
        if (t.isEmpty()) throw RuntimeException("服务端未返回 access_token（请核对 verify 接口返回结构）")
        accessToken = t
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(K_TOKEN, t).apply()
    }

    // —— 读取 ——
    fun loadTasks(date: String): List<Task> {
        val arr = JSONArray(request("GET", "/rest/v1/tasks?task_date=eq.$date&order=deadline.asc"))
        val out = mutableListOf<Task>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Task(
                id = o.optString("id"),
                templateId = o.optString("template_id").takeIf { it.isNotEmpty() },
                name = o.optString("name"),
                planStart = o.optString("plan_start").takeIf { it.isNotEmpty() },
                deadline = o.optString("deadline").takeIf { it.isNotEmpty() },
                taskDate = o.optString("task_date"),
                status = o.optString("status", "s0"),
                completedAt = o.optString("completed_at").takeIf { it.isNotEmpty() },
                completedBy = o.optString("completed_by").takeIf { it.isNotEmpty() },
                completedNote = o.optString("completed_note").takeIf { it.isNotEmpty() },
                warn = o.optBoolean("warn"),
                alarm = o.optBoolean("alarm"),
                evidence = (0 until (o.optJSONArray("evidence")?.length() ?: 0))
                    .map { o.optJSONArray("evidence").getString(it) }
            ))
        }
        return out
    }

    fun loadTemplates(): List<Template> {
        val arr = JSONArray(request("GET", "/rest/v1/templates?select=*&order=created_at.asc"))
        val out = mutableListOf<Template>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Template(
                id = o.optString("id"),
                name = o.optString("name"),
                planStart = o.optString("plan_start").takeIf { it.isNotEmpty() },
                deadline = o.optString("deadline").takeIf { it.isNotEmpty() },
                active = o.optBoolean("active", true)
            ))
        }
        return out
    }

    /** 按模板为今天补齐尚未生成的任务 */
    suspend fun ensureToday(templates: List<Template>, date: String) {
        val existing = loadTasks(date).mapNotNull { it.templateId }.toSet()
        for (tpl in templates.filter { it.active }) {
            if (tpl.id in existing) continue
            val body = JSONObject().apply {
                put("template_id", tpl.id)
                put("name", tpl.name)
                putOpt("plan_start", tpl.planStart)
                putOpt("deadline", tpl.deadline)
                put("task_date", date)
                put("status", "s0")
                put("warn", false)
                put("alarm", false)
            }
            request("POST", "/rest/v1/tasks", body.toString())
        }
    }

    /** 打卡完成（置为 s2 按时完成） */
    fun completeTask(id: String) {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        request("PATCH", "/rest/v1/tasks?id=eq.$id",
            JSONObject().put("status", "s2").put("completed_at", now).toString())
    }

    /** 标记预警/逾期已触发（与网页端 alerted 防刷屏逻辑一致） */
    fun markFired(id: String, warn: Boolean) {
        request("PATCH", "/rest/v1/tasks?id=eq.$id",
            JSONObject().put(if (warn) "warn" else "alarm", true).toString())
    }
}
