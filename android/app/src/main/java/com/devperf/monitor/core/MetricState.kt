package com.devperf.monitor.core

/**
 * 指标三态规范（双端 UI 必须一致遵循）：
 *
 * - [MetricState.Available]      AVAILABLE：设备/系统真实提供该数据，正常显示数值；
 * - [MetricState.Unavailable]    UNAVAILABLE：该设备无对应接口（或 SELinux/厂商限制导致读不到），
 *                                UI 显示 "—" + 小字"不可用"；
 * - [MetricState.NeedsPermission] NEEDS_PERMISSION：数据存在但需要用户授权，
 *                                UI 显示"需授权"，点击可跳转对应设置页。
 *
 * 硬性约束：
 * 1. 采集器禁止返回估算值/写死值冒充 AVAILABLE（GPU 频率、电池健康等无公开接口的指标
 *    必须显式进入 Unavailable 或 NeedsPermission）；
 * 2. 图表、健康分、智能建议都必须处理三态：数据不足时输出"数据不足，无法分析"；
 * 3. 任何采集异常（IO 失败、权限撤销）都必须降级为三态之一，禁止崩溃。
 */
sealed interface MetricState<out T> {

    /** 数据可用：[value] 为真实采样值。 */
    data class Available<out T>(val value: T) : MetricState<T>

    /** 设备无此接口 / 当前读取失败：[reason] 为内部诊断原因（不直接展示给用户）。 */
    data class Unavailable(val reason: String) : MetricState<Nothing>

    /** 需要用户授权：[permissionLabel] 为用户可见的权限名，[action] 用于跳转设置。 */
    data class NeedsPermission(
        val permissionLabel: String,
        val action: PermissionAction,
    ) : MetricState<Nothing>
}

/** 需要授权时点击跳转的设置目标。 */
enum class PermissionAction {
    /** 无可用跳转（仅提示）。 */
    NONE,

    /** 悬浮窗权限设置页。 */
    OVERLAY,

    /** 通知权限设置页。 */
    NOTIFICATIONS,

    /** 使用情况访问权（PACKAGE_USAGE_STATS）设置页。 */
    USAGE_STATS,

    /** Shizuku App（安装/启动/授权）。 */
    SHIZUKU,

    /** Root 授权弹窗。 */
    ROOT,
}

/**
 * 单个指标的当前值 + 三态 + 采样时间戳。
 * 时间戳用于 UI 判断数据新鲜度（长时间未更新可显示"不可用"）。
 */
data class Metric<T>(
    val state: MetricState<T>,
    val updatedAtMs: Long = 0L,
) {
    /** 仅当 AVAILABLE 时返回真实值，否则 null。 */
    val value: T?
        get() = (state as? MetricState.Available)?.value

    val isAvailable: Boolean
        get() = state is MetricState.Available

    companion object {
        fun <T> available(value: T, at: Long = System.currentTimeMillis()): Metric<T> =
            Metric(MetricState.Available(value), at)

        fun <T> unavailable(reason: String, at: Long = System.currentTimeMillis()): Metric<T> =
            Metric(MetricState.Unavailable(reason), at)

        fun <T> needsPermission(
            permissionLabel: String,
            action: PermissionAction = PermissionAction.NONE,
            at: Long = System.currentTimeMillis(),
        ): Metric<T> = Metric(MetricState.NeedsPermission(permissionLabel, action), at)
    }
}
