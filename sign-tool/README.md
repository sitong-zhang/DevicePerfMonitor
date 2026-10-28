# Brand · 自签安装（观序苹果版专用签名工具）

一套开源自签安装工具：在 iPhone 上用你自己的 Apple ID，即可把应用安装到本机，无需越狱、无需企业证书。

## 它能做什么

- 自动从指定的 GitHub Release 拉取未签名的 IPA；
- 用使用者自己的 Apple ID 向苹果申请临时开发证书；
- 对 IPA 进行签名，并通过本机服务无线安装到 iPhone。

界面极简：Apple ID 账号、密码、一个「开始安装」按钮与进度条。

## 产品约束（Brand 只做这一件事）

- 签名完成后自动上报一条**签名回执**到管理端（仅含时间 / 机型 / 系统版本 / Brand 版本 / 结果，**绝不含** Apple ID、密码、证书或设备唯一标识）；未配置回执通道（`BRAND_REPORT_TOKEN` secret）时降级为本地存档；
- 上报后进入**休眠锁定态并自动关闭**；再次打开仅提供「重新签名（续签）」入口（免费证书 7 天到期后续签用）；
- 管理端 App（仓库 `manager` Release）可查看全部签名记录。

## 构建（需要 macOS + Xcode 16+）

直接用 Xcode 打开 `Feather.xcworkspace` 编译，再借助 AltStore / SideStore 之类把 App 自身装进手机即可。

本目录已内联 `Zsign`、`IDeviceKitten` 等依赖，**无需再初始化子模块**。

## 目录说明

- `Feather/`：主工程、界面与安装流程编排；
- `Feather/Installer/`：下载、登录、申请证书、签名、安装的状态机与底层模块；
- `Zsign/`、`IDeviceKitten/`：随附的签名与设备通信依赖（已内联）；
- `AltSourceKit/`、`NimbleKit/`：工程依赖。

## 协议

本目录整体沿用上游 GPL-3.0，见 `LICENSE`。署名与版权信息保留在对应源码文件头中。

> 提示：免费 Apple ID 申请的开发证书有效期为 7 天，到期需重新签名安装。
