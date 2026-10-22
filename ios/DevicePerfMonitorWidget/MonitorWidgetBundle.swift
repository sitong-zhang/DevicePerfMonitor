//
//  MonitorWidgetBundle.swift
//  DevicePerfMonitorWidget
//
//  Widget Extension 入口：仅承载实时活动（Live Activity）UI，
//  不提供桌面小组件（需求未要求，避免申请无关能力）。
//

import WidgetKit
import SwiftUI

@main
struct MonitorWidgetBundle: WidgetBundle {
    var body: some Widget {
        MonitorLiveActivity()
    }
}
