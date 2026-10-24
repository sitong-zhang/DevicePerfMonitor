//
//  BackgroundRefresh.swift
//  DevicePerfMonitor
//
//  后台刷新（BGAppRefreshTask）：iOS 把 App 挂起后，无服务器前提下唯一的
//  正规刷新通道（1.0.11）。
//
//  链路：系统唤醒 App（后台）→ MetricsRepository.sampleOnce() 采样 →
//  发布快照 → AlertManager.evaluate → 实时活动（灵动岛/锁屏）拿到新数据。
//  全程不需要 UI，后台唤醒时照常执行。
//
//  必须如实说明的边界（都查证过，README「已知限制」有同等记录）：
//  - 唤醒时机由系统按使用习惯决定（通常十几分钟到几小时一次），不是实时；
//  - iOS 不允许第三方应用在后台常驻运行——苹果工程师论坛原话：退后台会被
//    挂起、定时器停摆，「持续更新只能靠推送服务器」；
//  - 推送服务器路线查证后不做：ActivityKit 推送有小时级预算、超了静默丢弃
//    （苹果文档原话：体育直播这类秒级场景「就是装不进这个预算」），且需要
//    App 联网上传 push token——撞破本项目「不申请 INTERNET 权限、没有任何
//    联网代码路径」的铁律。GitHub Actions 也当不了这个服务器：定时工作流
//    最小 5 分钟一轮（负载高时更晚），且拿不到设备 push token。
//
//  对应 Android 侧：前台服务保活，后台本来就是实时采集，不需要这套。
//

import BackgroundTasks
import Foundation

enum BackgroundRefresh {

    static let taskIdentifier = "com.devperf.monitor.la-refresh"

    /// App 启动时注册一次（AppDelegate.didFinishLaunching）。
    /// 注册不等于调度：真正提交发生在退后台时（scheduleNext）。
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            handle(task)
        }
    }

    /// 续订下一轮唤醒。每次退后台、每次唤醒完成后都调用，保持链条不断。
    static func scheduleNext() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        // 15 分钟是能提交的最早期望；系统会按电量与使用习惯自行放宽到更晚
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            #if DEBUG
            print("[BackgroundRefresh] 提交后台刷新失败: \(error)")
            #endif
        }
    }

    /// 取消待执行的唤醒（设置页关掉「后台采集」时调用）。
    static func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)
    }

    private static func handle(_ task: BGTask) {
        scheduleNext() // 先续订，保证这次之后还有下一次

        // MetricsRepository 是 @MainActor：采样 → 发布 → evaluate →
        // 实时活动更新全链路不需要 UI，后台唤醒时照常执行。
        var work: Task<Void, Never>?
        work = Task { @MainActor in
            MetricsRepository.shared.sampleOnce()
            // 采样是异步管线（workQueue → 主线程发布 → LA update），
            // 留 2s 收尾再交差（BGTask 运行时预算约 30s，2s 很便宜）
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if work?.isCancelled == false {
                task.setTaskCompleted(success: true)
            }
        }
        task.expirationHandler = {
            // 系统收回运行时：取消收尾等待，直接交差
            work?.cancel()
            task.setTaskCompleted(success: false)
        }
    }
}
