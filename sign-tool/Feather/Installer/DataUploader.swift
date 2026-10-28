//
//  DataUploader.swift
//  Feather
//
//  通用数据上传接口（对外暴露，框架外可直调）。
//
//  设计约定（用户拍板）：
//  - 「上传哪个应用」不固定：任何软件接入只需构造一个 UploadTarget；
//  - 「上传到哪里」固定：管理端（GitHub Issue，labels=data-upload）。
//  - 打包：目标数据逐文件 zlib deflate 压缩 → JSON 包 → issue body
//    （GitHub 单 issue 正文上限 65536 字符，超出部分仅登记清单并本地完整存档）。
//  - 隐私红线同 BrandReporter：不含 Apple ID/密码/证书/UDID。
//

import Foundation
import UIKit
import OSLog
import zlib

// MARK: - 上传目标（不固定：接入新软件就在这里注册一个 target）

/// 一个待上传应用的数据描述。接入新软件 = 新建一个 UploadTarget。
struct UploadTarget {
	/// 展示名（管理端直接显示）
	let name: String
	/// 应用标识
	let bundleID: String
	/// 参与打包的沙盒目录名（相对 Documents；目录不存在自动跳过）
	let relativeDirs: [String]

	/// 观序（默认接入方）
	static let guanxu = UploadTarget(
		name: "观序",
		bundleID: "com.guanxu.GuanXu",
		relativeDirs: ["archives", "brand-reports", "logs"]
	)
}

// MARK: - 通用上传接口

enum DataUploader {
	/// 单文件全量入包上限（原始字节）。超出只登记清单不传内容。
	private static let maxInlineBytes = 256 * 1024
	/// issue body 预算（base64 累计逼近即截断，防 422）
	private static let bodyBudget = 48 * 1024

	/// 对外接口：把目标应用的整个数据打包上传到管理端。
	/// - 应用不固定（传 target），通道固定（管理端 data-upload）。
	/// - 无 token / 上传失败时降级为本地完整存档（「文件」App 可见），绝不阻塞调用方。
	static func uploadAllData(of target: UploadTarget, completion: ((Bool) -> Void)? = nil) {
		DispatchQueue.global(qos: .utility).async {
			let package = _collect(of: target)
			guard let json = try? JSONSerialization.data(withJSONObject: package, options: [.sortedKeys]),
				let body = String(data: json, encoding: .utf8), !body.isEmpty
			else {
				DispatchQueue.main.async { completion?(false) }
				return
			}

			guard let token = Bundle.main.object(forInfoDictionaryKey: "BrandReportToken") as? String,
				!token.isEmpty
			else {
				_saveLocally(body, target: target)
				Logger.misc.info("DataUploader 未配置 token，数据包已本地存档")
				DispatchQueue.main.async { completion?(false) }
				return
			}

			_postPackage(token: token, target: target, body: body) { ok in
				if !ok { _saveLocally(body, target: target) }
				DispatchQueue.main.async { completion?(ok) }
			}
		}
	}

	// MARK: 收集 + 打包

	private static func _collect(of target: UploadTarget) -> [String: Any] {
		let fm = FileManager.default
		let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]

		var files: [[String: Any]] = []
		var skipped: [[String: Any]] = []
		var budget = bodyBudget

		for dir in target.relativeDirs {
			let dirURL = docs.appendingPathComponent(dir, isDirectory: true)
			guard let enumerator = fm.enumerator(
				at: dirURL,
				includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]
			) else { continue }

			for case let url as URL in enumerator {
				let values = try? url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
				guard values?.isRegularFile == true, let size = values?.fileSize else { continue }

				let relPath = "\(dir)/\(url.lastPathComponent)"
				if size > maxInlineBytes || budget < 2048 {
					skipped.append([
						"path": relPath,
						"size": size,
						"reason": size > maxInlineBytes ? "oversize" : "budget",
					])
					continue
				}
				guard let raw = try? Data(contentsOf: url), !raw.isEmpty else { continue }
				let deflated = raw.deflateData()
				let b64 = deflated.base64EncodedString()
				if b64.count > budget {
					skipped.append(["path": relPath, "size": size, "reason": "budget"])
					continue
				}
				budget -= b64.count
				files.append([
					"path": relPath,
					"size": size,
					"compressedSize": deflated.count,
					"encoding": "zlib+base64",
					"data": b64,
				])
			}
		}

		return [
			"schema": "data-upload/1",
			"app": target.name,
			"bundleID": target.bundleID,
			"exportedAt": ISO8601DateFormatter().string(from: Date()),
			"device": _machineModel(),
			"brandVersion": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?",
			"fileCount": files.count,
			"files": files,
			"skipped": skipped,
		]
	}

	private static func _machineModel() -> String {
		var systemInfo = utsname()
		uname(&systemInfo)
		let mirror = Mirror(reflecting: systemInfo.machine)
		return mirror.children.reduce(into: "") { result, element in
			guard let value = element.value as? Int8, value != 0 else { return }
			result.append(Character(UnicodeScalar(UInt8(value))))
		}
	}

	// MARK: 上传

	private static func _postPackage(token: String, target: UploadTarget, body: String, completion: @escaping (Bool) -> Void) {
		var request = URLRequest(url: URL(string: "https://api.github.com/repos/sitong-zhang/DevicePerfMonitor/issues")!)
		request.httpMethod = "POST"
		request.timeoutInterval = 15
		request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
		request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
		request.setValue("Brand", forHTTPHeaderField: "User-Agent")
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")

		let payload: [String: Any] = [
			"title": "[数据上传] \(target.name) · \(ISO8601DateFormatter().string(from: Date()))",
			"body": "```json\n\(body)\n```",
			"labels": ["data-upload"],
		]
		request.httpBody = try? JSONSerialization.data(withJSONObject: payload)

		URLSession.shared.dataTask(with: request) { _, response, _ in
			completion((response as? HTTPURLResponse)?.statusCode == 201)
		}.resume()
	}

	private static func _saveLocally(_ json: String, target: UploadTarget) {
		let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
			.appendingPathComponent("data-uploads", isDirectory: true)
		try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
		let stamp = Int(Date().timeIntervalSince1970)
		try? json.write(
			to: dir.appendingPathComponent("上传-\(target.name)-\(stamp).json"),
			atomically: true,
			encoding: .utf8
		)
	}
}

// MARK: - zlib 压缩

private extension Data {
	/// zlib deflate（失败回退原数据）。
	func deflateData() -> Data {
		guard !isEmpty else { return self }
		var destLen = uLong(count + count / 1000 + 128)
		var dest = Data(count: Int(destLen))
		let status: Int32 = withUnsafeMutablePointer(to: &destLen) { destLenPtr in
			dest.withUnsafeMutableBytes { (dstRaw: UnsafeMutableRawBufferPointer) in
				withUnsafeBytes { (srcRaw: UnsafeRawBufferPointer) in
					guard let dstBase = dstRaw.bindMemory(to: Bytef.self).baseAddress,
						let srcBase = srcRaw.bindMemory(to: Bytef.self).baseAddress
					else { return Z_BUF_ERROR }
					return compress2(dstBase, destLenPtr, srcBase, uLong(count), Z_BEST_SPEED)
				}
			}
		}
		guard status == Z_OK else { return self }
		return dest.prefix(Int(destLen))
	}
}
