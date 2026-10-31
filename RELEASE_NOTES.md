# PulseGuard 安卓版 v1.0.51 更新内容

**本次更新：把上游 Kyant0/AndroidLiquidGlass 的「全部组件与交互范式」正式做进用户端；弹窗统一升级为真液态玻璃；总览页从清一色卡片改为多形态布局。**

## 一、上游组件全量落地（不再只存在于独立组件库 App）

玻璃实验室页新增上游 demo 的五个「体验级」页面，每一种都代表一种完整交互范式，并补齐了此前未接入的上游工具：

- **放大镜**（Magnifier）：`rememberCombinedBackdrop` 三层背景叠加 + `onDrawBackdrop` 放大重采样 + 透镜色散，玻璃透镜折射其后文字与背景。
- **控制中心**（ControlCenter）：`UISensor` 重力传感器驱动高光角度，垂直拖拽经 `ProgressConverter` 控进场/退场进度，整组玻璃块联动缩放位移。
- **SDF 时钟锁屏**（LockScreen）：`SdfShader`（AGSL 有向距离场）沿 SDF 贴图折射，可拖动。
- **渐进模糊**（ProgressiveBlur）：`runtimeShaderEffect` AGSL alpha 蒙版，越往下越模糊的渐变玻璃。
- **玻璃游乐园**（GlassPlayground）：双指拖拽缩放旋转 + `exportedBackdrop` 玻璃嵌玻璃（面板内滑块采样面板自身）。

同时把上游此前缺的库文件 vendor 进用户端：`Ripple`、`Block`、`SdfShader`、`UISensor`、`ProgressConverter` 以及 `clock_sdf.webp` 资源。至此上游的成品组件（LiquidButton / LiquidToggle / LiquidSlider / LiquidBottomTabs）、所有 effect、Highlight 三样式、Shadow / InnerShadow、四类 Backdrop 创建方式、交互工具（InteractiveHighlight / DampedDragAnimation）全部在用户端可用并演示。

## 二、弹窗统一为真液态玻璃

新增统一的 `LiquidDialog` 玻璃弹窗，替代原来的 Material `AlertDialog`：

- **更新提示弹窗**：升级为液态玻璃卡 + 胶囊双按钮。
- **公告弹窗**：保留「滑到最底才可确认」，外壳改为玻璃质感。
- **「请务必记好您的密码」弹窗**：重排为标准玻璃形态。
- **退出登录确认**：新增玻璃确认弹窗（此前无二次确认）。

全部弹窗在采样应用级 backdrop 的窗口下呈现真 `drawBackdrop` 液态折射与高光。

## 三、总览页多形态重排（不再是清一色卡片）

- **综合评判 Hero**：在环形评分下方新增「流畅 / 温度 / 内存」三维度档位条，一眼看短板。
- **首个核心指标 → 全宽「特性卡」**：大数字 + 面积波形 + 低/高值标注，视觉权重最高。
- **其余核心指标 → 半宽小卡，按指标类型分配专属形态**：内存用环形仪表、温度用分段电平条、刷新率用柱状分布、其余用波形线——同屏四种形态混排，不再单调。

## 四、其它

- 版本号升至 1.0.51，沿用固定长期密钥签名，覆盖安装无需卸载。
- 升级后应用内「检查更新」即可收到本版本通知并下载。
