# 见微 · 苹果版（iOS）

与安卓版同功能的 iOS 性能监测 App：CPU、内存、电池、帧率、屏幕刷新率、存储实时采集，综合健康分，历史曲线，应用内悬浮 HUD，本地存储，GitHub 更新 / 公告检查。

> 本目录只放源码。**iOS App 必须在 macOS + Xcode 上编译**，本仓库的 Linux 构建环境无法产出 IPA。

## 与安卓版的差异（iOS 平台限制）

| 能力 | 安卓版 | 苹果版 |
|---|---|---|
| 设备温度 | 可读取 | **iOS 不暴露温度 API，已移除该指标**，评分改为「流畅度 / 内存 / 电量」 |
| 全局悬浮窗 | 支持（SYSTEM_ALERT_WINDOW） | **iOS 不允许全局悬浮窗**。提供「应用内悬浮 HUD」+ 可选「桌面 / 锁屏小组件」替代 |
| 安装方式 | 直接装 APK | 需用你的 **Apple ID** 走免费开发者证书签名后安装（见下） |

## 构建步骤（在 Mac 上）

1. 用 Xcode 新建一个 **iOS App** 项目，Product Name 填 `JianWei`，Bundle Identifier 填你自己的反域名（如 `com.你的名.jianwei`）。
2. 把本目录 `JianWei/` 下的 `App / Core / Models / ViewModels / Views` 以及 `Resources/Info.plist` 拖进工程（保留文件夹结构）。
3. 打开 `Signing & Capabilities`，Team 选你登录的 **Apple ID**（免费账号即可），勾选 Automatically manage signing。
4. 连接 iPhone，选真机设备，`⌘R` 运行；或 `Product → Archive` 后导出 **未签名 / 开发签名** 的 IPA（不要选发布分发）。

### 可选：小组件（常驻展示）

`Widget/` 下是 WidgetKit 扩展源码。在 Xcode 里 `File → New → Target → Widget Extension` 加入工程，把 `Widget/` 两个文件放进去；若要与主 App 共享数据，开启相同的 App Group（`group.com.molecule.jianwei`）。

## 签名安装到 iPhone（Apple ID 账户）

见微 苹果版不能像安卓那样直接装，必须经过签名。仓库内的 `../sign-tool/` 就是干这个的：

1. 把上面导出的 **未签名 IPA** 命名为 `JianWei-<版本号>.ipa`，上传到本仓库的 **Release**（建议最新版）。
2. 在 Mac/Xcode 编译并安装 `sign-tool/`（即「见微 苹果版」的签名工具本体），首次需借助 AltStore / SideStore 把它装进手机。
3. 打开签名工具，填入你自己的 **Apple ID 账号和密码**（仅用于向苹果申请临时证书，不上传第三方），点「开始安装」。
4. 工具会自动从本仓库最新 Release 拉取 `JianWei-*.ipa`，用你的 Apple ID 签名并无线安装到这台 iPhone。

> ⚠️ 免费 Apple ID 申请的开发证书 **7 天** 有效，到期后在签名工具里重新登录 Apple ID 续签、再次安装即可。

## 版本

当前源码版本 `1.0.0`。更新检测读取 GitHub Release 的 `latest`；公告读取仓库内 `android/app/src/main/assets/announcement.json`（可复用安卓版的那份）。
