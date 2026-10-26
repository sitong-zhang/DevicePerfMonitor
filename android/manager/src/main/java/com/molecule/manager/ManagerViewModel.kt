package com.molecule.manager

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.molecule.manager.network.FeedbackComment
import com.molecule.manager.network.FeedbackIssue
import com.molecule.manager.network.GitHubClient
import com.molecule.security.TokenVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 管理端 ViewModel：公告管理 + 反馈收件箱的状态与动作。
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

    // ---- 反馈 ----
    private val _issues = MutableStateFlow<List<FeedbackIssue>>(emptyList())
    val issues = _issues.asStateFlow()

    private val _issuesLoading = MutableStateFlow(false)
    val issuesLoading = _issuesLoading.asStateFlow()

    private val _selectedIssue = MutableStateFlow<FeedbackIssue?>(null)
    val selectedIssue = _selectedIssue.asStateFlow()

    // ---- 反馈详情：评论对话 + 回复 ----
    private val _comments = MutableStateFlow<List<FeedbackComment>>(emptyList())
    val comments = _comments.asStateFlow()

    private val _commentsLoading = MutableStateFlow(false)
    val commentsLoading = _commentsLoading.asStateFlow()

    private val _replyText = MutableStateFlow("")
    val replyText = _replyText.asStateFlow()

    private val _replyStatus = MutableStateFlow("")
    val replyStatus = _replyStatus.asStateFlow()

    private val _replyBusy = MutableStateFlow(false)
    val replyBusy = _replyBusy.asStateFlow()

    // ---- token 可用性（受安全模块约束） ----
    val tokenAvailable: Boolean get() = TokenVault.getToken(ctx) != null

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

    fun loadIssues() {
        _issuesLoading.value = true
        viewModelScope.launch {
            runCatching { GitHubClient.fetchFeedbackIssues() }
                .onSuccess { _issues.value = it }
                .onFailure { _issues.value = emptyList() }
            _issuesLoading.value = false
        }
    }

    fun selectIssue(it: FeedbackIssue?) {
        _selectedIssue.value = it
        if (it != null) {
            _comments.value = emptyList()
            _replyText.value = ""
            _replyStatus.value = ""
            loadComments(it.number)
        }
    }

    /** 进入详情时加载该 issue 的全部评论。 */
    fun loadComments(number: Int) {
        _commentsLoading.value = true
        viewModelScope.launch {
            runCatching { GitHubClient.fetchIssueComments(number, TokenVault.getToken(ctx)) }
                .onSuccess { _comments.value = it }
                .onFailure { _comments.value = emptyList() }
            _commentsLoading.value = false
        }
    }

    fun onReplyChange(v: String) { _replyText.value = v }

    fun postReply(number: Int) {
        val text = _replyText.value.trim()
        if (text.isBlank()) return
        _replyBusy.value = true
        _replyStatus.value = "发送中…"
        viewModelScope.launch {
            val (code, msg) = GitHubClient.postIssueComment(ctx, number, text)
            _replyStatus.value = "$msg（HTTP $code）"
            if (code in 200..299) {
                _replyText.value = ""
                // 刷新对话
                runCatching { GitHubClient.fetchIssueComments(number, TokenVault.getToken(ctx)) }
                    .onSuccess { _comments.value = it }
            }
            _replyBusy.value = false
        }
    }

    /** 标记已解决（关闭）或重新打开。 */
    fun resolveIssue(number: Int, closed: Boolean) {
        _replyBusy.value = true
        _replyStatus.value = if (closed) "关闭中…" else "重新打开中…"
        viewModelScope.launch {
            val (code, msg) = GitHubClient.setIssueState(ctx, number, closed)
            _replyStatus.value = "$msg（HTTP $code）"
            if (code in 200..299) {
                // 更新列表里这条的状态，并刷新详情
                _issues.value = _issues.value.map {
                    if (it.number == number) it.copy(state = if (closed) "closed" else "open") else it
                }
                _selectedIssue.value = _selectedIssue.value?.copy(state = if (closed) "closed" else "open")
            }
            _replyBusy.value = false
        }
    }
}
