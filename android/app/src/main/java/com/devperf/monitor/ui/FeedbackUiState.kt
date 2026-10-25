package com.devperf.monitor.ui

/**
 * 意见反馈的提交状态机（1.0.26 新增）。
 *
 * - [Idle]：没提交过；
 * - [Submitting]：正在创建 issue；
 * - [Submitted]：token 通道成功，[issueUrl] 可空（解析失败时至少告诉用户成功了）；
 * - [BrowserFallback]：没配 token，设置页拿到 [url] 后直接拉起系统浏览器
 *   （GitHub new-issue 页，标题/正文/label 已预填）；
 * - [Failed]：网络或服务端失败，[reason] 直接展示给用户。
 */
sealed interface FeedbackUiState {
    data object Idle : FeedbackUiState
    data object Submitting : FeedbackUiState
    data class Submitted(val issueUrl: String?) : FeedbackUiState
    data class BrowserFallback(val url: String) : FeedbackUiState
    data class Failed(val reason: String) : FeedbackUiState
}
