package com.devperf.monitor.ui

import com.devperf.monitor.data.AppAnnouncement

/** 公告弹窗状态。 */
sealed interface AnnouncementUiState {
    /** 没有要显示的公告。 */
    data object Idle : AnnouncementUiState

    /** 有未读公告，需展示（且必须滚到最底才能关）。 */
    data class Show(val announcement: AppAnnouncement) : AnnouncementUiState
}
