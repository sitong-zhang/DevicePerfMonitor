package com.molecule.manager.network

import android.util.Log
import com.molecule.manager.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.withContext

/**
 * 管理端后端 API 客户端（账号 / 用户管控 / 公告，唯一联网出口）。
 *
 * 与用户端 net/ApiClient 同一风格：内置 HttpURLConnection + org.json，不引额外依赖。
 * 全部请求发往 [BuildConfig.SERVER_URL] 指向的自建后端（backend/，Flask + SQLite）。
 *
 * 鉴权：管理端登录拿到的 token 走 `Authorization: Bearer <token>` 头（后端
 * current_admin 仅认 Bearer，与 Web 管理页的 session cookie 是两条独立通道）。
 */
object AdminApiClient {

    private const val TAG = "AdminApiClient"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    data class Result(val ok: Boolean, val data: JSONObject?, val msg: String?)

    // ---- 数据模型 ----

    data class AdminUser(
        val id: Int,
        val account: String,
        val email: String,
        val phone: String,
        val nickname: String,
        val status: String,
        val createdAt: Long,
        val lastSyncAt: Long,
        val opCount: Int,
        val disabledFeatures: List<String>,
    )

    data class UserOperation(
        val id: Int,
        val type: String,
        val detail: String,
        val createdAt: Long,
    )

    data class UserAnnouncement(
        val id: Int,
        val title: String,
        val content: String,
        val createdAt: Long,
    )

    data class AdminUserDetail(
        val user: AdminUser,
        val disabledFeatures: List<String>,
        val restrictionNote: String?,
        val snapshot: Map<String, Any>?,
        val snapshotUpdatedAt: Long?,
        val operations: List<UserOperation>,
        val announcements: List<UserAnnouncement>,
    )

    // ---- 通用请求 ----

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        token: String?,
    ): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(BuildConfig.SERVER_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = method
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                if (!token.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            body?.let {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299) {
                Result(
                    ok = json?.optBoolean("ok", true) ?: true,
                    data = json,
                    msg = json?.optString("msg")?.ifEmpty { null },
                )
            } else {
                Result(false, json, json?.optString("msg")?.ifEmpty { null } ?: "网络错误（$code）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "请求失败 $method $path", e)
            Result(false, null, "无法连接服务器，请检查网络")
        } finally {
            conn?.disconnect()
        }
    }

    fun post(path: String, body: JSONObject?, token: String? = null) = request("POST", path, body, token)
    fun get(path: String, token: String? = null) = request("GET", path, null, token)

    // ---- 管理端接口 ----

    /** 管理端登录；成功返回 token，失败返回 null + 错误提示。 */
    suspend fun login(password: String): Pair<String?, String?> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val r = post("/api/admin/login", JSONObject().put("password", password))
            if (r.ok && r.data != null) {
                r.data.optString("token").takeIf { it.isNotBlank() } to null
            } else {
                null to (r.msg ?: "登录失败")
            }
        }

    /** 用户列表（按注册时间倒序）。 */
    suspend fun listUsers(token: String): Pair<List<AdminUser>, String?> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val r = get("/api/admin/users", token)
            if (r.ok && r.data != null) {
                val arr = r.data.optJSONArray("users") ?: JSONArray()
                val users = List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    AdminUser(
                        id = o.optInt("id"),
                        account = o.optString("account"),
                        email = o.optString("email"),
                        phone = o.optString("phone"),
                        nickname = o.optString("nickname"),
                        status = o.optString("status", "normal"),
                        createdAt = o.optLong("created_at"),
                        lastSyncAt = o.optLong("last_sync_at"),
                        opCount = o.optInt("op_count"),
                        disabledFeatures = optStringList(o.optJSONArray("disabled_features")),
                    )
                }
                users to null
            } else {
                emptyList<AdminUser>() to (r.msg ?: "加载用户失败")
            }
        }

    /** 用户详情（含操作记录 / 单独公告 / 最近快照）。 */
    suspend fun userDetail(token: String, uid: Int): Pair<AdminUserDetail?, String?> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val r = get("/api/admin/users/$uid", token)
            if (r.ok && r.data != null) {
                val u = r.data.optJSONObject("user") ?: JSONObject()
                val user = AdminUser(
                    id = u.optInt("id"),
                    account = u.optString("account"),
                    email = u.optString("email"),
                    phone = u.optString("phone"),
                    nickname = u.optString("nickname"),
                    status = u.optString("status", "normal"),
                    createdAt = u.optLong("created_at"),
                    lastSyncAt = u.optLong("last_sync_at"),
                    opCount = u.optInt("op_count"),
                    disabledFeatures = optStringList(r.data.optJSONArray("disabled_features")),
                )
                val ops = r.data.optJSONArray("operations") ?: JSONArray()
                val operations = List(ops.length()) { i ->
                    val o = ops.getJSONObject(i)
                    UserOperation(
                        id = o.optInt("id"),
                        type = o.optString("type"),
                        detail = o.optString("detail"),
                        createdAt = o.optLong("created_at"),
                    )
                }
                val anns = r.data.optJSONArray("announcements") ?: JSONArray()
                val announcements = List(anns.length()) { i ->
                    val a = anns.getJSONObject(i)
                    UserAnnouncement(
                        id = a.optInt("id"),
                        title = a.optString("title"),
                        content = a.optString("content"),
                        createdAt = a.optLong("created_at"),
                    )
                }
                val snap = r.data.optJSONObject("snapshot")
                val snapshot: Map<String, Any>? = snap?.let { j ->
                    buildMap { for (k in j.keys()) put(k, j.get(k)) }
                }
                AdminUserDetail(
                    user = user,
                    disabledFeatures = user.disabledFeatures,
                    restrictionNote = r.data.optString("restriction_note").ifEmpty { null },
                    snapshot = snapshot,
                    snapshotUpdatedAt = r.data.optLong("snapshot_updated_at").takeIf { it > 0 },
                    operations = operations,
                    announcements = announcements,
                ) to null
            } else {
                null to (r.msg ?: "加载用户详情失败")
            }
        }

    /** 禁用 / 启用某功能。 */
    suspend fun disableFeature(token: String, uid: Int, feature: String, disabled: Boolean): String? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val r = post(
                "/api/admin/users/$uid/disable_feature",
                JSONObject().put("feature", feature).put("disabled", disabled),
                token,
            )
            if (r.ok) null else (r.msg ?: "操作失败")
        }

    /** 设置账号状态（normal / restricted / disabled）。 */
    suspend fun setStatus(token: String, uid: Int, status: String, note: String): String? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val r = post(
                "/api/admin/users/$uid/set_status",
                JSONObject().put("status", status).put("note", note),
                token,
            )
            if (r.ok) null else (r.msg ?: "操作失败")
        }

    /** 给单个用户单独发公告。 */
    suspend fun announceUser(token: String, uid: Int, title: String, content: String): String? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val r = post(
                "/api/admin/users/$uid/announce",
                JSONObject().put("title", title).put("content", content),
                token,
            )
            if (r.ok) null else (r.msg ?: "发布公告失败")
        }

    /** 发布全局公告。 */
    suspend fun publishAnnouncement(token: String, title: String, content: String): String? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val r = post(
                "/api/admin/announce",
                JSONObject().put("title", title).put("content", content),
                token,
            )
            if (r.ok) null else (r.msg ?: "发布公告失败")
        }

    // ---- 工具 ----

    private fun optStringList(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else List(arr.length()) { i -> arr.optString(i) }

    fun formatTime(ms: Long): String =
        if (ms <= 0) "—" else try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(java.util.Date(ms))
        } catch (_: Exception) {
            ms.toString()
        }
}
