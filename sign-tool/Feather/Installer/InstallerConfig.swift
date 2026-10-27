import Foundation

/// 精简安装器的全部可调项集中在这里。
///
/// 苹果这两轮风控（-80045 / -80046）改的是「登录时声称的身份」和「取 Xcode token 的方式」，
/// 所以 anisette 服务器地址、widget key、client info 都做成可替换常量——
/// 哪天苹果再改规则，改这几个值即可，不用动流程代码。
enum InstallerConfig {
	// MARK: - 要安装的 IPA（GitHub Release）
	static let repoOwner = "sitong-zhang"
	static let repoName = "DevicePerfMonitor"
	/// 资产筛选：取第一个以它结尾的资源。写死 .ipa，别的格式不会被拿去签名
	static let ipaSuffix = ".ipa"
	
	// MARK: - Apple 登录
	/// anisette 服务器。需要一个 Mac 身份的（现在 Android 身份的会被拒）。
	/// 自建可选 Dadoum/Provision，出现 tidal -80046 就换一个服务器再试
	static let anisetteServer = URL(string: "https://mac-ani.jkcoxson.com")!
	/// 苹果 widget key。报错 -22411 / bad request 时优先换这个
	static let widgetKey = "d39ba9916b7251055a07d0b6b8c9f50d6cbbdcb78e33a2c3d3c0f8e2b0b5c5c6"
	/// 开发者服务 client id（AltSign 沿用多年的常量）
	static let clientId = "XABBG36SBA"
	/// Xcode 开发者服务 token 的 app id key
	static let xcodeAppIdKey = "XABBG36SBA"
	static let protocolVersion = "QH65B2"
	
	// MARK: - 签名产物
	/// 生成的 p12 是否加密私钥。Zsign 读不进来时改成 false（用空密码 + 明文 keyBag）
	static let encryptsPrivateKeyInP12 = true
	/// 描述文件的 App ID 前缀；nil 时直接用 IPA 原始 bundle id
	static let appIdPrefix: String? = nil
}

enum InstallerError: LocalizedError {
	case noIPAInRelease
	case downloadFailed(String)
	case anisetteFailed(String)
	case authFailed(String)
	case twoFactorRequired
	case noTeam
	case noDeviceUDID
	case csrFailed(String)
	case certificateFailed(String)
	case profileFailed(String)
	case signingFailed(String)
	case appNotFound
	
	var errorDescription: String? {
		switch self {
		case .noIPAInRelease: return "仓库最新 Release 里没有 .ipa 安装包。"
		case .downloadFailed(let s): return "下载失败：\(s)"
		case .anisetteFailed(let s): return "无法获取 anisette：\(s)"
		case .authFailed(let s): return "登录失败：\(s)"
		case .twoFactorRequired: return "需要验证码"
		case .noTeam: return "这个 Apple ID 没有可用的开发者团队。"
		case .noDeviceUDID: return "取不到本机设备标识。"
		case .csrFailed(let s): return "证书请求生成失败：\(s)"
		case .certificateFailed(let s): return "证书申请失败：\(s)"
		case .profileFailed(let s): return "描述文件下载失败：\(s)"
		case .signingFailed(let s): return "签名失败：\(s)"
		case .appNotFound: return "找不到待安装的 App。"
		}
	}
}
