//
//  FloatingWindowController.swift
//  DevicePerfMonitor
//
//  应用内悬浮窗控制器（对应 Android 端 overlay/OverlayController.kt）。
//
//  窗口：独立 UIWindow（windowLevel = .alert + 1），层级在 App 内；
//  iOS 无 FLAG_NOT_TOUCH_MODAL 概念 —— UIWindow 只接收自身 bounds 内的
//  触摸，区域外自动穿透到下层窗口，语义等价。
//
//  手势优先级（与 Android 端一致）：拖动(单指) > 缩放(双指) > 双击隐藏 > 单击循环；
//  三态循环（单击切换）：card 完整卡片 → numbers 纯数字 → collapsed 收起；
//  贴边只吸附位置，不再自动收窄（收窄只由用户显式操作触发，1.0.8 起）；
//  垃圾桶：拖动过程中悬停底部垃圾桶区域（高亮），松手关闭。
//
//  平台限制（README「降级说明」记录）：iOS 不允许第三方应用创建系统级
//  全局悬浮窗（无 SYSTEM_ALERT_WINDOW 等价物），故仅在应用内显示；
//  App 退后台时窗口自动隐藏（scenePhase 驱动），回前台自动挂出
//  （用户要求「不需要手动打开」——iOS 上能做到的上限是回应用即显示）。
//

import SwiftUI
import UIKit

@MainActor
final class FloatingWindowController: NSObject, ObservableObject {

    static let shared = FloatingWindowController()

    // MARK: - 状态（SwiftUI 订阅）

    @Published var state = OverlayUiState()
    @Published private(set) var isVisible = false

    // MARK: - 私有

    private var window: UIWindow?
    private var hostingController: UIHostingController<OverlayRootView>?
    private var updateTimer: Timer?
    private var dragStartCenter: CGPoint = .zero
    private var currentScale: CGFloat = 1.0
    private var baseScale: CGFloat = 1.0
    // 注：删除区的悬停状态不再用私有字段存——它要驱动主界面的删除区 UI，
    // 所以放进 @Published 的 state（见 OverlayUiState.overTrash）。

    /// 各形态的窗口尺寸（含阴影安全边距：窗口尺寸 = 可见区域 + 阴影，对应需求）。
    private func windowSize(for mode: OverlayUiState.Mode) -> CGSize {
        switch mode {
        // 完整卡片 = 综合评判行 + 最多两行指标格（默认形态，稳定显示不变形）
        case .card: return CGSize(width: 340, height: 180)
        // 纯数字：只要数字、不要卡片——无玻璃无阴影的一横排
        case .numbers: return CGSize(width: 264, height: 52)
        case .collapsed: return CGSize(width: 44, height: 64)
        }
    }

    /// 启动时的默认形态：设置页「默认纯数字」开关（overlayMiniMode）。
    private var defaultMode: OverlayUiState.Mode {
        UserDefaults.standard.bool(forKey: "overlayMiniMode") ? .numbers : .card
    }

    /// 窗口透明度（设置页滑杆 0.4~1.0，UserDefaults「overlayOpacity」；未设置 = 1.0）。
    private var overlayOpacity: Double {
        let v = UserDefaults.standard.double(forKey: "overlayOpacity")
        return (v == 0 || v < 0.4) ? 1.0 : min(v, 1.0)
    }

    private var initialCenter: CGPoint {
        guard let scene = activeWindowScene() else {
            return CGPoint(x: UIScreen.main.bounds.width - 80, y: UIScreen.main.bounds.height * 0.3)
        }
        let bounds = scene.screen.bounds
        return CGPoint(x: bounds.width - 70, y: bounds.midY)
    }

    private override init() {}

    // MARK: - 显示 / 隐藏

    func show() {
        guard !isVisible, let scene = activeWindowScene() else { return }
        isVisible = true
        // 启动形态取设置页默认值（卡片 / 纯数字）
        state.mode = defaultMode

        let rootView = OverlayRootView(controller: self)
        let hosting = UIHostingController(rootView: rootView)
        hosting.view.backgroundColor = .clear
        hosting.view.isUserInteractionEnabled = true

        let window = TouchTransparentWindow(windowScene: scene)
        window.windowLevel = UIWindow.Level.alert + 1
        window.backgroundColor = .clear
        window.rootViewController = hosting
        // 窗口透明度：设置页滑杆接线（1.0.8 起真正生效）
        window.alpha = overlayOpacity
        window.frame = frame(for: state.mode, center: initialCenter)
        window.isHidden = false

        installGestures(on: hosting.view)

        self.window = window
        self.hostingController = hosting

        startUpdateTimer()
    }

    func hide() {
        stopUpdateTimer()
        window?.isHidden = true
        window = nil
        hostingController = nil
        isVisible = false
        state.mode = defaultMode
        currentScale = 1.0
        baseScale = 1.0
    }

    func toggle() {
        isVisible ? hide() : show()
    }

    /// 设置页透明度滑杆变更时调用（窗口未显示时下次 show 生效）。
    func applyOpacity() {
        window?.alpha = overlayOpacity
    }

    /// 设置页「默认纯数字」开关变更时调用（立即切换当前形态）。
    func applyDefaultMode() {
        state.mode = defaultMode
        applyFrame(center: window?.center ?? initialCenter, animated: false)
    }

    // MARK: - 窗口布局

    private func frame(for mode: OverlayUiState.Mode, center: CGPoint) -> CGRect {
        let size = windowSize(for: mode)
        return CGRect(origin: .zero, size: size).offsetBy(dx: center.x - size.width / 2,
                                                          dy: center.y - size.height / 2)
    }

    private func applyFrame(center: CGPoint, animated: Bool = true) {
        guard let window, let scene = activeWindowScene() else { return }
        let bounds = scene.screen.bounds
        let size = windowSize(for: state.mode)
        let safe = scene.windows.first?.safeAreaInsets ?? .zero

        // 位置钳制在安全区内
        let minX = size.width / 2 + 4
        let maxX = bounds.width - size.width / 2 - 4
        let minY = size.height / 2 + safe.top + 4
        let maxY = bounds.height - size.height / 2 - safe.bottom - 4
        let clamped = CGPoint(
            x: min(max(center.x, minX), maxX),
            y: min(max(center.y, minY), maxY)
        )

        if animated {
            UIView.animate(withDuration: 0.24, delay: 0,
                           options: [.curveEaseOut, .allowUserInteraction]) {
                window.frame = self.frame(for: self.state.mode, center: clamped)
            }
        } else {
            window.frame = frame(for: state.mode, center: clamped)
        }
    }

    private func applyScale() {
        guard let hostingController else { return }
        UIView.animate(withDuration: 0.18, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
            hostingController.view.transform = CGAffineTransform(scaleX: self.currentScale, y: self.currentScale)
        }
    }

    // MARK: - 手势（UIKit 优先级精确控制）

    private func installGestures(on view: UIView) {
        view.isMultipleTouchEnabled = true

        // 拖动（单指）：双指时主动失败，把识别权交给缩放
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        pan.delegate = self
        pan.maximumNumberOfTouches = 1
        view.addGestureRecognizer(pan)

        // 缩放（双指）
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
        pinch.delegate = self
        view.addGestureRecognizer(pinch)

        // 双击：隐藏（250ms 窗口由 require-to-fail 语义保证）
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        doubleTap.numberOfTouchesRequired = 1
        view.addGestureRecognizer(doubleTap)

        // 单击：三态循环（需等双击判定失败后触发 → 双击优先）
        let singleTap = UITapGestureRecognizer(target: self, action: #selector(handleSingleTap(_:)))
        singleTap.numberOfTapsRequired = 1
        singleTap.numberOfTouchesRequired = 1
        singleTap.require(toFail: doubleTap)
        view.addGestureRecognizer(singleTap)
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        guard let window else { return }
        switch gesture.state {
        case .began:
            dragStartCenter = window.center
            // 发布 dragging：主界面据此挂出底部删除区（1.0.9 之前 iOS 侧只有
            // 判定逻辑、屏幕上什么都看不到，用户不知道能拖到哪删除）
            state.dragging = true
            // 收起态下拖动即展开（让位给拖动，优先级最低）
            if state.mode == .collapsed {
                state.mode = .card
                applyFrame(center: window.center, animated: false)
            }
        case .changed:
            let translation = gesture.translation(in: window)
            let newCenter = CGPoint(x: dragStartCenter.x + translation.x,
                                    y: dragStartCenter.y + translation.y)
            window.center = newCenter

            // 删除区判定：窗口中心进入底部 trashZone 就算悬停
            let trashTop = UIScreen.main.bounds.height - DesignTokens.Gesture.trashZoneHeight
            let wasOver = state.overTrash
            state.overTrash = window.frame.midY > trashTop
            if wasOver != state.overTrash {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
            }
        case .ended, .cancelled:
            let wasOver = state.overTrash
            state.dragging = false
            state.overTrash = false
            if wasOver {
                hide()
                return
            }

            let center = window.center
            // 贴边只吸附位置，不改变形态（用户原话「不要动它」，1.0.8 起）
            applyFrame(center: center)
        default:
            break
        }
    }

    @objc private func handlePinch(_ gesture: UIPinchGestureRecognizer) {
        switch gesture.state {
        case .began:
            baseScale = currentScale
        case .changed:
            let scaled = baseScale * gesture.scale
            currentScale = CGFloat(
                min(max(Double(scaled), DesignTokens.Gesture.scaleRange.lowerBound),
                    DesignTokens.Gesture.scaleRange.upperBound)
            )
            applyScale()
        case .ended, .cancelled:
            baseScale = currentScale
        default:
            break
        }
    }

    @objc private func handleSingleTap(_ gesture: UITapGestureRecognizer) {
        // 三态循环：card → numbers → collapsed → card。
        // 内容形变交给 SwiftUI 过渡（spring），窗口尺寸瞬切——不拿窗口动画
        // 去形变用户正在看的卡片（与 Android applyLayout 同一原则）。
        let next: OverlayUiState.Mode
        switch state.mode {
        case .card: next = .numbers
        case .numbers: next = .collapsed
        case .collapsed: next = .card
        }
        withAnimation(
            .spring(
                response: DesignTokens.Motion.Spring.DefaultSpatial.response,
                dampingFraction: DesignTokens.Motion.Spring.DefaultSpatial.dampingFraction
            )
        ) {
            state.mode = next
        }
        applyFrame(center: window?.center ?? initialCenter, animated: false)
    }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        withAnimation(.easeOut(duration: 0.24)) {
            hide()
        }
    }

    // MARK: - 数据刷新（500ms 粒度，绕开采集 tick）

    private func startUpdateTimer() {
        stopUpdateTimer()
        let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.refresh()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        updateTimer = timer
    }

    private func stopUpdateTimer() {
        updateTimer?.invalidate()
        updateTimer = nil
    }

    private func refresh() {
        guard isVisible else { return }
        let repository = MetricsRepository.shared
        repository.sampleOnce()
        let overlay = repository.overlayData(now: Date().timeIntervalSince1970)
        let snapshot = repository.snapshot

        AlertManager.shared.evaluate(snapshot: snapshot, overlay: overlay)
        state.alert = AlertManager.shared.alertActive

        // 形态切换时同步窗口尺寸（瞬切；内容过渡由 SwiftUI 负责）
        if window?.frame.size != windowSize(for: state.mode) {
            applyFrame(center: window?.center ?? initialCenter, animated: false)
        }
    }

    // MARK: - 工具

    private func activeWindowScene() -> UIWindowScene? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
    }
}

// MARK: - 手势代理：单指拖动优先，双指缩放优先

extension FloatingWindowController: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        // 允许拖动与缩放同时待命，由 shouldBegin 中的触摸数裁决
        true
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if let pan = gestureRecognizer as? UIPanGestureRecognizer {
            // 双指触摸时拖动让位给缩放
            return pan.numberOfTouches <= 1
        }
        return true
    }
}

// MARK: - 窗口：区域外触摸穿透（iOS 上 UIWindow 本就只接收 bounds 内触摸，
// 这里显式声明语义，便于对照 Android FLAG_NOT_TOUCH_MODAL）

final class TouchTransparentWindow: UIWindow {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let view = super.hitTest(point, with: event)
        // bounds 外返回 nil（穿透到下层窗口）；内正常响应
        return view == self ? nil : view
    }
}

// MARK: - 悬浮窗根视图
//
// 直接观察 MetricsRepository（@Published snapshot）与控制器状态，
// 每次仓库发布即重算 overlayData（含 500ms 粒度 FPS 直读），
// 无需额外中转状态，也不会因重建视图丢失数值动画连续性。

struct OverlayRootView: View {
    @ObservedObject var controller: FloatingWindowController
    @ObservedObject var repository: MetricsRepository

    init(controller: FloatingWindowController) {
        self.controller = controller
        self.repository = MetricsRepository.shared
    }

    var body: some View {
        let data = repository.overlayData(now: Date().timeIntervalSince1970)
        OverlayView(state: controller.state, data: data, intervalMs: 500)
    }
}
