package com.molecule.manager

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.molecule.manager.network.GitHubClient
import com.molecule.security.TokenVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 管理端 ViewModel：公告管理（1.0.1 起撤掉反馈收件箱/回复，反馈由用户端
 * 直接进邮箱）。
 */
class ManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()

    // ---- 公告 ----
    private val _annoTitle = MutableStateFlow("")
    val annoTitle = _annoTitle.asStateFlow()

    private val _annoContent = MutableStateFlow("")
    val annoContent = _annoContent.asStateFlow()

    private val _annoStatus = MutableStateFlow("")
    val annoStatus = _annoStatus.asStateFlow()

    private val _annoLoading = MutableStateFlow(false)
    val annoLoading = _annoLoading.asStateFlow()

    // ---- token 可用性（受安全模块约束） ----
    val tokenAvailable: Boolean get() = TokenVault.getToken(ctx) != null

    // ---- Brand 签名回执 ----
    private val _signReports = MutableStateFlow<List<com.molecule.manager.network.SignReportData>>(emptyList())
    val signReports = _signReports.asStateFlow()

    private val _signReportsStatus = MutableStateFlow("")
    val signReportsStatus = _signReportsStatus.asStateFlow()

    private val _signReportsLoading = MutableStateFlow(false)
    val signReportsLoading = _signReportsLoading.asStateFlow()

    fun loadSignReports() {
        _signReportsLoading.value = true
        _signReportsStatus.value = ""
        viewModelScope.launch {
            runCatching { GitHubClient.fetchSignReports() }
                .onSuccess {
                    _signReports.value = it
                    _signReportsStatus.value = if (it.isEmpty()) "暂无签名记录" else "共 ${it.size} 条记录"
                }
                .onFailure { _signReportsStatus.value = "加载失败：${it.message}" }
            _signReportsLoading.value = false
        }
    }

    fun loadAnnouncement() {
        _annoLoading.value = true
        _annoStatus.value = ""
        viewModelScope.launch {
            runCatching { GitHubClient.fetchAnnouncement() }
                .onSuccess {
                    _annoTitle.value = it.title
                    _annoContent.value = it.content
                    _annoStatus.value = "已加载线上公告（更新于 ${it.updatedAt}）"
                }
                .onFailure { _annoStatus.value = "加载失败：${it.message}" }
            _annoLoading.value = false
        }
    }

    fun onTitleChange(v: String) { _annoTitle.value = v }
    fun onContentChange(v: String) { _annoContent.value = v }

    fun publishAnnouncement() {
        _annoLoading.value = true
        viewModelScope.launch {
            val (code, msg) = GitHubClient.publishAnnouncement(ctx, _annoTitle.value, _annoContent.value)
            _annoStatus.value = "$msg（HTTP $code）"
            _annoLoading.value = false
        }
    }
}
