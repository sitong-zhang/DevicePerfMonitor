# DevicePerfMonitor

一个看设备实时状态的 App。打开就能看内存占用、温度、屏幕刷新率和帧率，还有一个综合评分；还能开一个全局悬浮窗，退到后台自动隐藏、回到前台自动出现。

数据全程在本地采集和存储，不联网、不上传、不接任何统计 SDK。App 唯一会主动联网的地方是：检查更新、拉取公告——这两样都只是读取 GitHub 上公开的内容，不会向任何服务器发任何数据。

## 主要功能

- **四个核心指标 + 综合评分**：内存占用率、设备温度、屏幕刷新率、帧率。采不到的指标直接不显示，不拿估算值凑数。
- **综合评分** = 流畅度（帧率 ÷ 刷新率）0.4 + 温度 0.35 + 内存 0.25。
- **历史回看**：秒级采样，本地保留 2 小时，详情页可回看 1 分钟到 2 小时。
- **全局悬浮窗**：拖动能移动、靠边吸附、拖到垃圾桶关闭；可切换卡片 / 纯数字 / 收起三种形态，不透明度也能调。
- **更新提示**：打开 App 发现有新版本会提示更新，更新前旧版本功能暂不可用（断网时放行，不会锁死离线用户）。
- **公告**：开发者发公告后，用户下次打开 App 会收到，必须从头滑到尾才能关掉。

## 视觉

界面用 Material 3 Expressive。玻璃质感（按钮、开关、滑块、底栏、卡片）用的是开源库 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0，引擎与官方 catalog 组件源码已原样收进工程 `android/app/src/main/java/com/kyant/backdrop/`），效果参数直接照抄它官方示例的写法。低版本系统（Android 12 以下）自动退化为普通半透明，不会崩。

## 下载与版本

安装包在 [Releases](https://github.com/sitong-zhang/DevicePerfMonitor/releases) 页面，只发 Android 一个 APK（液态玻璃版，文件名 `DevicePerfMonitor-<版本>-release.apk`）。iOS 版已停止维护并从仓库移除（1.0.30 起），不再构建。

## 构建

**Android**

```bash
cd android
./build-apk.sh
```

脚本会检查 JDK 17+ 和 Android SDK，构建完打印签名指纹。

**签名（重要）**：release 包的正式签名密钥是 `android/keystore.properties` 指向的 `devperf-release.keystore`（均已被 gitignore，不进仓库）。配置方法见 `android/keystore.properties.template`。要点：

- **同一个 keystore 签所有版本**。1.0.26 之前误用 debug keystore 签名，构建机器/沙箱一重建，签名就变，用户覆盖安装会报「签名不一致」，只能卸载重装（设置和历史清空）。1.0.26 起改用正式 keystore，本地与 CI（GitHub secrets 里的同一份密钥）同签名，不再漂移。
- **keystore 和密码务必备份**。私钥丢了无法补签，只能换签名让用户卸载重装。备份建议放密码管理器 + 离线副本。
- GitHub 仓库 secrets 已配置：`DEVPERF_KEYSTORE_BASE64` / `DEVPERF_KEYSTORE_PASSWORD` / `DEVPERF_KEY_ALIAS` / `DEVPERF_KEY_PASSWORD`，CI 构建时自动还原签名配置，并在产物里核对证书是 `CN=DevicePerfMonitor`。

## 权限（Android）

- `SYSTEM_ALERT_WINDOW`：全局悬浮窗，必需，拒绝就引导去系统设置授权。
- `FOREGROUND_SERVICE`：采集和悬浮窗保活，必需。
- `POST_NOTIFICATIONS`：告警和常驻通知，可选，拒绝不影响采集。
- `RECEIVE_BOOT_COMPLETED`：开机恢复悬浮窗，可选。
- `INTERNET`：只用于读取 GitHub 上的更新和公告，**不上报任何数据**。

## 已知限制

- Android 的真实背景模糊在 Android 12 以下拿不到，会自动退化。
- 崩了会弹全屏报告（异常、调用栈、机型），Android 侧还会把报告写进文件，下次启动一定补弹。

## License

Apache-2.0，见 `LICENSE`。第三方库协议见仓库依赖声明。
