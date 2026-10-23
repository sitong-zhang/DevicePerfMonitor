//
//  MonitorLiveActivity.swift
//  DevicePerfMonitorWidget
//
//  实时活动 UI：灵动岛 + 锁屏两种展示（iOS 16.1+ ActivityKit）。
//  视觉规范与主 App 一致（Catppuccin 语义色 + 等宽数值），
//  告警态整体变红（与 Android 悬浮窗告警一致）。
//

import ActivityKit
import WidgetKit
import SwiftUI

struct MonitorLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: MonitorActivityAttributes.self) { context in
            // 锁屏 / 通知横幅形态
            LockScreenView(state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.35))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                // 展开态
                DynamicIslandExpandedRegion(.leading) {
                    Label("FPS", systemImage: "speedometer")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.state.alert ? "⚠ 告警" : "运行中")
                        .font(.caption2)
                        .foregroundStyle(context.state.alert ? Color.red : Color.green)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(formattedValue(context.state.fps, suffix: " fps"))
                        .font(.system(.title3, design: .monospaced).bold())
                        .foregroundStyle(.white)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    // 1.0.9 口径：温度 + 内存占用率（刷新率已移到应用内显示）。
                    // iOS 没有机身温度公开 API，deviceTempC 常态 nil → 这一项不渲染，
                    // 不拿热状态档位换算冒充（沿项目「采不到的不占位」原则）。
                    HStack(spacing: 12) {
                        if let temp = context.state.deviceTempC {
                            Text("温度 \(formattedValue(temp, suffix: "℃"))")
                        }
                        if let memory = context.state.memoryRatio {
                            Text("内存 \(formattedValue(memory, suffix: "%"))")
                        }
                    }
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.9))
                }
            } compactLeading: {
                Image(systemName: "speedometer")
                    .foregroundStyle(context.state.alert ? Color.red : Color.green)
            } compactTrailing: {
                Text(formattedValue(context.state.fps, suffix: ""))
                    .font(.system(.caption, design: .monospaced).bold())
                    .foregroundStyle(.white)
            } minimal: {
                Image(systemName: "speedometer")
                    .foregroundStyle(context.state.alert ? Color.red : Color.green)
            }
        }
    }

    private func formattedValue(_ value: Double?, suffix: String) -> String {
        guard let value else { return "" }
        return String(format: "%.0f", value) + suffix
    }
}

// MARK: - 锁屏视图

private struct LockScreenView: View {
    let state: MonitorActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                Text("实时性能")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(state.alert ? "⚠ 温度告警" : "监测中")
                    .font(.headline)
                    .foregroundStyle(state.alert ? Color.red : Color.primary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                if let fps = state.fps {
                    Text(valueText(fps, " fps"))
                        .font(.system(.title3, design: .monospaced).bold())
                }
                HStack(spacing: 8) {
                    if let temp = state.deviceTempC {
                        Text("温度 \(valueText(temp, "℃"))")
                    }
                    if let memory = state.memoryRatio {
                        Text("内存 \(valueText(memory, "%"))")
                    }
                }
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(.secondary)
            }
        }
        .padding()
    }

    private func valueText(_ value: Double?, _ suffix: String) -> String {
        guard let value else { return "" }
        return String(format: "%.0f", value) + suffix
    }
}
