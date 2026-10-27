import Foundation
import OSLog

struct AppleSession {
	let dsid: String
	let myacinfo: String
	let appToken: Data?
	let anisette: AnisetteData
}

/// Apple ID 登录：账号密码 →（可能要验证码）→ 建立会话 → 取 Xcode 开发者 token。
///
/// 关于 -80045 / -80046：
/// - 登录本身用 anisette 来源对应的身份（我们用 Mac 服务器，所以声称 Mac）
/// - 取 token 那一步必须声称 Mac，并且带 X-Xcode-Version / X-Apple-App-Info / svct=Xcode
/// 后面对开发者服务的请求继续声称 Mac（换身份会报 session expired）
final class AppleAuth {
	static let shared = AppleAuth()
	
	private let session: URLSession
	private var anisette: AnisetteData?
	private var signInSessionId: String?
	private var scnt: String?
	
	private init() {
		let config = URLSessionConfiguration.default
		config.httpCookieAcceptPolicy = .always
		config.httpShouldSetCookies = true
		config.httpCookieStorage = .shared
		self.session = URLSession(configuration: config)
	}
	
	// MARK: - 密码登录
	func signIn(email: String, password: String) async throws -> AppleSession {
		let anisette = try await AnisetteProvider.fetch()
		self.anisette = anisette
		
		let url = URL(string: "https://idmsa.apple.com/appleauth/auth/signin")!
		var request = URLRequest(url: url)
		request.httpMethod = "POST"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		AnisetteProvider.applyHeaders(&request, anisette: anisette, platform: .mac)
		request.httpBody = try JSONSerialization.data(withJSONObject: [
			"accountName": email,
			"password": password,
			"rememberMe": true,
			"trustTokens": [],
			"protocols": ["s2k", "s2k_fo"]
		])
		
		let (data, response) = try await session.data(for: request)
		let http = response as? HTTPURLResponse
		signInSessionId = http?.value(forHTTPHeaderField: "X-Apple-ID-Session-Id")
		scnt = http?.value(forHTTPHeaderField: "scnt")
		
		switch http?.statusCode ?? -1 {
		case 200:
			return try await establishSession(from: data, anisette: anisette)
		case 409:
			// 苹果把验证码推到了用户设备上，UI 再问一次
			throw InstallerError.twoFactorRequired
		default:
			throw InstallerError.authFailed(Self.serviceMessage(data) ?? "HTTP \(http?.statusCode ?? -1)")
		}
	}
	
	// MARK: - 两步验证
	func submitSecurityCode(_ code: String) async throws -> AppleSession {
		guard let anisette, let sid = signInSessionId, let scnt else {
			throw InstallerError.authFailed("登录会话已失效，请重新开始")
		}
		
		// 先试信任设备，失败再退到短信
		let trusted = try? await putSecurityCode(
			url: URL(string: "https://idmsa.apple.com/appleauth/auth/verify/trusteddevice/securitycode")!,
			body: ["securityCode": ["code": code]],
			sid: sid, scnt: scnt, anisette: anisette
		)
		
		if trusted == nil {
			let _ = try await putSecurityCode(
				url: URL(string: "https://idmsa.apple.com/appleauth/auth/verify/phone/securitycode")!,
				body: [
					"securityCode": ["code": code],
					"phoneNumber": ["id": 1],
					"mode": "sms"
				],
				sid: sid, scnt: scnt, anisette: anisette
			)
		}
		
		try await trust(anisette: anisette, sid: sid, scnt: scnt)
		return try await establishSession(from: Data(), anisette: anisette)
	}
	
	@discardableResult
	private func putSecurityCode(url: URL, body: [String: Any], sid: String, scnt: String, anisette: AnisetteData) async throws -> Bool {
		var request = URLRequest(url: url)
		request.httpMethod = "PUT"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		request.httpBody = try JSONSerialization.data(withJSONObject: body)
		AnisetteProvider.applyHeaders(&request, anisette: anisette, platform: .mac)
		request.setValue(sid, forHTTPHeaderField: "X-Apple-ID-Session-Id")
		request.setValue(scnt, forHTTPHeaderField: "scnt")
		
		let (data, response) = try await session.data(for: request)
		let status = (response as? HTTPURLResponse)?.statusCode ?? -1
		if status != 200 {
			throw InstallerError.authFailed(Self.serviceMessage(data) ?? "验证码错误或未通过（HTTP \(status)）")
		}
		return true
	}
	
	private func trust(anisette: AnisetteData, sid: String, scnt: String) async throws {
		var request = URLRequest(url: URL(string: "https://idmsa.apple.com/appleauth/auth/2sv/trust")!)
		request.httpMethod = "GET"
		AnisetteProvider.applyHeaders(&request, anisette: anisette, platform: .mac)
		request.setValue(sid, forHTTPHeaderField: "X-Apple-ID-Session-Id")
		request.setValue(scnt, forHTTPHeaderField: "scnt")
		_ = try? await session.data(for: request)
	}
	
	// MARK: - 会话 & token
	private func establishSession(from data: Data, anisette: AnisetteData) async throws -> AppleSession {
		let dsid = Self.findDSID(in: data) ?? Self.dsidFromCookies() ?? ""
		let myacinfo = Self.cookie(named: "myacinfo") ?? Self.cookie(named: "aasp") ?? ""
		
		guard !myacinfo.isEmpty || !dsid.isEmpty else {
			throw InstallerError.authFailed("没拿到会话凭据（myacinfo/dsid）")
		}
		
		let token = try? await fetchAppToken(anisette: anisette, sid: signInSessionId, scnt: scnt)
		if token == nil {
			Logger.misc.error("取 Xcode token 失败——多半撞上了苹果新的 -80046，换 anisette 服务器再试")
		}
		
		return AppleSession(dsid: dsid, myacinfo: myacinfo, appToken: token, anisette: anisette)
	}
	
	/// 这一步就是现在最容易坏的地方：必须以 Mac 身份请求 com.apple.gs.xcode.auth
	private func fetchAppToken(anisette: AnisetteData, sid: String?, scnt: String?) async throws -> Data {
		var components = URLComponents(string: "https://idmsa.apple.com/appleauth/auth/apptokens")!
		components.queryItems = [
			URLQueryItem(name: "appIdKey", value: InstallerConfig.xcodeAppIdKey)
		]
		
		var request = URLRequest(url: components.url!)
		request.httpMethod = "POST"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		// token 请求：固定用 Mac 身份 + akd 的 UA + Xcode 相关头
		AnisetteProvider.applyHeaders(&request, anisette: anisette, platform: .mac)
		request.setValue(ClaimedPlatform.tokenClientInfo, forHTTPHeaderField: "X-Mme-Client-Info")
		request.setValue(ClaimedPlatform.tokenUserAgent, forHTTPHeaderField: "User-Agent")
		request.setValue("12.0", forHTTPHeaderField: "X-Xcode-Version")
		request.setValue("Xcode", forHTTPHeaderField: "X-Apple-App-Info")
		if let sid { request.setValue(sid, forHTTPHeaderField: "X-Apple-ID-Session-Id") }
		if let scnt { request.setValue(scnt, forHTTPHeaderField: "scnt") }
		request.httpBody = try JSONSerialization.data(withJSONObject: ["appIdKeys": [InstallerConfig.xcodeAppIdKey]])
		
		let (data, response) = try await session.data(for: request)
		let status = (response as? HTTPURLResponse)?.statusCode ?? -1
		
		if status == 200 || data.count > 16 { return data }
		throw InstallerError.authFailed(Self.serviceMessage(data) ?? "apptokens HTTP \(status)")
	}
	
	// MARK: - 小工具
	private static func serviceMessage(_ data: Data) -> String? {
		guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
		if let errs = json["service_errors"] as? [[String: Any]], let first = errs.first {
			let code = first["code"] as? String ?? "?"
			let title = first["title"] as? String ?? first["message"] as? String ?? ""
			return "\(code) \(title)"
		}
		if let msg = json["message"] as? String { return msg }
		return nil
	}
	
	private static func findDSID(in data: Data) -> String? {
		guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
		func search(_ node: [String: Any]) -> String? {
			for (key, value) in node {
				if key.lowercased() == "dsid", let s = value as? String { return s }
				if let nested = value as? [String: Any], let hit = search(nested) { return hit }
			}
			return nil
		}
		return search(json)
	}
	
	private static func dsidFromCookies() -> String? { cookie(named: "dqsid") }
	
	private static func cookie(named name: String) -> String? {
		HTTPCookieStorage.shared.cookies(for: URL(string: "https://idmsa.apple.com")!)?
			.first(where: { $0.name == name })?.value
	}
}
