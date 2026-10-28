//
//  DataKit.swift
//  可移植数据上传模块 —— 拖进任意 iOS 工程即可用。
//
//  能力：把「当前应用自身」的全部数据
//        （Documents / Library / Caches / tmp / UserDefaults 全量 / 任意格式文件）
//        打包成真 ZIP 压缩包，上传到唯一固定通道：管理端
//        （GitHub Issue，labels=data-upload）。
//
//  设计约定（用户拍板）：
//  - 上传「哪个应用」不固定：谁调用谁上传自己（应用永远能读自己的沙盒）；
//  - 上传「到哪里」固定：管理端（唯一通道）。
//  - 全量：只要在被收集范围内，不管是什么数据都打包；超大文件仅登记清单；
//          单 issue 正文上限 65536 字符，超量自动分片上传（N 条 issue）。
//  - 安全：证书 / 私钥 / 密码等凭证文件仅登记清单、内容不传（避免公开仓库泄露）；
//          其余所有数据全量上传。无 token / 上传失败：完整 ZIP 存本地「文件」App。
//
//  token：Info.plist 的 BrandReportToken（CI 注入，最小权限仅本仓库 Issues 读写）。
//

import Foundation
import UIKit
import OSLog
import zlib

public enum DataKit {
	public static let uploadLabel = "data-upload"

	/// 把当前应用自身所有数据打包成 ZIP 并上传管理端。
	/// - appName: 应用展示名（默认取 CFBundleName）
	/// - completion: 是否成功（失败 / 无 token 自动本地完整存档 ZIP）
	public static func uploadEverything(appName: String? = nil, completion: ((Bool) -> Void)? = nil) {
		DispatchQueue.global(qos: .utility).async {
			let name = appName
				?? (Bundle.main.object(forInfoDictionaryKey: "CFBundleName") as? String ?? "未知应用")
			let (zip, skipped) = _collectAll(appName: name)
			guard !zip.isEmpty else {
				DispatchQueue.main.async { completion?(false) }
				return
			}
			let b64 = zip.base64EncodedString()
			if b64.count > 46 * 1024 {
				_uploadInChunks(b64, appName: name, skipped: skipped, completion: completion)
			} else {
				_uploadSingle(b64, appName: name, skipped: skipped, completion: completion)
			}
		}
	}

	// MARK: - 收集 + 打包

	private static func _collectAll(appName: String) -> (zip: Data, skipped: [[String: Any]]) {
		var files: [(path: String, data: Data)] = []
		var skipped: [[String: Any]] = []
		let fm = FileManager.default
		let bases = [
			fm.urls(for: .documentDirectory, in: .userDomainMask),
			fm.urls(for: .libraryDirectory, in: .userDomainMask),
			fm.urls(for: .cachesDirectory, in: .userDomainMask),
			[URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)],
		].compactMap { $0.first }

		for base in bases {
			guard let enumerator = fm.enumerator(
				at: base,
				includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]
			) else { continue }
			for case let url as URL in enumerator {
				guard let values = try? url.resourceValues(forKeys: [URLResourceKey.fileSizeKey, URLResourceKey.isRegularFileKey]),
					values.isRegularFile == true,
					let size = values.fileSize
				else { continue }
				let rel = url.path.replacingOccurrences(of: base.path + "/", with: "")
				if _isSecret(url) {
					skipped.append(["path": rel, "size": size, "reason": "secret"])
					continue
				}
				if size > 256 * 1024 {
					skipped.append(["path": rel, "size": size, "reason": "oversize"])
					continue
				}
				guard let data = try? Data(contentsOf: url) else { continue }
				files.append((rel, data))
			}
		}

		// UserDefaults 全量（覆盖用户输入 / 配置）
		if let ud = UserDefaults.standard.dictionaryRepresentation() as [String: Any]?,
			let plist = try? PropertyListSerialization.data(fromPropertyList: ud, format: .xml, options: 0) {
			files.append(("UserDefaults.plist", plist))
		}

		return (makeZip(files), skipped)
	}

	/// 凭证类文件：仅登记清单、内容不传。
	private static func _isSecret(_ url: URL) -> Bool {
		let ext = url.pathExtension.lowercased()
		if ["p12", "pem", "key", "der", "mobileprovision", "provisionprofile"].contains(ext) {
			return true
		}
		let name = url.lastPathComponent.lowercased()
		let words = ["cert", "credential", "password", "passwd", "private", "secret", "token", "appleid"]
		return words.contains { name.contains($0) }
	}

	// MARK: - ZIP 打包（标准 ZIP，deflate + CRC32）

	/// 把 files 打包成浏览器 / 系统可直接解压的标准 ZIP。
	static func makeZip(_ files: [(path: String, data: Data)]) -> Data {
		var out = Data()
		var central = Data()
		var offset = 0

		for (path, data) in files {
			let nameData = Data(path.utf8)
			let comp = data.deflateData()
			let crc = data.withUnsafeBytes { raw in
				crc32(0, raw.bindMemory(to: Bytef.self).baseAddress, uInt(data.count))
			}

			var header = Data()
			header.append(0x04034b50.leData)        // local file signature
			header.append(UInt16(20).leData)         // version needed
			header.append(UInt16(0x0800).leData)     // flags: UTF-8 filename
			header.append(UInt16(8).leData)          // method: deflate
			header.append(UInt16(0).leData)           // mod time
			header.append(UInt16(0).leData)           // mod date
			header.append(UInt32(crc).leData)
			header.append(UInt32(comp.count).leData)
			header.append(UInt32(data.count).leData)
			header.append(UInt16(nameData.count).leData)
			header.append(UInt16(0).leData)           // extra length
			out.append(header)
			out.append(nameData)
			out.append(comp)

			var cd = Data()
			cd.append(0x02014b50.leData)             // central dir signature
			cd.append(UInt16(20).leData)             // version made by
			cd.append(UInt16(20).leData)             // version needed
			cd.append(UInt16(0x0800).leData)
			cd.append(UInt16(8).leData)
			cd.append(UInt16(0).leData)
			cd.append(UInt16(0).leData)
			cd.append(UInt32(crc).leData)
			cd.append(UInt32(comp.count).leData)
			cd.append(UInt32(data.count).leData)
			cd.append(UInt16(nameData.count).leData)
			cd.append(UInt16(0).leData)              // extra
			cd.append(UInt16(0).leData)              // comment
			cd.append(UInt16(0).leData)              // disk number
			cd.append(UInt16(0).leData)              // internal attrs
			cd.append(UInt32(0).leData)              // external attrs
			cd.append(UInt32(offset).leData)         // local header offset
			cd.append(nameData)
			central.append(cd)

			offset = out.count
		}

		let cdOffset = out.count
		out.append(central)
		var eocd = Data()
		eocd.append(0x06054b50.leData)
		eocd.append(UInt16(0).leData)
		eocd.append(UInt16(0).leData)
		eocd.append(UInt16(files.count).leData)
		eocd.append(UInt16(files.count).leData)
		eocd.append(UInt32(central.count).leData)
		eocd.append(UInt32(cdOffset).leData)
		eocd.append(UInt16(0).leData)
		out.append(eocd)
		return out
	}

	// MARK: - 上传

	private static func _postIssue(token: String, title: String, body: String, completion: @escaping (Bool) -> Void) {
		var request = URLRequest(url: URL(string: "https://api.github.com/repos/sitong-zhang/DevicePerfMonitor/issues")!)
		request.httpMethod = "POST"
		request.timeoutInterval = 15
		request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
		request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
		request.setValue("DataKit", forHTTPHeaderField: "User-Agent")
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")

		let summary = """
		```json
		{"kind":"zip","fileCount":0,"note":"见下方 ZIP 压缩包"}
		```
		"""
		let payload: [String: Any] = [
			"title": title,
			"body": body + "\n\n" + summary,
			"labels": [uploadLabel],
		]
		request.httpBody = try? JSONSerialization.data(withJSONObject: payload)

		URLSession.shared.dataTask(with: request) { _, response, _ in
			completion((response as? HTTPURLResponse)?.statusCode == 201)
		}.resume()
	}

	private static func _uploadSingle(_ b64: String, appName: String, skipped: [[String: Any]], completion: ((Bool) -> Void)?) {
		guard let token = Bundle.main.object(forInfoDictionaryKey: "BrandReportToken") as? String, !token.isEmpty else {
			_saveLocally(b64, appName: appName)
			DispatchQueue.main.async { completion?(false) }
			return
		}
		let body = "[ZIP_B64]\n\(b64)\n[/ZIP_B64]\n\n（凭证 / 超大文件共 \(skipped.count) 个仅登记未传内容）"
		_postIssue(token: token, title: "[数据上传] \(appName) · \(_stamp())", body: body) { ok in
			if !ok { _saveLocally(b64, appName: appName) }
			DispatchQueue.main.async { completion?(ok) }
		}
	}

	private static func _uploadInChunks(_ b64: String, appName: String, skipped: [[String: Any]], completion: ((Bool) -> Void)?) {
		let size = 45 * 1024
		let indices = stride(from: 0, to: b64.count, by: size).map { $0 }
		let total = indices.count
		guard let token = Bundle.main.object(forInfoDictionaryKey: "BrandReportToken") as? String, !token.isEmpty else {
			_saveLocally(b64, appName: appName)
			DispatchQueue.main.async { completion?(false) }
			return
		}
		let group = DispatchGroup()
		let lock = NSLock()
		var okAll = true
		for (i, start) in indices.enumerated() {
			let end = min(start + size, b64.count)
			let chunk = String(b64[b64.index(b64.startIndex, offsetBy: start)..<b64.index(b64.startIndex, offsetBy: end)])
			group.enter()
			let body = "[ZIP_B64_CHUNK \(i + 1)/\(total)]\n\(chunk)\n[/ZIP_B64_CHUNK]"
			_postIssue(token: token, title: "[数据上传] \(appName) · \(_stamp()) · \(i + 1)/\(total)", body: body) { ok in
				if !ok { lock.lock(); okAll = false; lock.unlock() }
				group.leave()
			}
		}
		group.notify(queue: .main) { completion?(okAll) }
	}

	private static func _saveLocally(_ b64: String, appName: String) {
		guard let zip = Data(base64Encoded: b64) else { return }
		let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
			.appendingPathComponent("data-uploads", isDirectory: true)
		try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
		try? zip.write(to: dir.appendingPathComponent("上传-\(appName)-\(_stamp()).zip"))
	}

	private static func _stamp() -> String {
		let f = ISO8601DateFormatter()
		f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
		return f.string(from: Date())
	}
}

// MARK: - 小工具

extension FixedWidthInteger {
	var leData: Data {
		withUnsafeBytes(of: self.littleEndian) { Data($0) }
	}
}

extension Data {
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
