//
//  Text.swift
//  DevicePerfMonitor
//
//  指标数值显示（对应 Android 端 ui/kit/Text.kt）。
//
//  收敛后整个应用只展示四个核心指标，且采不到的指标不进界面——
//  所以这里不再提供「—」占位分支：调用方只渲染可用读数，
//  不可用时的原因文案由各页面自己决定怎么展示。
//
//  数值等宽字体与缓动打断策略见 Controls.swift（MonoNumber / AnimatedNumber）。
//

import SwiftUI
