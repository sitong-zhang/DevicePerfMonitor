//
//  ContentView.swift
//  DevicePerfMonitor
//
//  根导航：总览 / 设置 两个顶级页面 + 指标详情页（NavigationStack）。
//  底部自封装玻璃标签栏（GlassTabs，非系统 UITabBar）。
//

import SwiftUI

struct ContentView: View {
    @Environment(\.colors) private var colors
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

                // 底部玻璃标签栏
                VStack {
                    Spacer()
                    GlassPanel(style: GlassStyle(cornerRadius: DesignTokens.Radius.pill, outerShadow: false)) {
                        GlassTabs(
                            options: Tab.allCases.map { $0.label },
                            selectedIndex: Binding(
                                get: { Tab.allCases.firstIndex(of: tab) ?? 0 },
                                set: { tab = Tab.allCases[$0] }
                            )
                        )
                    }
                    .padding(.horizontal, DesignTokens.Spacing.s24)
                    .padding(.bottom, DesignTokens.Spacing.s24)
                }
            }
            .navigationDestination(item: $detailMetric) { metric in
                MetricDetailScreen(metricId: metric)
            }
            .navigationBarHidden(true)
        }
    }
}
