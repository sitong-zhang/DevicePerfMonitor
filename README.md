# 观序 · DevicePerfMonitor

一套专注移动设备性能与自由安装的轻量工具：Android 端实时性能监测，苹果端同款 App 经自签安装落地到 iPhone，两端都尊重你的隐私。

一个看设备实时状态的 App。打开就能看内存占用、温度、屏幕刷新率和帧率，还有一个综合评分；还能开一个全局悬浮窗（安卓）/ 应用内悬浮 HUD（苹果），退到后台自动隐藏、回到前台自动出现。App 首次打开需注册或登录账号（账号仅保存在本机，用于登录态持久化）。

数据全程在本地采集和存储，不联网、不上传、不接任何统计 SDK。App 唯一会主动联网的地方是：检查更新、拉取公告——这两样都只是读取 GitHub 上公开的内容，不会向任何服务器发任何数据。

## 仓库里的三样东西

| 名称 | 路径 | 说明 |
|---|---|---|
| **安卓版** | Release 中的 `*.apk`（源码在 `android/`） | 观序 性能监测 App，直接安装 |
| **苹果版** | `ios/`（源码） | 与安卓版同功能的 iOS 性能监测 App；未签名 IPA 由仓库 CI 自动构建，需经签名工具用你的 Apple ID 签名后安装 |
| **签名文件** | `sign-tool/`（源码） | 开源自签安装工具：拉取苹果版未签名 IPA、申请临时证书、签名并无线安装到 iPhone |

> 苹果版未签名 IPA 由本仓库的 GitHub Actions（macOS runner）自动构建并发布到 `ios-unsigned` Release，无需本地 macOS 即可拿到安装包。详见 `ios/README.md`。

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

- **安卓版**：[Releases](https://github.com/sitong-zhang/DevicePerfMonitor/releases) 页面的 APK（液态玻璃版，文件名 `DevicePerfMonitor-<版本>-release.apk`），允许「未知来源」即可安装，首次打开需注册/登录。
- **苹果版**：未签名 IPA 由仓库 CI 自动构建，发布在 [`ios-unsigned` Release](https://github.com/sitong-zhang/DevicePerfMonitor/releases/tag/ios-unsigned)；需在 Mac + Xcode 编译签名工具，用你的 Apple ID 签名安装。

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

## 苹果版：观序 iOS App（ios/）+ 签名工具（sign-tool/）

`ios/` 是与安卓版同功能的 iOS 性能监测 App 源码（SwiftUI），应用名为「观序」。由于 iOS 不暴露设备温度、也不允许全局悬浮窗，苹果版的评分维度改为「流畅度 / 内存 / 电量」，并以「应用内悬浮 HUD」替代全局悬浮窗；并内置本地账号（注册 / 登录 / 登出）。具体构建与平台差异见 `ios/README.md`。

`sign-tool/` 是一套开源自签安装工具（产品名 **全能签**），用于在 iPhone 上**用你自己的 Apple ID** 把苹果版（未签名 IPA）签名并安装到本机，无需越狱、无需企业证书。它包含从仓库 `ios-unsigned` Release 拉取 `GuanXu-unsigned.ipa`、申请临时开发证书、签名到无线安装的全流程。

**全能签 的产品约束**：它只做「把观序签名装到本机」这一件事——签名完成后自动上报一条签名回执（仅含时间/机型/系统版本/结果，不含 Apple ID、密码、证书等任何凭证）、进入休眠锁定态并自动关闭；再次打开仅提供「重新签名（续签）」入口。管理端 App 可查看全部签名记录。

**未签名 IPA（自动构建）**

本仓库 `.github/workflows/build-ios.yml` 已在 macOS runner 上完成：XcodeGen 生成工程 → `xcodebuild` 免签名编译 → 打包 `GuanXu-unsigned.ipa` 与 `Quannengqian-unsigned.ipa` → 发布到 [`ios-unsigned` Release](https://github.com/sitong-zhang/DevicePerfMonitor/releases/tag/ios-unsigned)。你本地无需 macOS 即可拿到安装包。

**签名安装（下载 全能签 即可，无需 macOS）**

1. 从 `ios-unsigned` Release 下载 `Quannengqian-unsigned.ipa`，借助 AltStore / SideStore 把它装进手机。
2. 打开 全能签，填入你的 **Apple ID 账号和密码**，点「开始安装」——全能签 会自动从 `ios-unsigned` Release 拉取 `GuanXu-unsigned.ipa` 并签名安装到这台 iPhone，完成后自动关闭。

`sign-tool/` 已内联 `Zsign`、`IDeviceKitten` 等依赖，无需再初始化子模块。详见 `sign-tool/README.md`。

> 注意：免费 Apple ID 申请的开发证书有效期为 7 天，到期需在签名工具里重新登录 Apple ID 续签后再次安装。

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

- Android 端应用（仓库根目录及 `android/`）采用 **Apache-2.0**，见根目录 `LICENSE`。
- `sign-tool/`（含内联的 `Zsign`、`IDeviceKitten` 等）整体沿用上游 **GPL-3.0**，见 `sign-tool/LICENSE`。署名与版权信息保留在对应源码文件头中。
