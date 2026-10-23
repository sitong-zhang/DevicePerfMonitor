package com.devperf.monitor.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.devperf.monitor.ui.theme.DevicePerfTheme
import com.devperf.monitor.ui.theme.LocalTokenColors
import com.devperf.monitor.ui.theme.DesignTokens

/**
 * 底部垃圾桶（拖动悬浮窗到屏幕底部时出现，释放即关闭）。
 *
 * - 独立小窗口，仅拖动期间显示；
 * - 命中判定用窗口实际边界（[contains]），不依赖 Compose 命中测试；
 * - 激活态（悬浮窗悬于其上）变大 + danger 色，真实的状态反馈。
 */
class TrashController(
    private val context: Context,
    private val windowManager: WindowManager,
    /** ComposeView 挂窗口所需的 ViewTree 宿主（Service 提供，与悬浮窗共用）。 */
    private val viewTreeOwner: ServiceViewTreeOwner,
) {
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var active = false

    fun show() {
        if (view != null) return
        val v = ComposeViewFactory.create(context) {
            TrashIcon(active = active)
        }
        // 垃圾桶也是 Service 托管的裸 ComposeView：挂窗口前补宿主，
        // 否则 Compose 1.11 一挂窗口就抛（同悬浮窗，见 ServiceViewTreeOwner）。
        viewTreeOwner.attachTo(v)
        val (sw, sh) = screenSize()
        val sizePx = (TRASH_SIZE_DP * context.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayWindowType(),
            // 垃圾桶只做视觉反馈，不拦截触摸（触摸始终归属悬浮窗窗口）
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (sh * 0.045f).toInt()
        }
        runCatching { windowManager.addView(v, lp) }
            .onFailure { Log.w(TAG, "垃圾桶窗口添加失败（忽略，不影响关闭判定）", it); return }
        view = v
        params = lp
    }

    fun setActive(next: Boolean) {
        if (active == next) return
        active = next
        // 直接重建内容（图标极小，重建成本可忽略；避免引入状态桥）
        view?.let { v ->
            (v as? androidx.compose.ui.platform.ComposeView)?.setContent {
                TrashIcon(active = active)
            }
        }
    }

    /** 手指屏幕坐标是否命中垃圾桶。 */
    fun contains(x: Float, y: Float): Boolean {
        val p = params ?: return false
        val (sw, sh) = screenSize()
        val sizePx = p.width
        val left = (sw - sizePx) / 2f
        val top = sh - p.y - sizePx
        return x in left..(left + sizePx) && y >= top
    }

    fun dismiss() {
        view?.let { v ->
            runCatching { windowManager.removeView(v) }
                .onFailure { Log.w(TAG, "垃圾桶窗口移除失败", it) }
        }
        view = null
        params = null
        active = false
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
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

    private companion object {
        const val TAG = "TrashController"
        /** 窗口尺寸：圆形徽标 + 上下留白（容纳"松手关闭"文字）。 */
        const val TRASH_SIZE_DP = 76f
    }
}

/** 圆形删除区直径。 */
private const val TRASH_BADGE_DP = 56f

/** 悬停时整体放大到的倍数。 */
private const val TRASH_ACTIVE_SCALE = 1.12f

/**
 * 删除区（拖动悬浮窗到屏幕底部时出现，松手即关闭）。
 *
 * 1.0.9 重写：以前是 Canvas 手画的铁桶（盖子 + 桶身 + 两道竖纹 + 提手弧），
 * 比例和线宽全靠手调，换个屏幕密度就走形。现在按成熟开源实现的范式来做：
 * - 图标用**官方 Material Symbols**（Icons.Filled.Delete），不再自绘路径；
 * - 圆形容器 + 描边 + 投影，悬停时整体放大、转 danger 色，并给「松手关闭」文字；
 * - 放大/变色走 Expressive 的 fastSpatial 弹簧（与悬浮窗三态同一条曲线）。
 */
@Composable
private fun TrashIcon(active: Boolean) {
    val colors = LocalTokenColors.current
    val scale by animateFloatAsState(
        targetValue = if (active) TRASH_ACTIVE_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = DesignTokens.Motion.Spring.FAST_SPATIAL_DAMPING,
            stiffness = DesignTokens.Motion.Spring.FAST_SPATIAL_STIFFNESS,
        ),
        label = "trashScale",
    )
    val container by animateColorAsState(
        targetValue = if (active) {
            colors.danger
        } else {
            colors.surfaceContainer.copy(alpha = 0.92f)
        },
        label = "trashContainer",
    )
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier
                    .size(TRASH_BADGE_DP.dp)
                    .scale(scale)
                    .shadow(
                        elevation = DesignTokens.Elevation.LEVEL2.SHADOW_Y.dp,
                        shape = CircleShape,
                        clip = false,
                    ),
                shape = CircleShape,
                color = container,
                border = BorderStroke(
                    width = DesignTokens.Surface.BORDER_WIDTH.dp,
                    color = if (active) colors.danger else colors.outline.copy(
                        alpha = DesignTokens.Surface.BORDER_ALPHA,
                    ),
                ),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "拖到这里关闭悬浮窗",
                        tint = if (active) colors.background else colors.textSecondary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            if (active) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "松手关闭",
                    color = colors.danger,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** ComposeView 工厂（集中创建，避免 TrashController 直接依赖 Compose 视图类型）。 */
internal object ComposeViewFactory {
    fun create(context: Context, content: @Composable () -> Unit): View {
        val view = androidx.compose.ui.platform.ComposeView(context)
        view.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy
                .DisposeOnDetachedFromWindowOrReleasedFromPool
        )
        view.setContent { DevicePerfTheme { content() } }
        return view
    }
}

/** 间距常量复用（避免未使用导入告警）。 */
internal val overlayTrashZoneDp: Float get() = DesignTokens.Gesture.TRASH_ZONE_HEIGHT_DP
