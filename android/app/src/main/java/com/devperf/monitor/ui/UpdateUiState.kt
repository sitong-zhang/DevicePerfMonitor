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
 * - [NoUpdate] / [Dismissed] / [Error]：终态，不弹窗。
 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object NoUpdate : UpdateUiState
    data object Dismissed : UpdateUiState
    data object Error : UpdateUiState
    data class Available(val release: AppRelease) : UpdateUiState
    data class Downloading(val progress: Int) : UpdateUiState
    data class Ready(val file: File) : UpdateUiState
}
