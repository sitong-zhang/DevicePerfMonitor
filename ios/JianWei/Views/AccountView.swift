import SwiftUI

/// 「我的」：展示当前账号、注册时间，提供退出登录。
struct AccountView: View {
	@EnvironmentObject private var account: AccountManager

	var body: some View {
		NavigationView {
			List {
				if let u = account.current {
					Section("当前账号") {
						LabeledContent("用户名", value: u.username)
						LabeledContent("注册时间", value: format(u.createdAt))
					}
					Section("数据说明") {
						Text("账号与性能数据均仅保存在本机，不上传任何服务器。")
							.font(.footnote)
							.foregroundStyle(.secondary)
					}
					Section {
						Button(role: .destructive) {
							account.logout()
						} label: {
							Text("退出登录")
								.frame(maxWidth: .infinity, alignment: .center)
						}
					}
				}
			}
			.navigationTitle("我的")
		}
	}

	private func format(_ d: Date) -> String {
		let f = DateFormatter()
		f.dateStyle = .medium
		f.timeStyle = .short
		f.locale = Locale(identifier: "zh_CN")
		return f.string(from: d)
	}
}
