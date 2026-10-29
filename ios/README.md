# 观序 · 苹果版（iOS）

与安卓版同功能的 iOS 性能监测 App：CPU、内存、电池、帧率、屏幕刷新率、存储实时采集，综合健康分，历史曲线，应用内悬浮 HUD，本地账号（注册 / 登录 / 登出），GitHub 更新 / 公告检查。

> 本目录只放源码。**未签名 IPA 由仓库的 CI（macOS runner）自动构建并发布到 `ios-unsigned` Release**，见下方说明。

## 与安卓版的差异（iOS 平台限制）

| 能力 | 安卓版 | 苹果版 |
|---|---|---|
| 设备温度 | 可读取 | **iOS 不暴露温度 API，已移除该指标**，评分改为「流畅度 / 内存 / 电量」 |
| 全局悬浮窗 | 支持（SYSTEM_ALERT_WINDOW） | **iOS 不允许全局悬浮窗**。提供「应用内悬浮 HUD」替代 |
| 账号 | 本地账号（注册 / 登录） | 本地账号（注册 / 登录），数据仅存本机 |
| 安装方式 | 直接装 APK | 需用你的 **Apple ID** 走免费开发者证书签名后安装（见下） |

## 未签名 IPA（自动构建）

本仓库 `.github/workflows/build-ios.yml` 会在 macOS runner 上：

1. 用 [XcodeGen](https://github.com/yonaskolb/XcodeGen) 根据 `ios/project.yml` 生成 Xcode 工程；
2. `xcodebuild` 以 **免签名**（CODE_SIGNING_ALLOWED=NO）方式编译 `GuanXu` 目标；
3. 把 `GuanXu.app` 打包为 `GuanXu-unsigned.ipa`；
4. 上传到仓库的 **`ios-unsigned` Release**。

触发方式：在仓库 Actions 页手动 `Run workflow`，或推送 `ios-v*` 标签。

> 你本地想自己编也行：在 Mac 上 `cd ios && brew install xcodegen && xcodegen generate`，用 Xcode 打开 `GuanXu.xcodeproj`，选自己的 Team 后 Archive / 运行。

## 签名安装到 iPhone（Apple ID 账户）

观序 苹果版不能像安卓那样直接装，必须经过签名。仓库内的 `../sign-tool/` 就是干这个的（它本身是 iOS App，需要先借助 AltStore / SideStore 装进手机）：

1. CI 已经把 **未签名 IPA**（`GuanXu-unsigned.ipa`）发布在 `ios-unsigned` Release。
2. 打开签名工具，填入你自己的 **Apple ID 账号和密码**（仅用于向苹果申请临时证书），点「开始安装」。
3. 工具会自动从 `ios-unsigned` Release 拉取 `GuanXu-unsigned.ipa`，用你的 Apple ID 签名并无线安装到这台 iPhone。

> ⚠️ 免费 Apple ID 申请的开发证书 **7 天** 有效，到期后在签名工具里重新登录 Apple ID 续签、再次安装即可。

## 版本

当前源码版本 `1.0.0`。更新检测读取 GitHub Release 的 `latest`；公告读取仓库内 `android/app/src/main/assets/announcement.json`（复用安卓版的那份）。
