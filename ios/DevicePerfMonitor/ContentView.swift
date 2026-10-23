//
//  ContentView.swift
//  DevicePerfMonitor
//
//  根导航：总览 / 设置 两个顶级页面 + 指标详情页（NavigationStack）。
//  底部浮动胶囊标签栏（FloatingCapsuleBar，非系统 UITabBar，与 Android 同构）。
//  GlassTabs 仍在详情页与设置页内使用（那里是页内分段控件，不是顶级导航）。
//

import SwiftUI

struct ContentView: View {
    @Environment(\.colors) private var colors
    @ObservedObject private var overlay = FloatingWindowController.shared
    @State private var tab: Tab = .overview
    @State private var detailMetric: MetricId?

    private enum Tab: String, CaseIterable {
        case overview, settings

        var label: String {
            switch self {
            case .overview: return "总览"
            case .settings: return "设置"
            }
        }

        /// SF Symbols 图标：与 Android 的 Material Icons（Home / Settings）对应，
        /// 双端形态保持一致（Android 只有 material-icons-core，没有 extended）。
        var icon: String {
            switch self {
            case .overview: return "house.fill"
            case .settings: return "gearshape.fill"
            }
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                colors.background.ignoresSafeArea()

                Group {
                    switch tab {
                    case .overview:
                        OverviewScreen()
                    case .settings:
                        SettingsScreen()
                    }
                }

                // 浮动胶囊底栏（1.0.9）：与 Android 的 BottomNavBar 同一组 token
                VStack {
                    Spacer()
                    FloatingCapsuleBar(
                        items: Tab.allCases.map { (label: $0.label, icon: $0.icon) },
                        selectedIndex: Binding(
                            get: { Tab.allCases.firstIndex(of: tab) ?? 0 },
                            set: { tab = Tab.allCases[$0] }
                        )
                    )
                    .padding(.horizontal, DesignTokens.BottomBar.marginHorizontal)
                    .padding(.bottom, DesignTokens.BottomBar.marginBottom)
                }
            }
            .navigationDestination(item: $detailMetric) { metric in
                MetricDetailScreen(metricId: metric)
            }
            .navigationBarHidden(true)
            // 拖动悬浮窗时挂出底部删除区（iOS 侧 1.0.9 才补上可见 UI）
            .overlay(alignment: .bottom) {
                if overlay.state.dragging {
                    OverlayTrashZone(overTrash: overlay.state.overTrash)
                        .padding(.bottom, DesignTokens.Spacing.s32)
                        .transition(.opacity.combined(with: .scale(scale: 0.9)))
                }
            }
            .animation(
                .spring(
                    response: DesignTokens.Motion.Spring.FastSpatial.response,
                    dampingFraction: DesignTokens.Motion.Spring.FastSpatial.dampingFraction
                ),
                value: overlay.state.dragging
            )
        }
    }
}

// MARK: - 浮动胶囊底栏

/// 自绘浮动胶囊底栏（1.0.9）。
///
/// 以前是 GlassPanel(pill) 包 GlassTabs：没有投影、选中态是整块填充的标签，
/// 看着像一块贴在底部的色块。现在改成与 Android 同构的形态——胶囊容器 + 真投影 +
/// 描边，选中态是一条用 matchedGeometryEffect 滑动的 pill 指示器。
///
/// 几何与配色全部取自 design-tokens 的 bottomBar 段，与 Android 用同一组数字。
private struct FloatingCapsuleBar: View {
    @Environment(\.colors) private var colors
    @Namespace private var indicator
    let items: [(label: String, icon: String)]
    @Binding var selectedIndex: Int

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items.indices, id: \.self) { i in
                Button {
                    guard selectedIndex != i else { return }
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    withAnimation(
                        .spring(
                            response: DesignTokens.Motion.Spring.FastSpatial.response,
                            dampingFraction: DesignTokens.Motion.Spring.FastSpatial.dampingFraction
                        )
                    ) {
                        selectedIndex = i
                    }
                } label: {
                    VStack(spacing: 2) {
                        Image(systemName: items[i].icon)
                            .font(.system(size: DesignTokens.BottomBar.iconSize))
                        Text(items[i].label)
                            .font(Typography.mono(DesignTokens.BottomBar.labelSize))
                    }
                    .foregroundStyle(selectedIndex == i ? colors.accent : colors.textSecondary)
                    .scaleEffect(selectedIndex == i ? DesignTokens.BottomBar.selectedScale : 1.0)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(alignment: .center) {
                        if selectedIndex == i {
                            Capsule()
                                .fill(colors.accent.opacity(DesignTokens.BottomBar.indicatorAlpha))
                                .padding(DesignTokens.BottomBar.indicatorInset)
                                .matchedGeometryEffect(id: "navIndicator", in: indicator)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .frame(height: DesignTokens.BottomBar.height)
        .background(alignment: .center) {
            Capsule()
                .fill(colors.surfaceContainer)
                .overlay(
                    Capsule()
                        .stroke(
                            colors.outline.opacity(DesignTokens.Surface.borderAlpha),
                            lineWidth: DesignTokens.Surface.borderWidth
                        )
                )
                .shadow(
                    color: Color.black.opacity(DesignTokens.Elevation.Level2.alpha),
                    radius: DesignTokens.Elevation.Level2.radius,
                    x: 0,
                    y: DesignTokens.Elevation.Level2.shadowY
                )
        }
        // 指示器滑动的整体动效（matchedGeometryEffect 需要动画上下文）
        .animation(
            .spring(
                response: DesignTokens.Motion.Spring.FastSpatial.response,
                dampingFraction: DesignTokens.Motion.Spring.FastSpatial.dampingFraction
            ),
            value: selectedIndex
        )
    }
}
