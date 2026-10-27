import Foundation

struct ReleaseInfo: Decodable {
    let tagName: String
    let body: String
    enum CodingKeys: String, CodingKey { case tagName = "tag_name", body }
}

struct Announcement: Decodable {
    let content: String
    let version: Int
    enum CodingKeys: String, CodingKey { case content, version }
}

/// 仅读取 GitHub 公开内容：检查更新、拉取公告。不向任何服务器上报数据。
final class UpdateService {
    static let shared = UpdateService()
    static let currentVersion = "1.0.0"

    func latestRelease() async throws -> ReleaseInfo {
        let url = URL(string: "https://api.github.com/repos/sitong-zhang/DevicePerfMonitor/releases/latest")!
        let (data, _) = try await URLSession.shared.data(from: url)
        return try JSONDecoder().decode(ReleaseInfo.self, from: data)
    }

    func announcement() async throws -> Announcement {
        let url = URL(string: "https://raw.githubusercontent.com/sitong-zhang/DevicePerfMonitor/main/android/app/src/main/assets/announcement.json")!
        let (data, _) = try await URLSession.shared.data(from: url)
        return try JSONDecoder().decode(Announcement.self, from: data)
    }
}
