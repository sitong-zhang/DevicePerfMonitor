package com.devperf.monitor.ui

/**
 * 意见反馈的提交状态（1.0.28 起为邮箱通道，纯本地拼装、无网络请求）。
 *
 * - [Idle]：没提交过；
 * - [EmailReady]：预填邮件已就绪（[mailtoUrl]），反馈页拉起邮件 App 发送；
 *   打不开邮件 App 时反馈页降级为复制到剪贴板；
 * - [Failed]：异常兜底（正常流程不会走到）。
 */
sealed interface FeedbackUiState {
    data object Idle : FeedbackUiState
    data class EmailReady(val mailtoUrl: String) : FeedbackUiState
    data class Failed(val reason: String) : FeedbackUiState
}
