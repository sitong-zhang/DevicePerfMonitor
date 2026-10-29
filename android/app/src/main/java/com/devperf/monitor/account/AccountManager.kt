package com.devperf.monitor.account

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 账号体系：注册 / 登录 / 登出 / 会话持久化（服务端账号系统）。
 *
 * 全部走自建后端（net/ApiClient，BuildConfig.SERVER_URL）：
 * - 注册：邮箱（真实验证码）+ 手机号 + 账号 + 密码 + 昵称，全字段必填；
 * - 登录：账号 + 密码，成功后 token 持久化在本机 SharedPreferences；
 * - 会话：token 永久保留直到主动登出（或服务端 token 失效时自动回登录页）。
 *
 * 旧版（≤1.0.31）的本地明文账号列表已废弃：服务端是唯一账号真相源。
 */
data class Account(
    val token: String,
    val account: String,
    val nickname: String,
    val email: String,
    val phone: String,
    val createdAt: Long,
    val status: String = "active",
)

class AccountManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("gx_account", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow<Account?>(loadSession())
    val session: StateFlow<Account?> = _session.asStateFlow()

    // ------------------------------------------------------------------
    // 邮箱验证码
    // ------------------------------------------------------------------

    /** 发送验证码结果：error 非空即失败；devCode 为官方服务器在免邮件模式下直接下发的验证码。 */
    data class SendCodeResult(val error: String?, val devCode: String?)

    /**
     * 请求发送邮箱验证码。
     *
     * 官方服务器在未配置邮件通道时会把验证码直接随响应下发（dev_code），
     * 此时 UI 自动填入，用户无需查收邮件即可完成注册 / 登录。
     */
    suspend fun sendCode(email: String): SendCodeResult = withContext(Dispatchers.IO) {
        val mail = email.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches()) {
            return@withContext SendCodeResult("邮箱格式不正确", null)
        }
        val res = ApiClientHolder.api.post(
            "/api/send_code",
            JSONObject().put("email", mail),
        )
        if (!res.ok) {
            SendCodeResult(res.msg ?: "验证码发送失败，请稍后再试", null)
        } else {
            SendCodeResult(null, res.data?.optString("dev_code")?.takeIf { it.isNotBlank() })
        }
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /**
     * 注册并登录。所有字段必填；返回 null 表示成功，
     * 否则为可直接展示给用户的错误提示。
     */
    suspend fun register(
        email: String,
        code: String,
        phone: String,
        account: String,
        password: String,
        nickname: String,
    ): String? = withContext(Dispatchers.IO) {
        val e = email.trim()
        val c = code.trim()
        val p = phone.trim()
        val a = account.trim()
        val n = nickname.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) return@withContext "邮箱格式不正确"
        if (c.isEmpty()) return@withContext "请填写邮箱验证码"
        if (p.length != 11 || !p.all { it.isDigit() }) return@withContext "手机号须为 11 位数字"
        if (a.length < 3) return@withContext "账号至少 3 个字符"
        if (password.length < 6) return@withContext "密码至少 6 位"
        if (n.isEmpty()) return@withContext "请填写昵称"

        val res = ApiClientHolder.api.post(
            "/api/register",
            JSONObject()
                .put("email", e)
                .put("code", c)
                .put("phone", p)
                .put("account", a)
                .put("password", password)
                .put("nickname", n),
        )
        val data = res.data
        if (!res.ok || data == null || !data.has("token")) {
            return@withContext (res.msg ?: "注册失败，请稍后再试")
        }
        setSession(
            Account(
                token = data.optString("token"),
                account = data.optJSONObject("user")?.optString("account") ?: a,
                nickname = data.optJSONObject("user")?.optString("nickname") ?: n,
                email = data.optJSONObject("user")?.optString("email") ?: e,
                phone = data.optJSONObject("user")?.optString("phone") ?: p,
                createdAt = data.optJSONObject("user")?.optLong("created_at") ?: System.currentTimeMillis(),
            ),
        )
        null
    }

    // ------------------------------------------------------------------
    // 登录 / 登出
    // ------------------------------------------------------------------

    /** 登录；返回 null 表示成功，否则为错误提示（账号或密码错误等）。 */
    suspend fun login(account: String, password: String): String? = withContext(Dispatchers.IO) {
        val a = account.trim()
        if (a.isEmpty() || password.isEmpty()) return@withContext "请填写账号与密码"
        val res = ApiClientHolder.api.post(
            "/api/login",
            JSONObject().put("account", a).put("password", password),
        )
        val data = res.data
        if (!res.ok || data == null || !data.has("token")) {
            return@withContext (res.msg ?: "账号或密码错误")
        }
        setSession(
            Account(
                token = data.optString("token"),
                account = data.optJSONObject("user")?.optString("account") ?: a,
                nickname = data.optJSONObject("user")?.optString("nickname") ?: a,
                email = data.optJSONObject("user")?.optString("email") ?: "",
                phone = data.optJSONObject("user")?.optString("phone") ?: "",
                createdAt = data.optJSONObject("user")?.optLong("created_at") ?: 0L,
            ),
        )
        null
    }

    fun logout() {
        prefs.edit().remove("session").apply()
        _session.value = null
    }

    // ------------------------------------------------------------------
    // 会话持久化
    // ------------------------------------------------------------------

    private fun setSession(a: Account) {
        _session.value = a
        prefs.edit()
            .putString(
                "session",
                JSONObject()
                    .put("token", a.token)
                    .put("account", a.account)
                    .put("nickname", a.nickname)
                    .put("email", a.email)
                    .put("phone", a.phone)
                    .put("createdAt", a.createdAt)
                    .put("status", a.status)
                    .toString(),
            ).apply()
    }

    private fun loadSession(): Account? {
        val s = prefs.getString("session", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            Account(
                token = o.optString("token"),
                account = o.optString("account"),
                nickname = o.optString("nickname"),
                email = o.optString("email"),
                phone = o.optString("phone"),
                createdAt = o.optLong("createdAt"),
                status = o.optString("status", "active"),
            )
        }.getOrNull()?.takeIf { it.token.isNotEmpty() }
    }
}

/** ApiClient 的懒加载桥（避免 AccountManager 单元测试触碰 Android BuildConfig）。 */
internal object ApiClientHolder {
    val api: com.devperf.monitor.net.ApiClient get() = com.devperf.monitor.net.ApiClient
}
