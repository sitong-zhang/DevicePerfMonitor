import SwiftUI

/// 登录 / 注册页。未登录时作为 App 入口门禁。
struct LoginView: View {
	@ObservedObject var accountManager: AccountManager

	@State private var isRegister = false
	@State private var username = ""
	@State private var password = ""
	@State private var message = ""

	var body: some View {
		NavigationView {
			VStack(spacing: 22) {
				VStack(spacing: 6) {
					Text("观序")
						.font(.system(size: 46, weight: .bold, design: .rounded))
					Text("设备性能监测 · 请登录后使用")
						.font(.subheadline)
						.foregroundStyle(.secondary)
				}
				.padding(.bottom, 8)

				TextField("用户名", text: $username)
					.textContentType(.username)
					.padding(14)
					.background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))

				SecureField("密码", text: $password)
					.textContentType(isRegister ? .newPassword : .password)
					.padding(14)
					.background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))

				if !message.isEmpty {
					Text(message)
						.font(.footnote)
						.foregroundStyle(.red)
						.frame(maxWidth: .infinity, alignment: .leading)
				}

				Button {
					if isRegister {
						message = accountManager.register(username: username, password: password) ?? ""
					} else {
						if accountManager.login(username: username, password: password) {
							message = ""
						} else {
							message = "用户名或密码错误"
						}
					}
				} label: {
					Text(isRegister ? "注册并登录" : "登录")
						.frame(maxWidth: .infinity)
						.padding(14)
						.background(.blue)
						.foregroundStyle(.white)
						.clipShape(RoundedRectangle(cornerRadius: 12))
				}

				Button {
					withAnimation { isRegister.toggle() }
					message = ""
				} label: {
					Text(isRegister ? "已有账号？去登录" : "没有账号？注册一个")
						.font(.footnote)
				}

				Spacer()
			}
			.padding(28)
			.toolbar(.hidden, for: .navigationBar)
		}
	}
}
