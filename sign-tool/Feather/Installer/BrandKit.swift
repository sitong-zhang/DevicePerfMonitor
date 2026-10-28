//
//  BrandKit.swift
//  Feather
//
//  Brand 专属：签名回执上报 + 签名完成后锁定/自动关闭。
//
//  产品约束（用户拍板）：Brand 只做一件事——把观序签名装到本机；
//  签完即锁定并自动关闭，同时把签名回执交给管理端。
//

import Foundation
import UIKit
import OSLog

// MARK: - 锁定状态

/// 签名完成后落盘锁定标记；下次启动显示锁定页（仅保留续签入口）。
enum BrandLock {
	static let key = "Brand.signLocked"
	static let lastSignedKey = "Brand.lastSignedAt"

	static var isLocked: Bool {
		UserDefaults.standard.bool(forKey: key)
	}

	static func lock() {
		UserDefaults.standard.set(true, forKey: key)
		UserDefaults.standard.set(ISO8601DateFormatter().string(from: Date()), forKey: lastSignedKey)
	}

	/// 续签：临时解锁，签名完成后会再次锁定。
	static func unlockForResign() {
		UserDefaults.standard.set(false, forKey: key)
	}
}

// MARK: - 签名回执

/// 签名完成后把回执交给管理端。
///
/// 通道与安卓端反馈同设计：GitHub Issue（labels=brand-sign），管理端免 token 读。
/// token 构建期注入 Info.plist 的 `BrandReportToken`（CI secret
/// BRAND_REPORT_TOKEN，最小权限仅本仓库 issues:write）；未注入时降级为
/// 本地存档（「文件」App 可见），绝不阻塞关闭流程。
///
/// 隐私红线：回执里只有机型/系统/版本/时间/结果这类非敏感遥测，
/// 绝不包含 Apple ID、密码、证书、设备 UDID。
enum BrandReporter {
	struct Report: Codable {
		let brand: String
		let guanxu: String
		let device: String
		let os: String
		let signedAt: String
		let result: String
	}

	/// 签名成功后调用。异步尽力上报（最多等 8 秒），随后回调继续收尾。
	static func sendSignedReport(completion: (() -> Void)? = nil) {
		let report = Report(
			brand: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?",
			guanxu: "观序（GuanXu）",
			device: _machineModel(),
			os: UIDevice.current.systemVersion,
			signedAt: ISO8601DateFormatter().string(from: Date()),
			result: "success"
		)

		guard let json = try? JSONEncoder().encode(report),
			let body = String(data: json, encoding: .utf8)
		else {
			completion?()
			return
		}

		guard let token = Bundle.main.object(forInfoDictionaryKey: "BrandReportToken") as? String,
			!token.isEmpty
		else {
			// 降级：本地存档，用户可用「文件」App 取出
			_saveLocally(body)
			Logger.misc.info("Brand 回执未配置 token，已本地存档")
			completion?()
			return
		}

		_postIssue(token: token, body: body) { _ in completion?() }
	}

	// MARK: 私有

	private static func _machineModel() -> String {
		var systemInfo = utsname()
		uname(&systemInfo)
		let mirror = Mirror(reflecting: systemInfo.machine)
		return mirror.children.reduce(into: "") { result, element in
			guard let value = element.value as? Int8, value != 0 else { return }
			result.append(Character(UnicodeScalar(UInt8(value))))
		}
	}

	private static func _postIssue(token: String, body: String, completion: @escaping (Bool) -> Void) {
		var request = URLRequest(url: URL(string: "https://api.github.com/repos/sitong-zhang/DevicePerfMonitor/issues")!)
		request.httpMethod = "POST"
		request.timeoutInterval = 8
		request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
		request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
		request.setValue("Brand", forHTTPHeaderField: "User-Agent")
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")

		let payload: [String: Any] = [
			"title": "Brand 签名回执 · \(body.suffixJSONValue(of: "signedAt"))",
			"body": "```json\n\(body)\n```",
			"labels": ["brand-sign"],
		]
		request.httpBody = try? JSONSerialization.data(withJSONObject: payload)

		URLSession.shared.dataTask(with: request) { _, response, _ in
			let ok = (response as? HTTPURLResponse)?.statusCode == 201
			DispatchQueue.main.async { completion(ok) }
		}.resume()
	}

	private static func _saveLocally(_ json: String) {
		let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
			.appendingPathComponent("brand-reports", isDirectory: true)
		try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
		let stamp = Int(Date().timeIntervalSince1970)
		try? json.write(to: dir.appendingPathComponent("report-\(stamp).json"), atomically: true, encoding: .utf8)
	}
}

private extension String {
	func suffixJSONValue(of key: String) -> String {
		guard let data = data(using: .utf8),
			let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
			let value = obj[key] as? String
		else { return "unknown" }
		return value
	}
}

// MARK: - 签名完成收尾

/// 签名成功后的统一收尾：上报回执 → 落锁 → 延时自动关闭。
enum BrandFinalizer {
	static func finalize(onReadyToClose: @escaping (String) -> Void) {
		BrandLock.lock()
		BrandReporter.sendSignedReport {
			// 无论上报成败都关闭（上报已尽力）
			onReadyToClose("签名完成，Brand 即将自动关闭")
		}
	}
}
