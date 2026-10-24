package com.devperf.monitor.ui

import com.devperf.monitor.data.AppRelease
import java.io.File

/**
 * 应用内更新检查的状态机。
 *
 * - [Idle]：尚未检查；
 * - [Checking]：正在查 GitHub Releases；
 * - [Available]：有新版本，弹出更新提示；
 * - [Downloading]：正在下载 APK，携带进度 0~100；
 * - [Ready]：APK 已下完，等待用户点「安装」；
 * - [NoUpdate] / [Error]：终态，不弹窗（Error 为网络失败，不锁死离线用户）。
 *
 * 注意：[Available] / [Downloading] / [Ready] 三个态均不可关闭——
 * 强制更新期间旧版本任何功能都无法使用，必须更新。
 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object NoUpdate : UpdateUiState
    data object Error : UpdateUiState
    data class Available(val release: AppRelease) : UpdateUiState
    data class Downloading(val progress: Int) : UpdateUiState
    data class Ready(val file: File) : UpdateUiState
}
