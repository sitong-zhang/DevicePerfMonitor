package com.devperf.monitor.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * 本地账号：注册 / 登录 / 登出 / 会话持久化。
 * 全部数据只存在本机 SharedPreferences，不联网、不上传任何服务器。
 */
data class Account(val username: String, val createdAt: Long)

class AccountManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("gx_account", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow<Account?>(loadSession())
    val session: StateFlow<Account?> = _session.asStateFlow()

    /** 注册并登录；返回 null 表示成功，否则为错误提示。 */
    fun register(username: String, password: String): String? {
        val u = username.trim()
        if (u.length < 3) return "用户名至少 3 个字符"
        if (password.length < 6) return "密码至少 6 位"
        val list = accounts()
        for (i in 0 until list.length()) {
            if (list.optJSONObject(i)?.optString("username") == u) return "该用户名已被注册"
        }

        val salt = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)
        val obj = JSONObject().apply {
            put("username", u)
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("hash", hash(password, salt))
            put("createdAt", System.currentTimeMillis())
        }
        val arr = JSONArray()
        for (i in 0 until list.length()) arr.put(list.optJSONObject(i))
        arr.put(obj)
        prefs.edit().putString("accounts", arr.toString()).apply()

        val acc = Account(u, obj.optLong("createdAt"))
        setSession(acc)
        return null
    }

    /** 登录；成功返回 true。 */
    fun login(username: String, password: String): Boolean {
        val u = username.trim()
        val list = accounts()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optString("username") == u) {
                val salt = Base64.decode(o.optString("salt"), Base64.NO_WRAP)
                return if (hash(password, salt) == o.optString("hash")) {
                    setSession(Account(u, o.optLong("createdAt")))
                    true
                } else {
                    false
                }
            }
        }
        return false
    }

    fun logout() {
        prefs.edit().remove("session").apply()
        _session.value = null
    }

    private fun setSession(a: Account) {
        _session.value = a
        prefs.edit()
            .putString(
                "session",
                JSONObject().put("username", a.username).put("createdAt", a.createdAt).toString(),
            ).apply()
    }

    private fun loadSession(): Account? {
        val s = prefs.getString("session", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            Account(o.optString("username"), o.optLong("createdAt"))
        }.getOrNull()
    }

    private fun accounts(): JSONArray {
        val raw = prefs.getString("accounts", null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    /** 口令哈希：SHA-256(盐 + 密码)，Base64 存储。 */
    private fun hash(password: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
    }
}
