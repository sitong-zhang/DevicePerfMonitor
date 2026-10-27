import SwiftUI

/// 应用内悬浮 HUD（iOS 不允许全局悬浮窗，此为 App 内替代方案；常驻展示可用小组件）。
struct HUDOverlay: View {
    @Binding var show: Bool
    @EnvironmentObject private var vm: MetricsViewModel
    @State private var offset = CGSize.zero

    var body: some View {
        if let s = vm.latest {
            VStack(alignment: .leading, spacing: 4) {
                HUDRow(label: "分", value: "\(s.score)")
                HUDRow(label: "CPU", value: String(format: "%.0f%%", s.cpu))
                HUDRow(label: "内存", value: String(format: "%.0f", s.memoryUsedMB))
                HUDRow(label: "帧", value: String(format: "%.0f", s.fps))
                Button { withAnimation { show = false } } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
            }
            .padding(10)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
            .offset(offset)
            .gesture(DragGesture().onChanged { offset = $0.translation })
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            .padding(16)
        }
    }
}

struct HUDRow: View {
    let label: String
    let value: String
    var body: some View {
        HStack(spacing: 6) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.caption.weight(.semibold))
        }
    }
}
