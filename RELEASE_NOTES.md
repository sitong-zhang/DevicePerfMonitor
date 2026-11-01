# PulseGuard 安卓版 v1.0.53 更新内容

**本次更新：弹窗一次性封装为上游 Kyant0/AndroidLiquidGlass 的同窗口叠层范式，按钮全部改为液态玻璃（LiquidButton）。**

## 一、弹窗改为同窗口叠层（不再开独立 Dialog 窗口）

此前 `LiquidDialog` 用的是 Compose `Dialog()` 独立窗口。本次按上游 catalog 的 `DialogContent` 范式**一次性封装**为同一窗口内的叠层：

- 整屏压一层暗化层（`dimColor`），点罩即等价「取消」（仅可关闭弹窗生效）；
- 中心放一张 `drawBackdrop` 玻璃卡，**采样应用级 backdrop**，呈现真液态折射与高光，而非简单的半透明面板；
- 玻璃卡参数与上游 1:1 对齐：`RoundedRectangle(48dp)` + `colorControls(brightness 0.2 / saturation 1.5)` + `blur(16dp / 浅，8dp / 深)` + `lens(24dp, 48dp, depthEffect)` + `Highlight.Plain` + 容器色表面；
- 无玻璃环境（悬浮窗等）回落实底面板，布局与交互不变。

## 二、按钮全部改为液态玻璃（LiquidButton）

弹窗的「取消 / 确认 / 安装 / 重试」等按钮改为 `GlassButton`（内部即上游 `LiquidButton`，Capsule + vibrancy + blur + lens + 按压高光），与玻璃卡风格统一；原本自绘的胶囊按钮已移除。

## 三、影响范围

- 更新提示弹窗、公告弹窗、密码保存提示、退出登录确认、安全中心「修改密码」弹窗等全部走新范式；
- 与用户端账号体系（1.0.52 的账号中心 / 安全中心）无缝衔接，弹窗玻璃纹样随当前页面 backdrop 实时变化。

## 四、其它

- 版本号升至 1.0.53，沿用固定长期密钥签名，覆盖安装无需卸载。
- 升级后应用内「检查更新」即可收到本版本通知并下载。
