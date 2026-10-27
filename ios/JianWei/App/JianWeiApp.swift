import SwiftUI

@main
struct JianWeiApp: App {
    @StateObject private var viewModel = MetricsViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(viewModel)
                .onAppear {
                    UIDevice.current.isBatteryMonitoringEnabled = true
                    viewModel.start()
                }
        }
    }
}
