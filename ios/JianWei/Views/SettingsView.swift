import SwiftUI

struct SettingsView: View {
	@EnvironmentObject private var vm: MetricsViewModel
	@EnvironmentObject private var account: AccountManager

	private let privacyText = "观序 全程在设备本地采集与存储性能数据，不联网上报任何数据。唯一联网行为仅用于检查更新与拉取公告，读取的是 GitHub 公开内容。账号仅保存在本机，用于登录态持久化。"

	var body: some View {
		NavigationView {
			List {
				Section("关于观序") {
					LabeledContent("版本", value: UpdateService.currentVersion)
					LabeledContent("平台", value: "iOS")
					NavigationLink("隐私政策") { ScrollView { Text(privacyText).padding() } }
				}

				Section("苹果版 · 安装说明") {
					VStack(alignment: .leading, spacing: 8) {
						Text("观序 苹果版通过你的 Apple ID 签名后安装到 iPhone。")
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
			.navigationTitle("观序 · 设置")
		}
		.alert("公告", isPresented: $vm.showAnnouncement) {
			Button("知道了", role: .cancel) {}
		} message: {
			Text(vm.announcement ?? "")
		}
	}
}
