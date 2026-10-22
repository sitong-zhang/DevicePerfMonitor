package com.devperf.monitor.overlay

import android.animation.ValueAnimator
import android.content.Context
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
import kotlinx.coroutines.launch

/**
 * 悬浮窗控制器：窗口位置 / 尺寸 / 形态状态机。
 *
 * 职责（对应需求悬浮窗条目）：
 * - 窗口标志：FLAG_NOT_TOUCH_MODAL（窗口外触摸穿透到后方应用）；
 * - 窗口尺寸 = 可见区域含阴影（内容 + 10dp 阴影边距，WRAP_CONTENT 语义由显式尺寸实现）；
 * - 长按拖动 + 边缘吸附（[onDragEnd] 中按 EDGE_SNAP_THRESHOLD_DP 吸附）；
 * - 双指缩放（0.8 ~ 1.6，design-tokens gesture.scaleRange）；
 * - 贴边收窄：吸附到左右边缘后自动收窄为窄条，拖出或点击恢复；
 * - 底部垃圾桶：拖动经过垃圾桶释放即关闭；
 * - 单击：展开/收起 6 项面板；双击：隐藏（保留服务，通知栏可恢复）。
 *
 * 线程约定：全部方法必须在主线程调用（Compose 手势回调与系统回调均为主线程）。
 */
class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onRequestClose: () -> Unit,
) {

    enum class Mode { MINI, STANDARD }

    data class UiState(
        val visible: Boolean = false,
        val mode: Mode = Mode.MINI,
        /** 贴边收窄态：吸附左右边缘后自动收窄。 */
        val collapsed: Boolean = false,
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
        }
        runCatching { windowManager.addView(v, lp) }
            .onFailure {
                Log.w(TAG, "悬浮窗添加失败（权限或系统限制）", it)
                return
            }
        view = v
        params = lp
        trash = TrashController(context, windowManager)
        _uiState.value = _uiState.value.copy(visible = true)
        applyLayout(animated = false)
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
        // 收窄态下拖动即展开（贴边收窄让位给拖动，优先级最低）
        if (_uiState.value.collapsed) {
            _uiState.value = _uiState.value.copy(collapsed = false)
            applyLayout(animated = true)
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
        // 边缘吸附 + 贴边收窄
        val (sw, sh) = screenSize()
        val snapPx = dpToPx(DesignTokens.Gesture.EDGE_SNAP_THRESHOLD_DP)
        val w = currentWidthPx()
        var snapped = false
        val targetX = when {
            p.x <= snapPx -> { snapped = true; 0 }
            p.x + w >= sw - snapPx -> { snapped = true; sw - w }
            else -> p.x
        }
        // 垂直方向夹回屏幕内
        val h = currentHeightPx()
        val targetY = p.y.coerceIn(0, (sh - h).coerceAtLeast(0))
        if (snapped) {
            _uiState.value = _uiState.value.copy(collapsed = true)
        }
        animatePosition(targetX, targetY) {
            applyLayout(animated = false)
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
        applyLayout(animated = false)
    }

    /** 单击：收窄态 → 展开；否则在 迷你/标准 面板间切换。 */
    fun onSingleTap() {
        val s = _uiState.value
        when {
            s.collapsed -> {
                _uiState.value = s.copy(collapsed = false)
                applyLayout(animated = true)
            }
            s.mode == Mode.MINI -> {
                _uiState.value = s.copy(mode = Mode.STANDARD)
                applyLayout(animated = true)
            }
            else -> {
                _uiState.value = s.copy(mode = Mode.MINI)
                applyLayout(animated = true)
            }
        }
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
    // 窗口尺寸 / 位置
    // ---------------------------------------------------------------------

    private fun widthForStateDp(): Float = when {
        !_uiState.value.visible -> 0f
        _uiState.value.collapsed -> COLLAPSED_W_DP
        _uiState.value.mode == Mode.MINI -> MINI_W_DP * scaleFactor
        else -> STANDARD_W_DP * scaleFactor
    }

    private fun heightForStateDp(): Float = when {
        !_uiState.value.visible -> 0f
        _uiState.value.collapsed -> COLLAPSED_H_DP
        _uiState.value.mode == Mode.MINI -> MINI_H_DP * scaleFactor
        else -> STANDARD_H_DP * scaleFactor
    }

    private fun currentWidthPx(): Int = dpToPx(widthForStateDp()).toInt()

    private fun currentHeightPx(): Int = dpToPx(heightForStateDp()).toInt()

    /** dp（Float）→ 像素。等价于 `dp.dp.toPx()`，但不依赖 Compose Density 接收者。 */
    private fun dpToPx(dp: Float): Float = dp * context.resources.displayMetrics.density

    private fun applyLayout(animated: Boolean) {
        val v = view ?: return
        val p = params ?: return
        val (sw, sh) = screenSize()
        val targetW = currentWidthPx()
        val targetH = currentHeightPx()
        var x = p.x
        var y = p.y
        if (!_uiState.value.dragging) {
            x = x.coerceIn(0, (sw - targetW).coerceAtLeast(0))
            y = y.coerceIn(0, (sh - targetH).coerceAtLeast(0))
        }
        if (animated) {
            animateWindow(v, p, x, y, targetW, targetH)
        } else {
            p.width = targetW
            p.height = targetH
            p.x = x
            p.y = y
            runCatching { windowManager.updateViewLayout(v, p) }
        }
    }

    private fun animateWindow(
        v: View,
        p: WindowManager.LayoutParams,
        targetX: Int,
        targetY: Int,
        targetW: Int,
        targetH: Int,
    ) {
        positionAnimator?.cancel()
        val fromX = p.x; val fromY = p.y
        val fromW = p.width; val fromH = p.height
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            addUpdateListener { va ->
                val f = va.animatedFraction
                p.x = (fromX + (targetX - fromX) * f).toInt()
                p.y = (fromY + (targetY - fromY) * f).toInt()
                p.width = (fromW + (targetW - fromW) * f).toInt().coerceAtLeast(1)
                p.height = (fromH + (targetH - fromH) * f).toInt().coerceAtLeast(1)
                runCatching { windowManager.updateViewLayout(v, p) }
            }
        }
        positionAnimator = anim
        anim.start()
    }

    private fun animatePosition(targetX: Int, targetY: Int, onEnd: () -> Unit) {
        val v = view ?: return
        val p = params ?: return
        positionAnimator?.cancel()
        val fromX = p.x; val fromY = p.y
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            addUpdateListener { va ->
                val f = va.animatedFraction
                p.x = (fromX + (targetX - fromX) * f).toInt()
                p.y = (fromY + (targetY - fromY) * f).toInt()
                runCatching { windowManager.updateViewLayout(v, p) }
            }
            doOnEnd { onEnd() }
        }
        positionAnimator = anim
        anim.start()
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                block()
            }
        })
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

    /** 设置变化（迷你模式默认值）时同步。 */
    fun setDefaultMiniMode(mini: Boolean) {
        val target = if (mini) Mode.MINI else Mode.STANDARD
        if (_uiState.value.mode != target && !_uiState.value.dragging) {
            _uiState.value = _uiState.value.copy(mode = target)
            applyLayout(animated = false)
        }
    }

    companion object {
        private const val TAG = "OverlayController"

        // 悬浮窗形态基准尺寸（dp）。阴影边距 10dp 含在窗口内（窗口尺寸=可见区域含阴影）。
        const val SHADOW_PADDING_DP = 10f
        const val MINI_W_DP = 216f
        const val MINI_H_DP = 60f
        const val STANDARD_W_DP = 272f
        const val STANDARD_H_DP = 156f
        const val COLLAPSED_W_DP = 26f
        const val COLLAPSED_H_DP = 148f

        const val FLAG_NOT_TOUCH_MODAL = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        const val FLAG_LAYOUT_NO_LIMITS = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        const val FLAG_HARDWARE_ACCELERATED = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}

/** 阴影边距（悬浮窗 Compose 内容使用，与控制器窗口尺寸计算保持一致）。 */
val OverlayShadowPaddingDp: Float get() = OverlayController.SHADOW_PADDING_DP
