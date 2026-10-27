import Foundation
import OSLog

struct DeveloperTeam {
	let id: String
	let name: String
}

/// 和苹果开发者服务打交道：取团队、注册设备、注册 App ID、上传 CSR、下载描述文件。
///
/// 端点沿用 AltSign 那套：developerservices2.apple.com/services/QH65B2/{...}.action
/// clientId 固定 XABBG36SBA
final class DeveloperServices {
	private let base = URL(string: "https://developerservices2.apple.com/services/\(InstallerConfig.protocolVersion)")!
	private let session: URLSession
	private let account: AppleSession
	
	init(account: AppleSession) {
		self.account = account
		let config = URLSessionConfiguration.default
		config.httpCookieAcceptPolicy = .always
		config.httpCookieStorage = .shared
		self.session = URLSession(configuration: config)
	}
	
	// MARK: - 团队
	func listTeams() async throws -> [DeveloperTeam] {
		let payload = try await post("listTeams.action", form: [:])
		guard let list = payload["teams"] as? [[String: Any]] else { throw InstallerError.noTeam }
		return list.compactMap { team in
			guard let id = team["teamId"] as? String else { return nil }
			return DeveloperTeam(id: id, name: (team["name"] as? String) ?? (team["teamName"] as? String) ?? id)
		}
	}
	
	// MARK: - 设备
	func listDeviceUDIDs(teamId: String) async throws -> [String] {
		let payload = try await post("ios/listDevices.action", form: ["teamId": teamId, "includeRemovedDevices": "false"])
		guard let list = payload["devices"] as? [[String: Any]] else { return [] }
		return list.compactMap { ($0["deviceNumber"] as? String)?.uppercased() }
	}
	
	func registerDevice(udid: String, teamId: String) async throws {
		_ = try await post("ios/addDevice.action", form: [
			"teamId": teamId,
			"deviceNumber": udid,
			"name": DeviceInfo.name
		])
	}
	
	// MARK: - App ID
	/// - returns: 新建 App ID 的 appIdId
	@discardableResult
	func addAppId(_ identifier: String, name: String, teamId: String) async throws -> String {
		let payload = try await post("ios/addAppId.action", form: [
			"appIdName": name,
			"appIdentifierString": identifier,
			"teamId": teamId
		])
		
		if let appId = payload["appId"] as? [String: Any], let id = appId["appIdId"] as? String { return id }
		if let id = payload["appIdId"] as? String { return id }
		
		// 已经存在时苹果会带 resultCode，去列表里捞出来即可
		let existing = try await listAppIds(teamId: teamId)
		if let hit = existing.first(where: { ($0["identifier"] as? String)?.lowercased() == identifier.lowercased() }),
		   let id = hit["appIdId"] as? String {
			return id
		}
		throw InstallerError.profileFailed("注册 App ID 失败：\(payload["resultCode"] ?? "未知错误")")
	}
	
	func listAppIds(teamId: String) async throws -> [[String: Any]] {
		let payload = try await post("ios/listAppIds.action", form: ["teamId": teamId])
		// 免费账号每天 App ID 配额有限，满了要从 payload 里读出来提示用户
		if let errorString = payload["userString"] as? String, !errorString.isEmpty {
			Logger.misc.info("listAppIds: \(errorString)")
		}
		return payload["appIds"] as? [[String: Any]] ?? []
	}
	
	// MARK: - 证书
	/// 上传 CSR 拿证书。
	/// - returns: 证书数据（.cer）
	func submitCSR(_ csrPEM: String, machineName: String, teamId: String) async throws -> Data {
		let payload = try await post("ios/submitDevelopmentCSR.action", form: [
			"teamId": teamId,
			"csrContent": csrPEM,
			"machineName": machineName,
			"machineId": "",
			"appIdId": ""
		])
		
		// 有些账号直接返回证书本体
		if let base64 = payload["certContent"] as? String, let data = Data(base64Encoded: base64) {
			return data
		}
		if let appended = payload["certRequest"] as? [String: Any],
		   let certId = appended["serialNumber"] ?? appended["certRequestId"] {
			return try await downloadCertificate(requestId: String(describing: certId), teamId: teamId)
		}
		throw InstallerError.certificateFailed("苹果没有返回证书：\(payload["resultCode"] ?? "未知错误")")
	}
	
	func downloadCertificate(requestId: String, teamId: String) async throws -> Data {
		let components = URLComponents(url: base.appendingPathComponent("ios/downloadDevelopmentCert.action"), resolvingAgainstBaseURL: false)!
		return try await requestWithFallback(components, form: ["teamId": teamId, "certRequestId": requestId], expectingBinary: true)
	}
	
	// MARK: - 描述文件
	func downloadTeamProvisioningProfile(appIdId: String, teamId: String) async throws -> Data {
		let components = URLComponents(url: base.appendingPathComponent("ios/downloadTeamProvisioningProfile.action"), resolvingAgainstBaseURL: false)!
		return try await requestWithFallback(components, form: ["teamId": teamId, "appIdId": appIdId], expectingBinary: true)
	}
	
	// MARK: - 底层
	@discardableResult
	private func post(_ path: String, form: [String: String]) async throws -> [String: Any] {
		let components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
		let data = try await requestWithFallback(components, form: form, expectingBinary: false)
		return Self.parse(data)
	}
	
	private func requestWithFallback(_ components: URLComponents, form: [String: String], expectingBinary: Bool) async throws -> Data {
		var params = form
		params["clientId"] = InstallerConfig.clientId
		let bodyString = params
			.map { "\($0.key)=\(Self.formEncode($0.value))" }
			.joined(separator: "&")
		
		var request = URLRequest(url: components.url!)
		request.httpMethod = "POST"
		request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.httpBody = bodyString.data(using: .utf8)
		
		// 开发者服务继续声称 Mac（和取 token 时保持一致，换身份会 session expired）
		AnisetteProvider.applyHeaders(&request, anisette: account.anisette, platform: .mac)
		request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
		if let token = account.appToken {
			request.setValue(token.base64EncodedString(), forHTTPHeaderField: "X-Apple-Token")
		}
		
		let (data, response) = try await session.data(for: request)
		let status = (response as? HTTPURLResponse)?.statusCode ?? -1
		guard (200..<300).contains(status) else {
			throw InstallerError.certificateFailed("开发者服务返回 HTTP \(status)")
		}
		return data
	}
	
	/// 苹果这些接口有的返 JSON、有的返 plist，两种都兜一遍
	private static func parse(_ data: Data) -> [String: Any] {
		if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] { return json }
		if let plist = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any] {
			return plist
		}
		if let text = String(data: data, encoding: .utf8), !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			return ["raw": text]
		}
		return [:]
	}
	
	private static func formEncode(_ value: String) -> String {
		value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? value
	}
}
