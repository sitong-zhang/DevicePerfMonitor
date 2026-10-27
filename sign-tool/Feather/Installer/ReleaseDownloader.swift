import Foundation
import OSLog

struct ReleaseAsset: Decodable {
	let name: String
	let size: Int64
	let browserDownloadUrl: URL
	
	enum CodingKeys: String, CodingKey {
		case name, size
		case browserDownloadUrl = "browser_download_url"
	}
}

struct ReleaseInfo: Decodable {
	let tagName: String
	let assets: [ReleaseAsset]
	
	enum CodingKeys: String, CodingKey {
		case tagName = "tag_name"
		case assets
	}
}

/// 从 GitHub Release 拉最新的 IPA 安装包。
/// 只认 .ipa 后缀，别的格式不会进签名链路
final class ReleaseDownloader {
	// MARK: - 查最新 Release
	func fetchLatestIPA() async throws -> ReleaseAsset {
		let url = URL(string: "https://api.github.com/repos/\(InstallerConfig.repoOwner)/\(InstallerConfig.repoName)/releases/latest")!
		var request = URLRequest(url: url)
		request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
		
		let (data, response) = try await URLSession.shared.data(for: request)
		guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
			throw InstallerError.downloadFailed("GitHub API 返回非 2xx")
		}
		
		let release = try JSONDecoder().decode(ReleaseInfo.self, from: data)
		guard let asset = release.assets.first(where: { $0.name.hasSuffix(InstallerConfig.ipaSuffix) }) else {
			throw InstallerError.noIPAInRelease
		}
		Logger.misc.info("Release \(release.tagName) → 资产 \(asset.name)")
		return asset
	}
	
	// MARK: - 下载（带实时进度）
	func download(_ asset: ReleaseAsset, to destination: URL, progress: @escaping (Double) -> Void) async throws -> URL {
		try FileManager.default.createDirectoryIfNeeded(at: destination.deletingLastPathComponent())
		try? FileManager.default.removeItem(at: destination)
		
		let delegate = DownloadObserver(progress: progress)
		let session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
		defer { session.invalidateAndCancel() }
		
		return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
			delegate.didFinish = { result in
				switch result {
				case .success(let temporaryURL):
					do {
						try? FileManager.default.removeItem(at: destination)
						try FileManager.default.moveItem(at: temporaryURL, to: destination)
						continuation.resume(returning: destination)
					} catch {
						continuation.resume(throwing: error)
					}
				case .failure(let error):
					continuation.resume(throwing: InstallerError.downloadFailed(error.localizedDescription))
				}
			}
			session.downloadTask(with: asset.browserDownloadUrl).resume()
		}
	}
}

// MARK: - 下载进度/完成观察者
private final class DownloadObserver: NSObject, URLSessionDownloadDelegate {
	let progress: (Double) -> Void
	var didFinish: ((Result<URL, Error>) -> Void)?
	private var hasResumed = false
	
	init(progress: @escaping (Double) -> Void) {
		self.progress = progress
	}
	
	func urlSession(
		_ session: URLSession,
		downloadTask: URLSessionDownloadTask,
		didWriteData bytesWritten: Int64,
		totalBytesWritten: Int64,
		totalBytesExpectedToWrite: Int64
	) {
		guard totalBytesExpectedToWrite > 0 else { return }
		progress(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))
	}
	
	func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
		// 拷一份，临时文件出了这个回调就没了
		let copy = FileManager.default.temporaryDirectory
			.appendingPathComponent(UUID().uuidString)
			.appendingPathExtension("ipa")
		do {
			try FileManager.default.copyItem(at: location, to: copy)
			finish(.success(copy))
		} catch {
			finish(.failure(error))
		}
	}
	
	func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
		if let error { finish(.failure(error)) }
	}
	
	private func finish(_ result: Result<URL, Error>) {
		guard !hasResumed else { return }
		hasResumed = true
		didFinish?(result)
	}
}
