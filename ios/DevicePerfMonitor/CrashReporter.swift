//
//  CrashReporter.swift
//  DevicePerfMonitor
//
//  全局崩溃监测器（iOS 端）：
//  - NSException（含多数 Swift/ObjC 逻辑崩溃）→ 立即弹出一个 UIKit 全屏窗口展示技术栈，可截图；
//  - 底层信号崩溃（SIGSEGV / SIGABRT / SIGTRAP 等，Swift fatalError、越界、空解包等会触发）
//    → 无法在信号处理里安全地刷新 UI，故写入缓存文件，下次启动自动弹出上次崩溃记录。
//
//  展示层用纯 UIKit（非 SwiftUI），避免与主 App 相同的合成崩溃。

import Foundation
import UIKit

final class CrashReporter {
    static let shared = CrashReporter()

    private var crashWindow: UIWindow?

    private var pendingReportPath: String {
        let dir = (NSSearchPathForDirectoriesInDomains(.cachesDirectory, .userDomainMask, true).first)
            ?? NSTemporaryDirectory()
        return (dir as NSString).appendingPathComponent("crash_pending.txt")
    }

    // MARK: - 安装

    func install() {
        // 注意：传给 C 函数指针（@convention(c)）的闭包不能捕获动态 Self 类型，
        // 因此这里统一用具体类型名 CrashReporter 引用单例与静态方法。
        NSSetUncaughtExceptionHandler { exception in
            let report = CrashReporter.format(exception: exception)
            CrashReporter.write(report, to: CrashReporter.shared.pendingReportPath)
            CrashReporter.shared.presentCrashWindow(report: report)
        }

        // 底层信号（Swift 运行时崩溃多走这条路）
        for sig in [SIGABRT, SIGILL, SIGSEGV, SIGBUS, SIGTRAP, SIGFPE] {
            signal(sig) { s in
                let report = CrashReporter.formatSignal(s)
                CrashReporter.write(report, to: CrashReporter.shared.pendingReportPath)
                // 恢复默认处理并重新触发，让系统生成真实崩溃报告
                signal(s, SIG_DFL)
                raise(s)
            }
        }
    }

    /// 启动时检查上一次的信号崩溃记录并展示（若有）
    func checkPendingCrash() {
        guard let report = try? String(contentsOfFile: pendingReportPath, encoding: .utf8),
              !report.isEmpty else { return }
        presentCrashWindow(report: "（这是上一次启动时的崩溃记录）\n\n" + report)
        try? FileManager.default.removeItem(atPath: pendingReportPath)
    }

    // MARK: - 格式化

    private static func format(exception: NSException) -> String {
        var lines: [String] = []
        lines.append("========== 应用崩溃报告 / Crash Report ==========")
        lines.append("时间 / Time: \(Date())")
        lines.append("异常类型 / Exception: \(exception.name.rawValue)")
        lines.append("原因 / Reason: \(exception.reason ?? "未知")")
        lines.append("用户信息 / UserInfo: \(exception.userInfo ?? [:])")
        lines.append("---------------------------------------------------")
        lines.append("调用栈 / Stack trace:")
        lines.append(contentsOf: exception.callStackSymbols)
        return lines.joined(separator: "\n")
    }

    private static func formatSignal(_ sig: Int32) -> String {
        var addresses = [UnsafeMutableRawPointer?](repeating: nil, count: 64)
        let count = backtrace(&addresses, Int32(addresses.count))
        var lines = ["========== 应用崩溃报告 / Crash Report (Signal \(sig)) ==========",
                     "时间 / Time: \(Date())",
                     "---------------------------------------------------",
                     "调用栈 / Stack trace:"]
        if let symbols = backtrace_symbols(&addresses, count) {
            for i in 0..<Int(count) {
                if let sym = symbols[i] {
                    lines.append(String(cString: sym))
                }
            }
            // backtrace_symbols 内部已分配内存；崩溃进程即将终止，无需 free
        } else {
            lines.append("（无法解析符号）")
        }
        return lines.joined(separator: "\n")
    }

    private static func write(_ text: String, to path: String) {
        try? text.write(toFile: path, atomically: true, encoding: .utf8)
    }

    // MARK: - 展示

    private func presentCrashWindow(report: String) {
        DispatchQueue.main.async {
            let window = UIWindow(frame: UIScreen.main.bounds)
            window.windowLevel = .alert + 1
            window.rootViewController = CrashViewController(report: report)
            window.makeKeyAndVisible()
            self.crashWindow = window
        }
    }
}

// MARK: - 纯 UIKit 崩溃展示页

final class CrashViewController: UIViewController {
    private let report: String

    init(report: String) {
        self.report = report
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        let title = UILabel()
        title.text = "应用崩溃了（Crash）"
        title.textColor = .systemRed
        title.font = .boldSystemFont(ofSize: 18)
        title.translatesAutoresizingMaskIntoConstraints = false

        let textView = UITextView()
        textView.text = report
        textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        textView.isEditable = false
        textView.isSelectable = true
        textView.translatesAutoresizingMaskIntoConstraints = false

        let copyButton = UIButton(type: .system)
        copyButton.setTitle("复制崩溃信息", for: .normal)
        copyButton.addTarget(self, action: #selector(copyTapped), for: .touchUpInside)

        let closeButton = UIButton(type: .system)
        closeButton.setTitle("关闭应用", for: .normal)
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)

        let bar = UIStackView(arrangedSubviews: [copyButton, closeButton])
        bar.axis = .horizontal
        bar.distribution = .fillEqually
        bar.spacing = 12
        bar.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(title)
        view.addSubview(textView)
        view.addSubview(bar)

        NSLayoutConstraint.activate([
            title.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            title.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            title.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),

            textView.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 12),
            textView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            textView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            textView.bottomAnchor.constraint(equalTo: bar.topAnchor, constant: -12),

            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            bar.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),
            bar.heightAnchor.constraint(equalToConstant: 44),
        ])
    }

    @objc private func copyTapped() {
        UIPasteboard.general.string = report
    }

    @objc private func closeTapped() {
        // 退到后台（无法强制退出，交还给系统）
        _ = UIApplication.shared.perform(NSSelectorFromString("suspend"))
        self.view.window?.isHidden = true
    }
}
