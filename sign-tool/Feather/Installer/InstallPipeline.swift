import Foundation
import CoreData
import UIKit
import Zip
import OSLog
import IDeviceSwift

enum PipelineStage: Equatable {
	case idle
	case downloading(Double)
	case waitingForUser
	case authorizing
	case awaitingCode
	case preparingCertificate
	case preparingApp
	case signing
	case installing(Double)
	case completed
	case failed(String)
	
	/// 给最终用户看的文案。不涉及任何技术细节
	var title: String {
		switch self {
		case .idle: return "准备中"
		case .downloading: return "正在下载安装包…"
		case .waitingForUser: return "请填写 Apple ID"
		case .authorizing: return "正在登录 Apple ID…"
		case .awaitingCode: return "请输入苹果发到设备上的验证码"
		case .preparingCertificate: return "正在获取安装授权…"
		case .preparingApp: return "正在准备安装包…"
		case .signing: return "正在准备应用…"
		case .installing: return "正在安装…"
		case .completed: return "安装完成"
		case .failed(let message): return "没能完成：\(message)"
		}
	}
	
	var progress: Double {
		switch self {
		case .downloading(let p): return p * 0.45
		case .waitingForUser: return 0.5
		case .authorizing: return 0.6
		case .awaitingCode: return 0.62
		case .preparingCertificate: return 0.72
		case .preparingApp: return 0.82
		case .signing: return 0.88
		case .installing(let p): return 0.9 + p * 0.1
		case .completed: return 1
		default: return 0
		}
	}
	
	var isTerminal: Bool {
		switch self {
		case .completed, .failed: return true
		default: return false
		}
	}
}

/// 一条龙：下载安装包 ‖ 登录 → 申请授权 → 签名 → 装到本机
@MainActor
final class InstallPipeline: ObservableObject {
	@Published var appleID: String = ""
	@Published var password: String = ""
	@Published var securityCode: String = ""
	@Published private(set) var stage: PipelineStage = .idle
	@Published private(set) var isBusy: Bool = false
	
	private let downloader = ReleaseDownloader()
	private var ipaURL: URL?
	private var codeContinuation: CheckedContinuation<String, Error>?
	private var installer: ServerInstaller?  // 必须持有，否则 deinit 直接关掉本地服务
	
	var canStart: Bool {
		!isBusy && ipaURL != nil && !appleID.isEmpty && !password.isEmpty
	}
	
	/// 苹果把验证码推送到用户设备上了，等着他输进来
	var isAwaitingCode: Bool {
		if case .awaitingCode = stage { return true }
		return false
	}
	
	// MARK: - 启动即开始下载（和用户填表单并行）
	func beginDownloadIfNeeded() {
		guard ipaURL == nil, case .idle = stage else { return }
		
		Task {
			stage = .downloading(0)
			do {
				let asset = try await downloader.fetchLatestIPA()
				let destination = FileManager.default.temporaryDirectory
					.appendingPathComponent("pending.ipa")
				let url = try await downloader.download(asset, to: destination) { [weak self] progress in
					Task { @MainActor in self?.stage = .downloading(progress) }
				}
				ipaURL = url
				if !canStart { stage = .waitingForUser }
			} catch {
				stage = .failed(error.localizedDescription)
			}
		}
	}
	
	func submitSecurityCode() {
		guard !securityCode.isEmpty, let continuation = codeContinuation else { return }
		codeContinuation = nil
		let code = securityCode
		securityCode = ""
		continuation.resume(returning: code)
	}
	
	// MARK: - 主流程
	func run() async {
		guard canStart else { return }
		isBusy = true
		defer { isBusy = false }
		
		do {
			let ipa = try await waitForIPA()
			stage = .authorizing
			let session = try await login()
			stage = .preparingCertificate
			let certificate = try await makeCertificate(session: session, ipa: ipa)
			stage = .preparingApp
			let app = try await importApp(ipa)
			stage = .signing
			let signed = try await sign(app: app, using: certificate)
			stage = .installing(0)
			try await install(signed)
			stage = .completed
		} catch {
			Logger.misc.error("安装流程失败：\(error.localizedDescription)")
			stage = .failed(error.localizedDescription)
		}
	}
	
	private func waitForIPA() async throws -> URL {
		guard let ipaURL else { throw InstallerError.downloadFailed("安装包还没准备好") }
		return ipaURL
	}
	
	// MARK: - 登录
	private func login() async throws -> AppleSession {
		do {
			return try await AppleAuth.shared.signIn(email: appleID, password: password)
		} catch InstallerError.twoFactorRequired {
			stage = .awaitingCode
			let code = try await withCheckedThrowingContinuation { continuation in
				codeContinuation = continuation
			}
			stage = .authorizing
			return try await AppleAuth.shared.submitSecurityCode(code)
		}
	}
	
	// MARK: - 用 Apple ID 换取本机可用的证书
	private func makeCertificate(session: AppleSession, ipa: URL) async throws -> CertificatePair {
		let services = DeveloperServices(account: session)
		
		let teams = try await services.listTeams()
		guard let team = teams.first else { throw InstallerError.noTeam }
		
		guard let udid = DeviceInfo.localUDID() else { throw InstallerError.noDeviceUDID }
		let registered = try await services.listDeviceUDIDs(teamId: team.id)
		if !registered.contains(udid) {
			try await services.registerDevice(udid: udid, teamId: team.id)
		}
		
		let bundleId = try bundleIdentifier(of: ipa)
		let appIdIdentifier = [InstallerConfig.appIdPrefix, bundleId].compactMap { $0 }.joined(separator: ".")
		let appIdId = try await services.addAppId(appIdIdentifier, name: bundleId, teamId: team.id)
		
		let csrRequest = try CertificateFactory.makeCSR(commonName: DeviceInfo.name, emailAddress: appleID)
		defer { csrRequest.release() }
		
		let certificateData = try await services.submitCSR(csrRequest.pem, machineName: DeviceInfo.name, teamId: team.id)
		let p12Data = try CertificateFactory.makeP12(certificate: certificateData, privateKey: csrRequest.privateKey)
		let provisionData = try await services.downloadTeamProvisioningProfile(appIdId: appIdId, teamId: team.id)
		
		let p12URL = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).p12")
		let provisionURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).mobileprovision")
		try p12Data.write(to: p12URL)
		try provisionData.write(to: provisionURL)
		
		try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
			FR.handleCertificateFiles(
				p12URL: p12URL,
				provisionURL: provisionURL,
				p12Password: "",
				certificateName: "Apple ID 自动 \(DateFormatter.localizedString(from: Date(), dateStyle: .short, timeStyle: .short))",
				isDefault: true
			) { error in
				if let error { continuation.resume(throwing: error) } else { continuation.resume() }
			}
		}
		
		guard let pair = Storage.shared.getCertificate(for: 0) else {
			throw InstallerError.certificateFailed("证书没写进本地库")
		}
		return pair
	}
	
	// MARK: - IPA 入库
	private func importApp(_ ipa: URL) async throws -> AppInfoPresentable {
		let handler = AppFileHandler(file: ipa)
		try await handler.copy()
		try await handler.extract()
		try await handler.move()
		try await handler.addToDatabase()
		try? await handler.clean()
		
		let request = NSFetchRequest<Imported>(entityName: "Imported")
		request.sortDescriptors = [NSSortDescriptor(key: "date", ascending: false)]
		request.fetchLimit = 1
		request.includesPendingChanges = true
		guard let imported = try Storage.shared.context.fetch(request).first else {
			throw InstallerError.appNotFound
		}
		return AnyApp(base: imported)
	}
	
	// MARK: - 签名
	private func sign(app: AppInfoPresentable, using certificate: CertificatePair) async throws -> AppInfoPresentable {
		try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
			FR.signPackageFile(
				app,
				using: OptionsManager.shared.options,
				icon: nil,
				certificate: certificate
			) { error in
				if let error { continuation.resume(throwing: error) } else { continuation.resume() }
			}
		}
		
		let request = NSFetchRequest<Signed>(entityName: "Signed")
		request.sortDescriptors = [NSSortDescriptor(key: "date", ascending: false)]
		request.fetchLimit = 1
		request.includesPendingChanges = true
		guard let signed = try Storage.shared.context.fetch(request).first else {
			throw InstallerError.signingFailed("找不到签名后的 App")
		}
		return AnyApp(base: signed)
	}
	
	// MARK: - 安装到本机
	private func install(_ app: AppInfoPresentable) async throws {
		let viewModel = InstallerStatusViewModel(isIdevice: false)
		let installer = try ServerInstaller(app: app, viewModel: viewModel)
		self.installer = installer
		
		let handler = ArchiveHandler(app: app, viewModel: viewModel)
		try await handler.move()
		let package = try await handler.archive()
		
		installer.packageUrl = package
		viewModel.status = .ready
		if let url = URL(string: installer.iTunesLink) {
			UIApplication.shared.open(url)
		}
		
		await pollInstallProgress(bundleID: app.identifier ?? "", viewModel: viewModel)
	}
	
	private func pollInstallProgress(bundleID: String, viewModel: InstallerStatusViewModel) async {
		var hasStarted = false
		let deadline = Date().addingTimeInterval(180)  // 兜底，免得一直转圈
		
		while !Task.isCancelled, Date() < deadline {
			let raw = UIApplication.installProgress(for: bundleID) ?? 0
			if raw > 0 { hasStarted = true }
			let normalized = hasStarted ? min(1, max(0, (raw - 0.6) / 0.3)) : 0
			
			stage = .installing(normalized)
			
			if hasStarted && raw == 0 { stage = .completed; break }
			try? await Task.sleep(nanoseconds: 5_000_000)
		}
	}
	
	// MARK: - 工具
	private func bundleIdentifier(of ipa: URL) throws -> String {
		let work = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
		try FileManager.default.createDirectoryIfNeeded(at: work)
		defer { try? FileManager.default.removeItem(at: work) }
		
		Zip.addCustomFileExtension("ipa")
		try Zip.unzipFile(ipa, destination: work, overwrite: true, password: nil)
		let payload = work.appendingPathComponent("Payload")
		let apps = try FileManager.default.contentsOfDirectory(at: payload, includingPropertiesForKeys: nil)
		guard let appURL = apps.first(where: { $0.pathExtension == "app" }) else {
			throw InstallerError.appNotFound
		}
		guard
			let data = try? Data(contentsOf: appURL.appendingPathComponent("Info.plist")),
			let info = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any],
			let identifier = info["CFBundleIdentifier"] as? String
		else { throw InstallerError.appNotFound }
		return identifier
	}
}
