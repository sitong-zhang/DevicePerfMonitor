# PulseGuard · DevicePerfMonitor

一套专注 Android 设备性能监测的轻量工具，尊重你的隐私。

一个看设备实时状态的 App。打开就能看内存占用、温度、屏幕刷新率和帧率，还有一个综合评分；还能开一个全局悬浮窗，退到后台自动隐藏、回到前台自动出现。App 首次打开需注册或登录账号。

App 内置账号体系（注册需邮箱验证码），性能数据与账号操作会按小时自动同步、并在关键操作发生时实时上报到管理端，便于管理人员实时查看与管控。检查更新、拉取公告同样经由服务端获取。

## 仓库里的三样东西

| 名称 | 路径 | 说明 |
|---|---|---|
| **用户端 App** | Release 中的 `app-release.apk`（源码在 `android/app/`） | PulseGuard 性能监测 App，直接安装 |
| **管理端 App** | Release 中的 `manager-release.apk`（源码在 `android/manager/`） | 管理人员管控后台，密码锁进入，**仅限管理人员下载** |
| **后端服务** | `backend/`（源码） | Flask + SQLite，提供账号、同步、管控与公告 API，可自托管 |

## 主要功能

- **四个核心指标 + 综合评分**：内存占用率、设备温度、屏幕刷新率、帧率。采不到的指标直接不显示，不拿估算值凑数。
- **综合评分** = 流畅度（帧率 ÷ 刷新率）0.4 + 温度 0.35 + 内存 0.25。
- **历史回看**：秒级采样，本地保留 2 小时，详情页可回看 1 分钟到 2 小时。
- **全局悬浮窗**：拖动能移动、靠边吸附、拖到垃圾桶关闭；可切换卡片 / 纯数字 / 收起三种形态，不透明度也能调。
- **更新提示**：打开 App 发现有新版本会提示更新，更新前旧版本功能暂不可用（断网时放行，不会锁死离线用户）。
- **公告**：开发者发公告后，用户下次打开 App 会收到，必须从头滑到尾才能关掉。

## 视觉

界面用 Material 3 Expressive。玻璃质感（按钮、开关、滑块、底栏、卡片）用的是开源库 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0，引擎与官方 catalog 组件源码已原样收进工程 `android/app/src/main/java/com/kyant/backdrop/`），效果参数直接照抄它官方示例的写法。低版本系统（Android 12 以下）自动退化为普通半透明，不会崩。

## 架构与账号体系

- **后端服务（`backend/`）**：Flask + SQLite 服务，提供邮箱验证码注册、登录、数据同步、操作上报与管理端 API；可自托管，App 通过 `SERVER_URL`（构建期由 `DEVPERF_SERVER_URL` 注入，默认本机联调地址）指向。
- **用户端 App（`android/app`）**：注册需邮箱验证码 + 手机号 + 账号 + 密码 + 昵称（全部必填）；登录后每小时自动把性能数据与操作记录同步到后端，关键操作（购买、功能激活等）实时上报；管理端的公告 / 功能开关 / 状态变更实时生效。
- **管理端 App（`android/manager`）**：密码锁（由部署机密注入，仓库不记录明文；输入一次后本地永久记住，除非卸载）；可查看全部注册用户及其数据 / 操作记录，对任意账号进行功能禁用、状态管控、定向公告等，并可发布全局公告。**仅限管理人员下载。**
- **邮件验证码**：通过真实 SMTP（QQ 邮箱）发送，邮件正文内嵌分子智能科技 logo。

## 下载与版本

- **安卓版**：[Releases](https://github.com/sitong-zhang/DevicePerfMonitor/releases/tag/apk) 页面的 `app-release.apk`（液态玻璃版），允许「未知来源」即可安装，首次打开需注册/登录。
- **管理端**：内部工具，**不公开发布**。由本地构建后以 `管理端<版本号>.apk` 直接交付给授权管理人员（内含账号管控能力，请勿外传）。

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
- `INTERNET`：用于检查更新、拉取公告，以及账号注册 / 登录 / 数据同步 / 接收管理端下发内容。

## 已知限制

- Android 的真实背景模糊在 Android 12 以下拿不到，会自动退化。
- 崩了会弹全屏报告（异常、调用栈、机型），Android 侧还会把报告写进文件，下次启动一定补弹。

## License

- Android 端应用（仓库根目录及 `android/`）采用 **Apache-2.0**，见根目录 `LICENSE`。
