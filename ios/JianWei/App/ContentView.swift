import SwiftUI

struct ContentView: View {
	@EnvironmentObject private var account: AccountManager
	@EnvironmentObject private var vm: MetricsViewModel
	@State private var showHUD = false

	var body: some View {
		// 未登录先走登录 / 注册门禁
		if !account.isLoggedIn {
			LoginView()
		} else {
			ZStack {
				TabView {
					DashboardView()
						.tabItem { Label("监测", systemImage: "gauge.medium") }
					HistoryView()
						.tabItem { Label("历史", systemImage: "chart.xyaxis.line") }
					AccountView()
						.tabItem { Label("我的", systemImage: "person.circle") }
					SettingsView()
						.tabItem { Label("设置", systemImage: "gearshape") }
				}
				.accentColor(.blue)

				if showHUD {
					HUDOverlay(show: $showHUD)
						.transition(.scale)
				}
			}
			.overlay(alignment: .bottomTrailing) {
				if !showHUD {
					Button {
						withAnimation { showHUD = true }
					} label: {
						Image(systemName: "gauge.open.with.lines.needle.33percent")
							.font(.system(size: 20, weight: .semibold))
							.frame(width: 52, height: 52)
							.background(.ultraThinMaterial)
							.clipShape(Circle())
							.shadow(radius: 6)
					}
					.padding()
				}
			}
		}
	}
}
