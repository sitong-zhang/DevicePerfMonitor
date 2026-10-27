import Foundation

/// 声称的平台身份。
///
/// 苹果现在的规则（2026-10 起）：
/// - 登录时声称的身份必须和 anisette 来源一致，否则 -80045
/// - 取 com.apple.gs.xcode.auth 的 token 时必须声称 Mac，否则 -80046（安卓身份拿不到 Xcode token）
/// 我们默认连的是 Mac anisette 服务器（mac-ani.jkcoxson.com），所以全程声称 Mac
enum ClaimedPlatform: String {
	case mac
	case android
	
	/// X-Mme-Client-Info
	var clientInfo: String {
		switch self {
		case .mac: return "<Mac15,7> <macOS;15.0;24A335> <com.apple.AuthKit/1 (com.apple.dt.Xcode/3594.2)>"
		case .android: return "<Android Phone> <Linux;Android 12;Scale/2.00> <com.apple.itunes/1 (com.apple.mediaplayerd/1.0)>"
		}
	}
	
	var userAgent: String {
		switch self {
		case .mac: return "Xcode/3594.2 CFNetwork/1568.200.51 Darwin/24.1.0"
		case .android: return "Apple-iTunes/12.12.9 (Android/12; Scale/2.00)"
		}
	}
	
	/// token 请求专用的身份：必须是 Mac + akd 的 UA，苹果才会发 Xcode token
	static let tokenClientInfo = "<Mac15,7> <macOS;15.0;24A335> <com.apple.AuthKit/1 (com.apple.akd/1.0)>"
	static let tokenUserAgent = "akd/1.0 CFNetwork/808.1.4"
}

struct AnisetteData {
	var machineId: String = ""        // X-Apple-I-MD
	var oneTimePassword: String = ""  // X-Apple-I-MD-M
	var routingInfo: String = ""      // X-Apple-I-MD-RINFO
	var clientInfo: String = ""       // X-Mme-Client-Info
}

enum AnisetteProvider {
	private static let isoFormatter: ISO8601DateFormatter = {
		let f = ISO8601DateFormatter()
		f.formatOptions = [.withInternetDateTime]
		return f
	}()
	
	static var nowString: String { isoFormatter.string(from: Date()) }
	
	/// 从 anisette 服务器取那一组头部。
	/// 接口约定：GET {server}/get_headers 返回 {"X-Apple-I-MD": "...", "X-Apple-I-MD-M": "...", ...}
	static func fetch(platform: ClaimedPlatform = .mac) async throws -> AnisetteData {
		var components = URLComponents(url: InstallerConfig.anisetteServer.appendingPathComponent("get_headers"), resolvingAgainstBaseURL: false)!
		components.queryItems = [URLQueryItem(name: "app", value: "com.apple.gs.xcode.auth")]
		
		var request = URLRequest(url: components.url!)
		request.httpMethod = "GET"
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.timeoutInterval = 30
		
		let (data, response) = try await URLSession.shared.data(for: request)
		guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
			throw InstallerError.anisetteFailed("HTTP \((response as? HTTPURLResponse)?.statusCode ?? -1)")
		}
		
		guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
			throw InstallerError.anisetteFailed("返回不是 JSON")
		}
		
		// 各家 anisette 服务的字段名大小写/写法不统一，逐个兜底
		func pick(_ keys: [String]) -> String {
			for k in keys {
				if let v = json[k] as? String, !v.isEmpty { return v }
			}
			return ""
		}
		
		let md = pick(["X-Apple-I-MD", "machineId", "machine_id", "X-Apple-I-MD-LU"])
		let otp = pick(["X-Apple-I-MD-M", "oneTimePassword", "otp", "X-Apple-I-OTP"])
		let routing = pick(["X-Apple-I-MD-RINFO", "routingInfo", "routing_info"])
		let clientInfo = pick(["X-Mme-Client-Info", "clientInfo"])
		
		guard !md.isEmpty, !otp.isEmpty else {
			throw InstallerError.anisetteFailed("返回缺 machineId / otp")
		}
		
		return AnisetteData(
			machineId: md,
			oneTimePassword: otp,
			routingInfo: routing.isEmpty ? "17106176" : routing,
			clientInfo: clientInfo.isEmpty ? platform.clientInfo : clientInfo
		)
	}
	
	/// 把 anisette + 身份塞进请求头
	static func applyHeaders(_ request: inout URLRequest, anisette: AnisetteData?, platform: ClaimedPlatform) {
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.setValue(AnisetteProvider.nowString, forHTTPHeaderField: "X-Apple-I-Client-Time")
		request.setValue(InstallerConfig.widgetKey, forHTTPHeaderField: "X-Apple-Widget-Key")
		if let anisette {
			request.setValue(anisette.machineId, forHTTPHeaderField: "X-Apple-I-MD")
			request.setValue(anisette.oneTimePassword, forHTTPHeaderField: "X-Apple-I-MD-M")
			request.setValue(anisette.routingInfo, forHTTPHeaderField: "X-Apple-I-MD-RINFO")
			request.setValue(anisette.clientInfo.isEmpty ? platform.clientInfo : anisette.clientInfo,
							 forHTTPHeaderField: "X-Mme-Client-Info")
		} else {
			request.setValue(platform.clientInfo, forHTTPHeaderField: "X-Mme-Client-Info")
		}
		request.setValue(platform.userAgent, forHTTPHeaderField: "User-Agent")
	}
}
