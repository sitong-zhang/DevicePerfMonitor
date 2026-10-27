import Foundation
import UIKit

enum DeviceInfo {
	/// 本机名称（注册到开发者后台时当设备名）
	static var name: String { UIDevice.current.name }
	
	/// 本机 UDID。
	/// iOS 不公开 API 读 UDID，通用做法是从本 App 自己的 embedded.mobileprovision 里读 ProvisionedDevices
	/// （本 App 当初就是签给这台设备的，里面必然有这台机器的 UDID）
	static func localUDID() -> String? {
		guard
			let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
			let raw = try? Data(contentsOf: url),
			let text = String(data: raw, encoding: .isoLatin1),
			let start = text.range(of: "<?xml"),
			let end = text.range(of: "</plist>", options: .backwards, range: start.upperBound..<text.endIndex)
		else { return nil }
		
		let xml = String(text[start.lowerBound...end.upperBound])
		guard
			let data = xml.data(using: .isoLatin1),
			let plist = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any],
			let devices = plist["ProvisionedDevices"] as? [String]
		else { return nil }
		
		return devices.first?.uppercased()
	}
	
	/// 机型，如 iPhone15,3
	static var modelIdentifier: String {
		var size = 0
		sysctlbyname("hw.machine", nil, &size, nil, 0)
		var machine = [CChar](repeating: 0, count: size)
		sysctlbyname("hw.machine", &machine, &size, nil, 0)
		return String(cString: machine)
	}
}
