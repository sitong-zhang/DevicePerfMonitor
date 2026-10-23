//
//  OverlayTrashZone.swift
//  DevicePerfMonitor
//
//  拖拽删除区：拖动悬浮窗时挂在主界面底部，松手即关闭悬浮窗。
//

import SwiftUI

/// 底部删除区（1.0.9 新增）。
///
/// 以前 iOS 侧只有 `handlePan` 里的悬停判定，**屏幕上完全没有这个 UI**——
/// 用户拖着悬浮窗往下走，不知道拖到哪能删掉，也不知道拖到没拖到。
/// 现在与 Android 的 TrashIcon 对齐：圆形容器 + 官方 SF Symbols 图标
/// （不再自绘路径）+ 悬停放大转危险色 +「松手关闭」文字。
///
/// 它挂在主界面（ContentView）上而不是悬浮窗窗口里：iOS 的悬浮窗是应用内窗口，
/// 尺寸只有卡片那么大，装不下屏幕底部的删除区。
struct OverlayTrashZone: View {
    @Environment(\.colors) private var colors
    /// 手指是否悬停在删除区上。
    let overTrash: Bool

    var body: some View {
        VStack(spacing: 4) {
            ZStack {
                Circle()
                    .fill(overTrash ? colors.danger : colors.surfaceContainer.opacity(0.92))
                    .overlay(
                        Circle()
                            .stroke(
                                overTrash
                                    ? colors.danger
                                    : colors.outline.opacity(DesignTokens.Surface.borderAlpha),
                                lineWidth: DesignTokens.Surface.borderWidth
                            )
                    )
                    .shadow(
                        color: Color.black.opacity(DesignTokens.Elevation.Level2.alpha),
                        radius: DesignTokens.Elevation.Level2.radius,
                        x: 0,
                        y: DesignTokens.Elevation.Level2.shadowY
                    )
                Image(systemName: "trash.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(overTrash ? colors.background : colors.textSecondary)
            }
            .frame(width: TrashZone.badgeSize, height: TrashZone.badgeSize)
            .scaleEffect(overTrash ? TrashZone.activeScale : 1.0)

            Text("松手关闭")
                .font(Typography.mono(10))
                .foregroundStyle(colors.danger)
                .opacity(overTrash ? 1 : 0)
        }
        // 放大/变色走 Expressive fastSpatial 弹簧，与悬浮窗三态同一条曲线
        .animation(
            .spring(
                response: DesignTokens.Motion.Spring.FastSpatial.response,
                dampingFraction: DesignTokens.Motion.Spring.FastSpatial.dampingFraction
            ),
            value: overTrash
        )
    }

    private enum TrashZone {
        /// 圆形删除区直径（与 Android TRASH_BADGE_DP 对齐）。
        static let badgeSize: CGFloat = 56
        /// 悬停放大倍数。
        static let activeScale: CGFloat = 1.12
    }
}
