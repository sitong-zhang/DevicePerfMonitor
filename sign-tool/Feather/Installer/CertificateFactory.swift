import Foundation
import Security
import OSLog

/// 生成 RSA 私钥 → CSR → 把苹果返回的证书打包成 p12。
///
/// iOS 上没有 SecPKCS12Export（那是 macOS-only），所以这里自己按 ASN.1 拼 PKCS#12。
/// 私钥 bag 不加密、不带 MacData，openssl/zsign 读这种格式最没脾气，也绕开了
/// 各种 PBES/RC2 算法在 iOS 上的兼容问题。密码固定为空。
enum CertificateFactory {
	struct CSRRequest {
		let pem: String
		let privateKey: SecKey
		private let applicationTag: Data
		
		fileprivate init(pem: String, privateKey: SecKey, applicationTag: Data) {
			self.pem = pem
			self.privateKey = privateKey
			self.applicationTag = applicationTag
		}
		
		/// 用完了把临时钥匙串项删干净
		func release() {
			SecItemDelete([
				kSecClass as String: kSecClassKey,
				kSecAttrApplicationTag as String: applicationTag
			] as CFDictionary)
		}
	}
	
	// MARK: - CSR
	static func makeCSR(commonName: String, emailAddress: String) throws -> CSRRequest {
		let tag = "com.devperf.installer.\(UUID().uuidString)".data(using: .utf8)!
		
		let attributes: [String: Any] = [
			kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
			kSecAttrKeySizeInBits as String: 2048,
			// iOS 上临时生成的、不带 permanent 的 key 有时会拒绝导出 external representation，
			// 放进钥匙串再删，走最稳的一条路
			kSecPrivateKeyAttrs as String: [
				kSecAttrIsPermanent as String: true,
				kSecAttrApplicationTag as String: tag,
				kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
			]
		]
		
		var randomError: Unmanaged<CFError>?
		guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &randomError) else {
			throw InstallerError.csrFailed(randomError?.takeRetainedValue().localizedDescription ?? "生成私钥失败")
		}
		
		guard let publicKey = SecKeyCopyPublicKey(privateKey) else {
			throw InstallerError.csrFailed("取不到公钥")
		}
		
		var exportError: Unmanaged<CFError>?
		guard let publicKeyDER = SecKeyCopyExternalRepresentation(publicKey, &exportError) as Data? else {
			throw InstallerError.csrFailed(exportError?.takeRetainedValue().localizedDescription ?? "导出公钥失败")
		}
		
		// SubjectPublicKeyInfo：RSA 算法 OID + PKCS#1 公钥
		let spki = DER.sequence([
			DER.sequence([DER.oid("1.2.840.113549.1.1.1"), DER.null()]),
			DER.bitString(publicKeyDER)
		])
		
		// Subject：CN + emailAddress（AltSign 沿用多年的写法）
		let subject = DER.sequence([
			DER.set([DER.sequence([DER.oid("2.5.4.3"), DER.utf8String(commonName)])]),
			DER.set([DER.sequence([DER.oid("1.2.840.113549.1.9.1"), DER.ia5String(emailAddress)])])
		])
		
		let requestInfo = DER.sequence([
			DER.integer(0),
			subject,
			spki,
			DER.context(0xA0, Data())  // attributes，空
		])
		
		var signError: Unmanaged<CFError>?
		guard let signature = SecKeyCreateSignature(
			privateKey,
			.rsaSignatureMessagePKCS1v15SHA256,
			requestInfo as CFData,
			&signError
		) as Data? else {
			throw InstallerError.csrFailed(signError?.takeRetainedValue().localizedDescription ?? "CSR 签名失败")
		}
		
		let csr = DER.sequence([
			requestInfo,
			DER.sequence([DER.oid("1.2.840.113549.1.11"), DER.null()]),  // sha256WithRSAEncryption
			DER.bitString(signature)
		])
		
		return CSRRequest(pem: Self.pem(csr, label: "CERTIFICATE REQUEST"), privateKey: privateKey, applicationTag: tag)
	}
	
	// MARK: - 证书 + 私钥 → p12
	static func makeP12(certificate: Data, privateKey: SecKey, password: String = "") throws -> Data {
		var keyError: Unmanaged<CFError>?
		guard let privateKeyDER = SecKeyCopyExternalRepresentation(privateKey, &keyError) as Data? else {
			throw InstallerError.certificateFailed(keyError?.takeRetainedValue().localizedDescription ?? "读不出私钥")
		}
		
		guard let secCert = SecCertificateCreateWithData(nil, certificate as CFData) else {
			throw InstallerError.certificateFailed("解析苹果返回的证书失败")
		}
		let certificateDER = SecCertificateCopyData(secCert) as Data
		
		// PKCS#8 PrivateKeyInfo 包起来
		let privateKeyInfo = DER.sequence([
			DER.integer(0),
			DER.sequence([DER.oid("1.2.840.113549.1.1.1"), DER.null()]),
			DER.octetString(privateKeyDER)
		])
		
		// certBag（x509Certificate）+ keyBag
		let certBag = Self.safeBag(
			bagId: "1.2.840.113549.1.12.10.1.3",
			value: DER.sequence([
				DER.oid("1.2.840.113549.1.9.22.1"),
				DER.context(0xA0, DER.octetString(certificateDER))
			])
		)
		let keyBag = Self.safeBag(bagId: "1.2.840.113549.1.12.10.1.1", value: privateKeyInfo)
		
		let safeContents = DER.sequence([certBag, keyBag])
		let authenticatedSafe = DER.sequence([
			Self.contentInfo(contentType: "1.2.840.113549.1.7.1", content: DER.octetString(safeContents))
		])
		
		// PFX：version = 3，authSafe，不做 MacData（openssl 允许缺失）
		return DER.sequence([
			DER.integer(3),
			Self.contentInfo(contentType: "1.2.840.113549.1.7.1", content: DER.octetString(authenticatedSafe))
		])
	}
	
	// MARK: - 私有
	private static func safeBag(bagId: String, value: Data) -> Data {
		DER.sequence([DER.oid(bagId), DER.context(0xA0, value)])
	}
	
	private static func contentInfo(contentType: String, content: Data) -> Data {
		DER.sequence([DER.oid(contentType), DER.context(0xA0, content)])
	}
	
	private static func pem(_ der: Data, label: String) -> String {
		let base64 = der.base64EncodedString()
		var lines: [String] = []
		var index = base64.startIndex
		while index < base64.endIndex {
			let end = base64.index(index, offsetBy: 64, limitedBy: base64.endIndex) ?? base64.endIndex
			lines.append(String(base64[index..<end]))
			index = end
		}
		return "-----BEGIN \(label)-----\n\(lines.joined(separator: "\n"))\n-----END \(label)-----\n"
	}
}

// MARK: - 极简 DER 编码器
enum DER {
	static func length(_ count: Int) -> Data {
		if count < 0x80 { return Data([UInt8(count)]) }
		var bytes: [UInt8] = []
		var value = count
		while value > 0 {
			bytes.insert(UInt8(value & 0xff), at: 0)
			value >>= 8
		}
		return Data([UInt8(0x80 | bytes.count)]) + bytes
	}
	
	static func tlv(_ tag: UInt8, _ content: Data) -> Data {
		Data([tag]) + length(content.count) + content
	}
	
	static func sequence(_ items: [Data]) -> Data { tlv(0x30, items.reduce(Data(), +)) }
	static func set(_ items: [Data]) -> Data { tlv(0x31, items.reduce(Data(), +)) }
	
	static func integer(_ number: Int) -> Data {
		if number == 0 { return tlv(0x02, Data([0x00])) }
		var value = number
		var bytes: [UInt8] = []
		while value > 0 {
			bytes.insert(UInt8(value & 0xff), at: 0)
			value >>= 8
		}
		return integer(bytes: Data(bytes))
	}
	
	static func integer(bytes: Data) -> Data {
		var normalized = bytes
		while normalized.first == 0x00, normalized.count > 1 { normalized.removeFirst() }
		if (normalized.first ?? 0) >= 0x80 { normalized.insert(0x00, at: 0) }
		return tlv(0x02, normalized)
	}
	
	static func oid(_ dotted: String) -> Data {
		let parts = dotted.split(separator: ".").compactMap { Int($0) }
		guard parts.count >= 2 else { return Data() }
		
		var content = Data([UInt8(parts[0] * 40 + parts[1])])
		for part in parts.dropFirst(2) {
			var value = part
			var stack: [UInt8] = [UInt8(value & 0x7f)]
			value >>= 7
			while value > 0 {
				stack.insert(UInt8((value & 0x7f) | 0x80), at: 0)
				value >>= 7
			}
			content.append(contentsOf: stack)
		}
		return tlv(0x06, content)
	}
	
	static func null() -> Data { tlv(0x05, Data()) }
	static func octetString(_ data: Data) -> Data { tlv(0x04, data) }
	static func bitString(_ data: Data) -> Data { tlv(0x03, Data([0x00]) + data) }
	static func utf8String(_ value: String) -> Data { tlv(0x0C, Data(value.utf8)) }
	static func ia5String(_ value: String) -> Data { tlv(0x16, Data(value.utf8)) }
	static func context(_ tag: UInt8, _ content: Data) -> Data { tlv(tag, content) }
}
