import SwiftUI

/// 极简安装界面：账号密码 + 一个主按钮 + 进度条。
/// 打开 App 的瞬间后台已经在下载安装包了
struct InstallView: View {
	@StateObject private var pipeline = InstallPipeline()
	
	var body: some View {
		VStack(spacing: 20) {
			Spacer(minLength: 24)
			
			VStack(spacing: 4) {
				Text("设备性能监控")
					.font(.title2.weight(.semibold))
				Text("安装到本机")
					.font(.subheadline)
					.foregroundStyle(.secondary)
			}
			.padding(.bottom, 12)
			
			VStack(spacing: 12) {
				TextField("Apple ID", text: $pipeline.appleID)
					.textInputAutocapitalization(.never)
					.autocorrectionDisabled()
					.keyboardType(.emailAddress)
					.textContentType(.username)
					.textFieldStyle(.roundedBorder)
					.disabled(pipeline.isBusy)
				
				SecureField("密码", text: $pipeline.password)
					.textContentType(.password)
					.textFieldStyle(.roundedBorder)
					.disabled(pipeline.isBusy)
				
				if pipeline.isAwaitingCode {
					SecureField("验证码", text: $pipeline.securityCode)
						.keyboardType(.numberPad)
						.textFieldStyle(.roundedBorder)
						.transition(.opacity)
				}
			}
			.padding(.horizontal)
			
			VStack(spacing: 8) {
				ProgressView(value: pipeline.stage.progress)
					.tint(.accentColor)
				Text(pipeline.stage.title)
					.font(.footnote)
					.foregroundStyle(.secondary)
					.multilineTextAlignment(.center)
			}
			.padding(.horizontal)
			.padding(.top, 4)
			
			Spacer(minLength: 12)
			
			if pipeline.isAwaitingCode {
				primaryButton(title: "继续") {
					pipeline.submitSecurityCode()
				}
				.disabled(pipeline.securityCode.isEmpty)
			} else {
				primaryButton(title: pipeline.isBusy ? "处理中…" : "开始安装") {
					Task { await pipeline.run() }
				}
				.disabled(!pipeline.canStart)
			}
			
			Text("会使用你的 Apple ID 向苹果申请这台设备的安装授权。密码只在本机使用，不会上传。")
				.font(.caption2)
				.foregroundStyle(.secondary)
				.multilineTextAlignment(.center)
				.padding(.horizontal)
		}
		.padding()
		.animation(.easeInOut(duration: 0.2), value: pipeline.isAwaitingCode)
		.onAppear { pipeline.beginDownloadIfNeeded() }
	}
	
	@ViewBuilder
	private func primaryButton(title: String, action: @escaping () -> Void) -> some View {
		Button(action: action) {
			Text(title)
				.font(.body.weight(.medium))
				.frame(maxWidth: .infinity)
				.padding(.vertical, 14)
		}
		.buttonStyle(.borderedProminent)
		.controlSize(.large)
		.padding(.horizontal)
	}
}
