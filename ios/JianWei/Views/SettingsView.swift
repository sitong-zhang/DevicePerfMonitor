import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var vm: MetricsViewModel

    private let privacyText = "见微 全程在设备本地采集与存储性能数据，不注册、不登录、不上传任何服务器。唯一联网行为仅用于检查更新与拉取公告，读取的是 GitHub 公开内容。"

    var body: some View {
        NavigationView {
            List {
                Section("关于见微") {
                    LabeledContent("版本", value: "1.0.0")
                    LabeledContent("平台", value: "iOS")
                    NavigationLink("隐私政策") { ScrollView { Text(privacyText).padding() } }
                }

                Section("苹果版 · 账户说明") {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("本应用通过你的 Apple ID 签名安装到 iPhone。")
                        Text("使用免费 Apple ID 申请的开发证书有效期为 7 天，到期需在签名工具中重新登录 Apple ID 续签后再次安装。")
                        Text("Apple ID 仅用于向苹果申请临时证书，不会上传给任何第三方。")
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }

                Section("更新") {
                    if vm.updateAvailable {
                        Link("发现新版本 \(vm.updateTag ?? "")", destination: URL(string: "https://github.com/sitong-zhang/DevicePerfMonitor/releases")!)
                    } else {
                        Text("已是最新").foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("见微 · 设置")
        }
        .alert("公告", isPresented: $vm.showAnnouncement) {
            Button("知道了", role: .cancel) {}
        } message: {
            Text(vm.announcement ?? "")
        }
    }
}
