import SwiftUI

@main
struct GuanXuApp: App {
	@StateObject private var account = AccountManager.shared
	@StateObject private var viewModel = MetricsViewModel()

	var body: some Scene {
		WindowGroup {
			ContentView(account: account)
				.environmentObject(viewModel)
				.onAppear {
					UIDevice.current.isBatteryMonitoringEnabled = true
					viewModel.start()
				}
		}
	}
}
