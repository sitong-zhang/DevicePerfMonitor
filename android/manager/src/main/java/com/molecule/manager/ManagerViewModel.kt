package com.molecule.manager

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.molecule.manager.network.AdminApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 管理端 ViewModel（2.0 重写）：账号管控 + 公告，数据全部来自自建后端
 * （backend/ Flask + SQLite），不再依赖 GitHub。
 *
 * 密码锁：管理密码 421083，输入一次后本地永久记住（SharedPrefs，除非卸载应用）。
 * 密码正确即向后端 /api/admin/login 换取 token，后续接口走 Bearer 鉴权。
 */
class ManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()

    companion object {
        /** 管理端密码（仅限管理人员知晓；分发仅限管理人员下载）。 */
        const val ADMIN_PASSWORD: String = "421083"
        private const val PREFS = "manager_lock"
        private const val KEY_UNLOCKED = "unlocked"
        private const val KEY_OFFICIAL_URL = "official_url"
    }

    // ---- 密码锁 ----
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _unlocked = MutableStateFlow(prefs.getBoolean(KEY_UNLOCKED, false))
    val unlocked = _unlocked.asStateFlow()

    init {
        // 官方后端地址自动发现：先用上次缓存的地址保证首屏即可用，
        // 再从仓库官方文件拉取最新地址并缓存，用户全程无需任何配置。
        prefs.getString(KEY_OFFICIAL_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { AdminApiClient.officialUrl = it }
        viewModelScope.launch {
            val url = AdminApiClient.resolveServerUrl()
            if (!url.isNullOrBlank()) {
                prefs.edit().putString(KEY_OFFICIAL_URL, url).apply()
            }
        }
    }

    private val _adminToken = MutableStateFlow<String?>(null)
    val adminToken = _adminToken.asStateFlow()

    /** 尝试解锁：密码正确返回 null，否则返回错误提示。 */
    fun tryUnlock(input: String): String? {
        if (input != ADMIN_PASSWORD) return "密码错误"
        _unlocked.value = true
        prefs.edit().putBoolean(KEY_UNLOCKED, true).apply()
        refreshToken()
        return null
    }

    /** 用硬编码管理密码向后端换 token（解锁后自动调用；token 失效也可重调）。 */
    fun refreshToken() {
        viewModelScope.launch {
            val (token, err) = AdminApiClient.login(ADMIN_PASSWORD)
            _adminToken.value = token
            if (err != null) _status.value = "后端连接失败：$err"
        }
    }

    // ---- 公告（全局）----
    private val _annoTitle = MutableStateFlow("")
    val annoTitle = _annoTitle.asStateFlow()

    private val _annoContent = MutableStateFlow("")
    val annoContent = _annoContent.asStateFlow()

    private val _annoStatus = MutableStateFlow("")
    val annoStatus = _annoStatus.asStateFlow()

    private val _annoLoading = MutableStateFlow(false)
    val annoLoading = _annoLoading.asStateFlow()

    // ---- 用户列表 ----
    private val _users = MutableStateFlow<List<AdminApiClient.AdminUser>>(emptyList())
    val users = _users.asStateFlow()

    private val _usersLoading = MutableStateFlow(false)
    val usersLoading = _usersLoading.asStateFlow()

    // ---- 选中用户详情 ----
    private val _selectedUser = MutableStateFlow<AdminApiClient.AdminUserDetail?>(null)
    val selectedUser = _selectedUser.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading = _detailLoading.asStateFlow()

    // ---- 通用状态条 ----
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()

    // ---- 动作 ----

    fun onTitleChange(v: String) { _annoTitle.value = v }
    fun onContentChange(v: String) { _annoContent.value = v }

    /** 拉取用户列表。 */
    fun loadUsers() {
        val token = _adminToken.value ?: run {
            _status.value = "尚未连接后端，请检查网络或重启应用"
            return
        }
        _usersLoading.value = true
        viewModelScope.launch {
            val (list, err) = AdminApiClient.listUsers(token)
            if (err == null) _users.value = list
            else _status.value = err
            _usersLoading.value = false
        }
    }

    /** 打开用户详情（同时拉取详情 + 刷新列表中的该条）。 */
    fun openUser(uid: Int) {
        val token = _adminToken.value ?: return
        _detailLoading.value = true
        viewModelScope.launch {
            val (detail, err) = AdminApiClient.userDetail(token, uid)
            _selectedUser.value = detail
            if (err != null) _status.value = err
            _detailLoading.value = false
        }
    }

    fun closeUser() { _selectedUser.value = null }

    /** 禁用 / 启用某功能（如 overlay / sync / alert 等）。 */
    fun toggleFeature(uid: Int, feature: String, disabled: Boolean) {
        val token = _adminToken.value ?: return
        viewModelScope.launch {
            val err = AdminApiClient.disableFeature(token, uid, feature, disabled)
            if (err != null) _status.value = err
            else openUser(uid) // 刷新详情
        }
    }

    /** 设置账号状态（normal / restricted / disabled）。 */
    fun setStatus(uid: Int, status: String, note: String) {
        val token = _adminToken.value ?: return
        viewModelScope.launch {
            val err = AdminApiClient.setStatus(token, uid, status, note)
            if (err != null) _status.value = err
            else { _status.value = "已更新状态：$status"; openUser(uid) }
        }
    }

    /** 给当前选中用户单独发公告。 */
    fun announceUser(uid: Int, title: String, content: String) {
        val token = _adminToken.value ?: return
        viewModelScope.launch {
            val err = AdminApiClient.announceUser(token, uid, title, content)
            _status.value = err ?: "已向该用户发送单独公告"
            if (err == null) openUser(uid)
        }
    }

    /** 发布全局公告。 */
    fun publishAnnouncement() {
        val token = _adminToken.value ?: run {
            _annoStatus.value = "尚未连接后端"
            return
        }
        _annoLoading.value = true
        viewModelScope.launch {
            val err = AdminApiClient.publishAnnouncement(token, _annoTitle.value, _annoContent.value)
            _annoStatus.value = err ?: "发布成功"
            _annoLoading.value = false
        }
    }
}
