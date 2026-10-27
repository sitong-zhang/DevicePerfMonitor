import Foundation
import SwiftUI
import Combine

final class MetricsViewModel: ObservableObject {
    @Published var latest: MetricSample?
    @Published var isMonitoring = false
    @Published var updateAvailable = false
    @Published var updateTag: String?
    @Published var announcement: String?
    @Published var showAnnouncement = false

    private var timer: Timer?

    func start() {
        DeviceMonitor.shared.startFPS()
        isMonitoring = true
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.tick()
        }
        Task { await checkUpdateAndAnnouncement() }
    }

    func stop() {
        timer?.invalidate()
        DeviceMonitor.shared.stopFPS()
        isMonitoring = false
    }

    private func tick() {
        let s = DeviceMonitor.shared.currentSample()
        DispatchQueue.main.async {
            self.latest = s
            MetricsStore.shared.append(s)
        }
    }

    private func checkUpdateAndAnnouncement() async {
        do {
            let rel = try await UpdateService.shared.latestRelease()
            let cur = UpdateService.currentVersion
            if rel.tagName != "v\(cur)" && rel.tagName != cur {
                await MainActor.run {
                    self.updateAvailable = true
                    self.updateTag = rel.tagName
                }
            }
        } catch { /* 离线放行，不弹错 */ }

        do {
            let ann = try await UpdateService.shared.announcement()
            await MainActor.run {
                self.announcement = ann.content
                self.showAnnouncement = !ann.content.isEmpty
            }
        } catch { /* 忽略 */ }
    }
}
