import Foundation
import CryptoKit

/// 本地账号：注册 / 登录 / 登出 / 会话持久化。
/// 全部数据只存在本机，不联网、不上传任何服务器。
struct Account: Codable, Identifiable {
	let id: UUID
	let username: String
	let createdAt: Date
}

final class AccountManager: ObservableObject {
	static let shared = AccountManager()

	@Published var current: Account?

	private let accountsFile: URL
	private let sessionFile: URL

	private init() {
		let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
		accountsFile = dir.appendingPathComponent("gx_accounts.json")
		sessionFile = dir.appendingPathComponent("gx_session.json")
		current = loadSession()
	}

	var isLoggedIn: Bool { current != nil }

	// MARK: - 注册
	func register(username: String, password: String) -> Result<Account, String> {
		let u = username.trimmingCharacters(in: .whitespacesAndNewlines)
		guard u.count >= 3 else { return .failure("用户名至少 3 个字符") }
		guard password.count >= 6 else { return .failure("密码至少 6 位") }

		var list = loadAccounts()
		guard !list.contains(where: { $0.username == u }) else {
			return .failure("该用户名已被注册")
		}

		let salt = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
		let hash = Self.hash(password: password, salt: salt)
		list.append(StoredAccount(username: u, salt: salt, hash: hash))
		saveAccounts(list)

		let acc = Account(id: UUID(), username: u, createdAt: Date())
		setSession(acc)
		return .success(acc)
	}

	// MARK: - 登录
	func login(username: String, password: String) -> Bool {
		let u = username.trimmingCharacters(in: .whitespacesAndNewlines)
		guard let rec = loadAccounts().first(where: { $0.username == u }) else { return false }
		guard Self.hash(password: password, salt: rec.salt) == rec.hash else { return false }
		setSession(Account(id: UUID(), username: u, createdAt: Date()))
		return true
	}

	func logout() {
		try? FileManager.default.removeItem(at: sessionFile)
		current = nil
	}

	// MARK: - 持久化
	private func setSession(_ a: Account) {
		current = a
		if let d = try? JSONEncoder().encode(a) {
			try? d.write(to: sessionFile, options: .atomic)
		}
	}

	private func loadSession() -> Account? {
		guard let d = try? Data(contentsOf: sessionFile) else { return nil }
		return try? JSONDecoder().decode(Account.self, from: d)
	}

	private func loadAccounts() -> [StoredAccount] {
		guard let d = try? Data(contentsOf: accountsFile) else { return [] }
		return (try? JSONDecoder().decode([StoredAccount].self, from: d)) ?? []
	}

	private func saveAccounts(_ list: [StoredAccount]) {
		if let d = try? JSONEncoder().encode(list) {
			try? d.write(to: accountsFile, options: .atomic)
		}
	}

	// MARK: - 口令哈希（SHA-256 + 随机盐）
	static func hash(password: String, salt: Data) -> Data {
		var hasher = SHA256()
		hasher.update(data: salt)
		hasher.update(data: Data(password.utf8))
		return Data(hasher.finalize())
	}
}

private struct StoredAccount: Codable {
	let username: String
	let salt: Data
	let hash: Data
}
