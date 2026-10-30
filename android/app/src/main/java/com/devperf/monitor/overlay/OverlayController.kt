package com.devperf.monitor.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.devperf.monitor.ui.theme.DesignTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮窗控制器：窗口位置 / 尺寸 / 三态形态状态机。
 *
 * 职责（对应需求悬浮窗条目，1.0.8 重构）：
 * - 窗口标志：FLAG_NOT_TOUCH_MODAL（窗口外触摸穿透到后方应用）；
 * - 三态循环（单击切换）：CARD 完整卡片 → NUMBERS 纯数字 → COLLAPSED 收起；
 *   * CARD：默认形态，一退出应用就稳定显示这张卡片，不自动变形；
 *   * NUMBERS：只要卡片上的数字，去掉卡片容器与阴影；
 *   * COLLAPSED：收成一条竖线，点一下恢复；
 * - 窗口透明度：params.alpha 由设置页滑杆接线（[setOpacity]，0.4~1.0）；
 * - 双指缩放（0.8 ~ 1.6，初值取设置页默认缩放 settings.overlayScale）；
 * - 长按拖动 + 边缘吸附（[onDragEnd] 按 EDGE_SNAP_THRESHOLD_DP 吸附；
 *   只吸附位置，不再贴边自动收窄——收窄只由用户显式操作触发）；
 * - 底部垃圾桶：拖动经过垃圾桶释放即关闭；
 * - 双击：隐藏（保留服务，通知栏可恢复）。
 * - 动效原则（用户原话「等它显示那张卡片，不要动它」）：窗口尺寸只瞬切，
 *   不做宽高逐帧动画；内容形变交给 Compose AnimatedContent（spring）。
 *
 * 线程约定：全部方法必须在主线程调用（Compose 手势回调与系统回调均为主线程）。
 */
class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    /** ComposeView 挂窗口所需的 ViewTree 宿主（由 Service 提供，垃圾桶窗口共用）。 */
    private val viewTreeOwner: ServiceViewTreeOwner,
    private val onRequestClose: () -> Unit,
) {

    /** 悬浮窗四态。顺序即单击循环顺序。 */
    enum class Mode { CARD, NUMBERS, COMPACT, COLLAPSED }

    data class UiState(
        val visible: Boolean = false,
        val mode: Mode = Mode.CARD,
        val scale: Float = 1f,
        val dragging: Boolean = false,
        val overTrash: Boolean = false,
        /** 高温告警态：悬浮窗整体变红（阈值来自设置页）。 */
        val alert: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var trash: TrashController? = null
    private var positionAnimator: ValueAnimator? = null
    private var scaleFactor = 1f
    private var opacity = 1f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------

    fun attach(v: View) {
        if (view != null) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            // FLAG_NOT_TOUCH_MODAL：窗口之外的触摸事件穿透到后方应用（需求硬性规定）
            FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_NO_LIMITS or FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (sw, sh) = screenSize()
            x = (sw * 0.06f).toInt()
            y = (sh * 0.28f).toInt()
            // 窗口透明度：设置页滑杆（0.4~1.0），默认 1.0 稳定显示
            alpha = opacity
        }
        runCatching { windowManager.addView(v, lp) }
            .onFailure {
                Log.w(TAG, "悬浮窗添加失败（权限或系统限制）", it)
                return
            }
        view = v
        params = lp
        trash = TrashController(context, windowManager, viewTreeOwner)
        _uiState.value = _uiState.value.copy(visible = true)
        applyLayout()
    }

    fun detach() {
        view?.let { v ->
            runCatching { windowManager.removeView(v) }
                .onFailure { Log.w(TAG, "悬浮窗移除失败", it) }
        }
        view = null
        params = null
        trash?.dismiss()
        trash = null
        positionAnimator?.cancel()
        positionAnimator = null
        scaleFactor = 1f
        _uiState.value = UiState()
    }

    /** 双击隐藏：移除窗口但保留服务（通知栏提供「显示」入口）。 */
    fun hide() {
        view?.let { v ->
            runCatching { windowManager.removeView(v) }
                .onFailure { Log.w(TAG, "悬浮窗隐藏失败", it) }
        }
        view = null
        params = null
        trash?.dismiss()
        positionAnimator?.cancel()
        positionAnimator = null
        _uiState.value = _uiState.value.copy(visible = false, dragging = false, overTrash = false)
    }

    /** 从通知栏恢复显示。 */
    fun show(v: View) {
        if (view != null) return
        attach(v)
    }

    // ---------------------------------------------------------------------
    // 手势回调（来自 OverlayGestureCallbacks）
    // ---------------------------------------------------------------------

    fun onDragStart() {
        // 收起态下拖动即展开（让位给拖动，优先级最低）
        if (_uiState.value.mode == Mode.COLLAPSED) {
            _uiState.value = _uiState.value.copy(mode = Mode.CARD)
            applyLayout()
        }
        _uiState.value = _uiState.value.copy(dragging = true)
        trash?.show()
    }

    fun onDrag(deltaPx: androidx.compose.ui.geometry.Offset) {
        val p = params ?: return
        positionAnimator?.cancel()
        positionAnimator = null
        p.x += deltaPx.x.toInt()
        p.y += deltaPx.y.toInt()
        clampPosition(p)
        view?.let { runCatching { windowManager.updateViewLayout(it, p) } }
    }

    fun onDragEnd() {
        val p = params ?: return
        _uiState.value = _uiState.value.copy(dragging = false)
        // 垃圾桶释放 → 关闭
        if (_uiState.value.overTrash) {
            trash?.dismiss()
            _uiState.value = _uiState.value.copy(overTrash = false)
            onRequestClose()
            return
        }
        trash?.dismiss()
        // 边缘吸附：只把窗口拉回边缘内（位置动画），不改变形态——
        // 收窄/收起只由用户单击显式触发（需求：不要动它）。
        val (sw, sh) = screenSize()
        val snapPx = dpToPx(DesignTokens.Gesture.EDGE_SNAP_THRESHOLD_DP)
        val w = currentWidthPx()
        val snapped = p.x <= snapPx || p.x + w >= sw - snapPx
        val targetX = when {
            p.x <= snapPx -> 0
            p.x + w >= sw - snapPx -> (sw - w).coerceAtLeast(0)
            else -> p.x
        }
        // 垂直方向夹回屏幕内
        val h = currentHeightPx()
        val targetY = p.y.coerceIn(0, (sh - h).coerceAtLeast(0))
        if (snapped) {
            animatePosition(targetX, targetY)
        } else {
            p.x = targetX
            p.y = targetY
            view?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }
    }

    fun onScale(factor: Float) {
        val next = (scaleFactor * factor).coerceIn(
            DesignTokens.Gesture.SCALE_MIN,
            DesignTokens.Gesture.SCALE_MAX,
        )
        if (next == scaleFactor) return
        scaleFactor = next
        _uiState.value = _uiState.value.copy(scale = scaleFactor)
        applyLayout()
    }

    /**
     * 单击：三态循环 CARD → NUMBERS → COLLAPSED → CARD。
     *
     * 收起态单击恢复为完整卡片（最常见的「把它叫回来」路径）。
     */
    fun onSingleTap() {
        val s = _uiState.value
        val next = when (s.mode) {
            Mode.CARD -> Mode.NUMBERS
            Mode.NUMBERS -> Mode.COMPACT
            Mode.COMPACT -> Mode.COLLAPSED
            Mode.COLLAPSED -> Mode.CARD
        }
        _uiState.value = s.copy(mode = next)
        applyLayout()
    }

    /** 双击：隐藏悬浮窗（服务保活，通知栏恢复）。 */
    fun onDoubleTap() {
        hide()
    }

    /** 触点本地坐标 → 屏幕坐标，判定是否悬在垃圾桶上。 */
    fun onTouchLocal(local: androidx.compose.ui.geometry.Offset) {
        val p = params ?: return
        if (!_uiState.value.dragging) return
        val fingerX = p.x + local.x
        val fingerY = p.y + local.y
        val over = trash?.contains(fingerX, fingerY) ?: false
        if (over != _uiState.value.overTrash) {
            _uiState.value = _uiState.value.copy(overTrash = over)
            trash?.setActive(over)
        }
    }

    fun setAlert(active: Boolean) {
        if (_uiState.value.alert != active) {
            _uiState.value = _uiState.value.copy(alert = active)
        }
    }

    // ---------------------------------------------------------------------
    // 设置联动
    // ---------------------------------------------------------------------

    /**
     * 窗口透明度（设置页滑杆，0.4~1.0）。
     * alpha 作用于整个窗口（含阴影），与卡片自身的内容透明度叠加。
     */
    fun setOpacity(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (next == opacity) return
        opacity = next
        val v = view ?: return
        val p = params ?: return
        p.alpha = next
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    /** 设置页默认缩放变化：重置双指缩放系数并重排窗口。 */
    fun setScaleFromSettings(value: Float) {
        scaleFactor = value.coerceIn(
            DesignTokens.Gesture.SCALE_MIN,
            DesignTokens.Gesture.SCALE_MAX,
        )
        _uiState.value = _uiState.value.copy(scale = scaleFactor)
        applyLayout()
    }

    /** 设置页默认形态变化：非拖动态时切换默认三态。 */
    fun setDefaultMode(mode: Mode) {
        if (_uiState.value.mode != mode && !_uiState.value.dragging) {
            _uiState.value = _uiState.value.copy(mode = mode)
            applyLayout()
        }
    }

    /**
     * 屏幕配置变化（旋转 / 分屏 / 折叠屏展开 / 多窗口调整）。
     * Service 经 [OverlayService.onConfigurationChanged] 转发到这里：
     * 重新夹取窗口位置与尺寸，避免旋转后悬浮窗跑到屏幕外（适配不完整修复）。
     */
    fun onScreenConfigChanged() {
        applyLayout()
    }

    // ---------------------------------------------------------------------
    // 窗口尺寸 / 位置
    // ---------------------------------------------------------------------

    private fun widthForStateDp(): Float = when {
        !_uiState.value.visible -> 0f
        _uiState.value.mode == Mode.COLLAPSED -> COLLAPSED_W_DP
        _uiState.value.mode == Mode.NUMBERS -> NUMBERS_W_DP
        _uiState.value.mode == Mode.COMPACT -> COMPACT_W_DP
        else -> CARD_W_DP
    } * scaleFactor

    private fun heightForStateDp(): Float = when {
        !_uiState.value.visible -> 0f
        _uiState.value.mode == Mode.COLLAPSED -> COLLAPSED_H_DP
        _uiState.value.mode == Mode.NUMBERS -> NUMBERS_H_DP
        _uiState.value.mode == Mode.COMPACT -> COMPACT_H_DP
        else -> CARD_H_DP
    } * scaleFactor

    private fun currentWidthPx(): Int = dpToPx(widthForStateDp()).toInt()

    private fun currentHeightPx(): Int = dpToPx(heightForStateDp()).toInt()

    /** dp（Float）→ 像素。等价于 `dp.dp.toPx()`，但不依赖 Compose Density 接收者。 */
    private fun dpToPx(dp: Float): Float = dp * context.resources.displayMetrics.density

    /**
     * 同步窗口尺寸与位置。
     *
     * 尺寸一律瞬切（用户原话「不要动它」：不要拿宽高动画去形变用户正在看的卡片）；
     * 内容形变由 Compose AnimatedContent 在内容层完成。位置仅在非拖动时夹回屏幕内。
     */
    private fun applyLayout() {
        val v = view ?: return
        val p = params ?: return
        val (sw, sh) = screenSize()
        val targetW = currentWidthPx()
        val targetH = currentHeightPx()
        p.width = targetW
        p.height = targetH
        p.alpha = opacity
        if (!_uiState.value.dragging) {
            p.x = p.x.coerceIn(0, (sw - targetW).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (sh - targetH).coerceAtLeast(0))
        }
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    /** 位置吸附动画：只动 x/y，不碰宽高。 */
    private fun animatePosition(targetX: Int, targetY: Int) {
        val v = view ?: return
        val p = params ?: return
        positionAnimator?.cancel()
        val fromX = p.x
        val fromY = p.y
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            addUpdateListener { va ->
                val f = va.animatedFraction
                p.x = (fromX + (targetX - fromX) * f).toInt()
                p.y = (fromY + (targetY - fromY) * f).toInt()
                runCatching { windowManager.updateViewLayout(v, p) }
            }
        }
        positionAnimator = anim
        anim.start()
    }

    private fun clampPosition(p: WindowManager.LayoutParams) {
        val (sw, sh) = screenSize()
        p.x = p.x.coerceIn(0, (sw - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (sh - p.height).coerceAtLeast(0))
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    companion object {
        private const val TAG = "OverlayController"

        // 悬浮窗三态基准尺寸（dp）。阴影边距 10dp 含在窗口内（窗口尺寸=可见区域含阴影）。
        const val SHADOW_PADDING_DP = 10f
        const val CARD_W_DP = 272f
        const val CARD_H_DP = 156f
        const val NUMBERS_W_DP = 224f
        const val NUMBERS_H_DP = 48f
        const val COMPACT_W_DP = 200f
        const val COMPACT_H_DP = 40f
        const val COLLAPSED_W_DP = 26f
        const val COLLAPSED_H_DP = 148f

        const val FLAG_NOT_TOUCH_MODAL = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        const val FLAG_LAYOUT_NO_LIMITS = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        const val FLAG_HARDWARE_ACCELERATED = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}

/** 阴影边距（悬浮窗 Compose 内容使用，与控制器窗口尺寸计算保持一致）。 */
val OverlayShadowPaddingDp: Float get() = OverlayController.SHADOW_PADDING_DP
