package com.devperf.monitor.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.devperf.monitor.ui.theme.LiquidGlassTheme
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
        const val TRASH_SIZE_DP = 76f
    }
}

/** 垃圾桶图标：纯自绘（盖子 + 桶身 + 提手），激活态放大并变 danger 色。 */
@Composable
private fun TrashIcon(active: Boolean) {
    val colors = LocalTokenColors.current
    val tint = if (active) colors.danger else colors.textSecondary
    LiquidGlassTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(if (active) 1.12f else 1f)
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    val stroke = w * 0.055f
                    // 桶身（圆角矩形，向下略收）
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(w * 0.26f, h * 0.34f),
                        size = Size(w * 0.48f, h * 0.48f),
                        cornerRadius = CornerRadius(w * 0.08f),
                        style = Stroke(width = stroke),
                    )
                    // 桶身竖纹
                    for (i in 0..1) {
                        val x = w * (0.40f + i * 0.20f)
                        drawLine(
                            color = tint.copy(alpha = 0.7f),
                            start = Offset(x, h * 0.42f),
                            end = Offset(x, h * 0.74f),
                            strokeWidth = stroke * 0.6f,
                        )
                    }
                    // 盖子
                    drawLine(
                        color = tint,
                        start = Offset(w * 0.18f, h * 0.30f),
                        end = Offset(w * 0.82f, h * 0.30f),
                        strokeWidth = stroke,
                    )
                    // 提手
                    drawArc(
                        color = tint,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(w * 0.38f, h * 0.14f),
                        size = Size(w * 0.24f, h * 0.16f),
                        style = Stroke(width = stroke * 0.8f),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
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
        view.setContent { LiquidGlassTheme { content() } }
        return view
    }
}

/** 间距常量复用（避免未使用导入告警）。 */
internal val overlayTrashZoneDp: Float get() = DesignTokens.Gesture.TRASH_ZONE_HEIGHT_DP
