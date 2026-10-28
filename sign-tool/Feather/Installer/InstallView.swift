import SwiftUI

/// Brand 主界面（原极简安装器）：账号密码 + 一个主按钮 + 进度条。
/// 打开 App 的瞬间后台已经在下载安装包了。
///
/// 产品约束：Brand 只做「把观序签名装到本机」这一件事——
/// 签名完成后自动上报回执、落锁并关闭；再次打开显示锁定页（可续签）。
struct InstallView: View {
	@StateObject private var pipeline = InstallPipeline()

	/// 续签时临时绕过锁定页（签名完成后会再次锁定并关闭）
	@State private var _unlockedForResign = false
	/// 签名完成后的关闭覆盖层文案；非 nil 时倒计时后退出
	@State private var _closingMessage: String?

	var body: some View {
		Group {
			if BrandLock.isLocked && !_unlockedForResign {
				BrandLockedView(onResign: { _unlockedForResign = true })
			} else if let message = _closingMessage {
				BrandClosingView(message: message)
			} else {
				_signingForm
			}
		}
		.animation(.easeInOut(duration: 0.2), value: BrandLock.isLocked)
		.animation(.easeInOut(duration: 0.2), value: _closingMessage == nil)
	}

	// MARK: - 签名表单

	private var _signingForm: some View {
		VStack(spacing: 20) {
			Spacer(minLength: 24)

			VStack(spacing: 4) {
				Text("Brand")
					.font(.title2.weight(.semibold))
				Text("观序 · 签名安装到本机")
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

			Text("会使用你的 Apple ID 向苹果申请这台设备的安装授权。密码只在本机使用，不会上传。签名完成后 Brand 会自动关闭。")
				.font(.caption2)
				.foregroundStyle(.secondary)
				.multilineTextAlignment(.center)
				.padding(.horizontal)
		}
		.padding()
		.animation(.easeInOut(duration: 0.2), value: pipeline.isAwaitingCode)
		.onAppear { pipeline.beginDownloadIfNeeded() }
		.onChange(of: pipeline.stage) { newStage in
			if case .completed = newStage, _closingMessage == nil {
				BrandFinalizer.finalize { message in
					_closingMessage = message
					_scheduleExit()
				}
			}
		}
	}

	private func _scheduleExit() {
		DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
			exit(0)
		}
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

// MARK: - 锁定页

/// 签名任务已完成后的锁定页：只保留「重新签名（续签）」一个入口。
private struct BrandLockedView: View {
	let onResign: () -> Void

	var body: some View {
		VStack(spacing: 20) {
			Spacer()

			Image(systemName: "checkmark.seal.fill")
				.font(.system(size: 56))
				.foregroundStyle(.green)

			VStack(spacing: 6) {
				Text("Brand")
					.font(.title2.weight(.semibold))
				Text("本次签名任务已完成")
					.font(.subheadline)
					.foregroundStyle(.secondary)
				if let last = UserDefaults.standard.string(forKey: "Brand.lastSignedAt") {
					Text("最近签名：\(last)")
						.font(.caption2)
						.foregroundStyle(.tertiary)
				}
			}

			Text("观序已安装到本机，Brand 用完即休眠。免费证书 7 天到期后，点下方按钮重新签名即可续期。")
				.font(.caption)
				.foregroundStyle(.secondary)
				.multilineTextAlignment(.center)
				.padding(.horizontal, 32)

			Spacer()

			Button(action: onResign) {
				Text("重新签名（续签）")
					.font(.body.weight(.medium))
					.frame(maxWidth: .infinity)
					.padding(.vertical, 14)
			}
			.buttonStyle(.borderedProminent)
			.controlSize(.large)
			.padding(.horizontal)
			.padding(.bottom, 24)
		}
	}
}

// MARK: - 关闭覆盖层

/// 签名完成后的短暂过渡页：提示回执与即将关闭，3 秒后自动退出。
private struct BrandClosingView: View {
	let message: String

	var body: some View {
		VStack(spacing: 16) {
			Spacer()

			Image(systemName: "checkmark.circle.fill")
				.font(.system(size: 64))
				.foregroundStyle(.green)

			Text("观序 已安装到本机")
				.font(.title3.weight(.semibold))

			Text(message)
				.font(.subheadline)
				.foregroundStyle(.secondary)

			ProgressView()
				.padding(.top, 8)

			Spacer()
		}
		.padding()
	}
}
