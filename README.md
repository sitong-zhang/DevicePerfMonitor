# DevicePerfMonitor

一个设备性能监控 App，Android 和 iOS 双端。核心是三块：全局悬浮窗看实时数据、硬件指标全量采集、本地健康分分析。全程不联网，数据只存本地。

两个端的界面是同一套设计：`design-tokens.json` 是唯一真源，色板用 Catppuccin Mocha/Latte，Android 端液态玻璃材质来自 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，iOS 端用 `UIVisualEffectView` 加图层叠加实现同样的质感。

几条从一开始就定下的规矩：

- 采不到的数据就显示"不可用"并说明原因，不拿估算值冒充
- 液态玻璃是真模糊加高光阴影叠出来的，不是半透明纯色
- 不用系统默认控件和默认蓝，按钮卡片开关滑块全部自己封装
- 不申请 INTERNET 权限，没有任何联网代码路径
- 平台做不到的功能，直接说做不到，降级方案写进文档

## 版本

当前 **1.0.2**。安装包在 [Releases](https://github.com/sitong-zhang/DevicePerfMonitor/releases) 页面，仓库里不存二进制。

发版要改的地方就这几处，漏一处两边就对不上：

| 位置 | 字段 |
| --- | --- |
| `android/app/build.gradle.kts` | `versionCode` / `versionName` |
| `ios/DevicePerfMonitor.xcodeproj/project.pbxproj` | `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`，各 4 处 |
| `ios/Tools/generate_xcodeproj.py` | 同样两个字段，各 2 处；重跑生成脚本时靠它写进 pbxproj |
| `README.md` | 本节版本号，和构建小节里的包体积 |
| Release 资产名 | `DevicePerfMonitor-<版本>-release.apk` / `.ipa` |

### 更新记录

**1.0.2**：修掉 Android 10/11/12 上打开即闪退的真正原因——液态玻璃的实时模糊走 `android.graphics.RuntimeShader`（AGSL），这个类是 **API 33** 才有的，低版本上绘制期直接抛 `NoClassDefFoundError`。现在低于 Android 13 自动切到降级渲染（真实半透明 + 自绘高光/描边/内阴影/噪点，不是假模糊）。另外崩溃监测改成"先落盘、下次启动必弹"：Android 10 起系统限制后台启动 Activity，崩溃当下弹的窗可能被静默拦掉，所以报告会写进文件，下次进主界面（前台）一定显示，并附上一次启动的逐步轨迹，用来定位"死在哪一步"。

**1.0.1**：修掉内存占用率算出来是 `Double`、泛型擦除后被当成 `Float` 送进 UI 导致的 `ClassCastException`。顺手给 `MetricsRepository` 的取值函数加了实际值类型校验，以后再有采集器写错类型只会降级显示，不会崩。

**1.0.0**：首个版本。

## 功能

**全局悬浮窗**（Android 系统级，iOS 应用内）

单行显示 FPS、CPU 温度、内存占用率，单击展开 6 项面板，双击隐藏（250ms 判定），双指缩放 0.8x~1.6x，长按拖动，靠边 96dp 内自动吸附，拖到底部垃圾桶松手关闭。窗口外触摸穿透到下层应用。FPS 500ms 刷新一次，温度 1s，数字用等宽字体，跳动时不晃。数值动画从当前值接着缓动，不重播。

**硬件采集**

显示、CPU、GPU、电池、内存、设备温度六大类。每个指标每次采样都有明确状态：可用 / 不可用（带原因）/ 需要授权。30 分钟秒级历史存本地，信号过 3σ 加中位数滤波。前台服务保活，App 退了后台也能继续采。

**主应用与本地分析**

首页是健康分大卡片加四张指标卡片；详情页有 1/5/30 分钟图表和峰值；设置页管采集频率、悬浮窗开关、主题和告警阈值。

健康分公式（权重和分段点都在 `design-tokens.json` 里，双端共用）：

```
总分 = 温度分 × 0.4 + 帧率稳定性分 × 0.3 + 电池分 × 0.2 + 内存分 × 0.1
```

温度分取 CPU 和电池温度的较大值，40℃ 以下满分，40~70℃ 线性降到 10，超过 70 归零。帧率稳定性优先用全局掉帧率算，没有全局 FPS 时降级用本 App 帧时间标准差，但会标成"参考值"。哪个维度不可用，它的权重按比例分给其他维度；全不可用就显示"数据不足"，不编分数。

**崩溃监测**（双端）

任何没被 catch 的异常都会弹出全屏报告：异常类型、完整调用栈、机型系统版本、全部线程栈，带复制按钮。Android 侧在独立的 `:crash` 进程里用纯 View 画报告，绕开主界面的 Compose 渲染，不会陷入"崩了又崩"；iOS 侧 NSException 实时弹 UIKit 窗口，信号类崩溃写文件、下次启动展示。代码在 `android/.../CrashHandler.kt`、`CrashActivity.kt`、`BootTrace.kt` 和 `ios/.../CrashReporter.swift`。

有一点要说明：Android 10 起系统限制了"后台启动 Activity"，如果崩溃发生在还没有可见界面的时候，当场弹的窗会被静默拦掉。所以 Android 侧是双保险——报告先写进 `filesDir`，下次启动进 `MainActivity`（前台，允许弹窗）时一定补展示，并附带上次启动的逐步轨迹（最后一行就是卡住/崩溃的位置）。这样即使崩溃发生在监测器安装之前、或者是 Java 层捕获不到的 native 崩溃，也能看到"死在哪一步"。

## 构建

### Android

```bash
cd android
./build-apk.sh
```

脚本会检查 JDK 17+ 和 Android SDK（`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`local.properties` 任选其一），构建完自动打印签名证书指纹。加 `--debug` 参数出 debug 包。

要发布签名就复制 `android/keystore.properties.template` 为 `keystore.properties`，按模板里的 keytool 命令生成密钥再填进去。这个文件已被 gitignore，不会入库。没配的话 release 包会自动用 debug 签名，构建日志里会说明，这是有意的降级。

已在 Linux 环境验证通过：`:app:assembleDebug`、`:app:assembleRelease`、`:app:lintVitalRelease` 全部成功，release APK 约 3.3 MB。`releases/` 目录下会放打好的包，可以直接装，但这个目录不入库，正式分发走 GitHub Release。

### iOS

用 Xcode 打开 `ios/DevicePerfMonitor.xcodeproj`，选 `DevicePerfMonitor` scheme 跑起来。首次打开会去拉 Charts（danielgindi/Charts）的 SPM 依赖，需要联网。

要求 Xcode 15+，部署目标 iOS 17.0（用到了 iOS 17 的 API：`navigationDestination(item:)`、`.numericText` 等）。这个工程是 `ios/Tools/generate_xcodeproj.py` 生成的，UUID 确定性生成，重复生成不会漂移，不建议手改 pbxproj。

**iOS 安装包（IPA）**：交付环境是 Linux，没有 Xcode，本地打不出 IPA。仓库里放了 `.github/workflows/ios.yml`，push 代码后 GitHub 的 macOS runner 会自动构建 IPA 并上传为 artifact。这个流程产物是未签名的，要装到手机上需要自己重签名（或者有开发者账号的话配好证书 secret，workflow 里有开关）。真机上 Live Activity 和应用内悬浮窗的完整效果建议在真机验证，模拟器行为不一致。

## 目录结构

```
├── design-tokens.json     # 双端唯一真源：色板/玻璃参数/动效/健康分公式
├── Tools/check_tokens.py  # 校验双端生成产物一致
├── licenses/              # Inter 与 JetBrains Mono 的 OFL 许可证
├── releases/              # 打好的 APK / IPA（本地副本，gitignore，不在仓库里）
├── android/
│   ├── buildSrc/          # design-tokens 代码生成任务（编译型，兼容配置缓存）
│   ├── build-apk.sh       # 一键打包
│   └── app/src/main/java/com/devperf/monitor/
│       ├── core/          # 指标定义与三态模型
│       ├── data/          # 采集器、特权通道（root/Shizuku）、本地存储与滤波
│       ├── analysis/      # 健康分、建议、告警
│       ├── overlay/       # 悬浮窗服务、手势、垃圾桶
│       ├── ui/kit/        # 自封装液态玻璃组件
│       └── ui/            # 首页、详情、设置、主题
└── ios/
    ├── Tools/             # pbxproj 生成与校验脚本
    ├── DevicePerfMonitor/
    │   ├── Collection/    # 采集器与三态模型（与 Android 对齐）
    │   ├── Overlay/       # 应用内悬浮窗
    │   ├── LiveActivity/  # 实时活动（App 与 Widget 共享）
    │   ├── Charts/        # Charts 图表封装
    │   ├── Theme/         # 液态玻璃主题与生成的 tokens
    │   └── Screens/       # 首页、详情、设置
    └── DevicePerfMonitorWidget/  # Widget 扩展（灵动岛/锁屏）
```

## 依赖与版本

Android 的版本统一在 `android/gradle/libs.versions.toml`，iOS 的 Charts 走 SPM。

| 依赖 | 版本 | 协议 | 说明 |
| --- | --- | --- | --- |
| AGP | 8.13.2 | Apache-2.0 | 没用 AGP 9，它不再支持 kotlin.android 插件组合 |
| Kotlin | 2.4.21 | Apache-2.0 | |
| Gradle | 8.14.6 | Apache-2.0 | wrapper 锁定 |
| core-ktx | 1.15.0 | Apache-2.0 | 1.16+ 要 AGP 9.1 |
| activity-compose | 1.12.4 | Apache-2.0 | minAGP 8.9.1 |
| lifecycle | 2.9.4 | Apache-2.0 | |
| navigation-compose | 2.9.4 | Apache-2.0 | 2.10.x 硬依赖 lifecycle 2.11.0，要 AGP 9.1，所以不升 |
| compose-bom | 2026.06.01 | Apache-2.0 | compose 1.11.4 + material3 1.4.0 |
| datastore-preferences | 1.3.0 | Apache-2.0 | 本地存储 |
| backdrop | 2.0.0-alpha03 | Apache-2.0 | 液态玻璃材质。2.0.0 正式版 AAR 要 compileSdk 37（AGP 9.1），alpha03 只要 36，API 一样，所以锁 alpha03。这个库只有材质，没有高级组件，组件全部自封装 |
| Vico | 3.2.3 | Apache-2.0 | Compose 图表。3.3.x 要 compileSdk 37 |
| Shizuku | 13.1.5 | Apache-2.0 | 免 root 提权通道，可选。13.1.5 起 `Shizuku.newProcess` 私有化了，改成走公开 AIDL `IShizukuService.Stub.asInterface(...)` |
| Charts (iOS) | 5.0.0+ | Apache-2.0 | iOS 图表，对应 Android 的 Vico。注意：这个库 4.0 起把包名和 SPM 产品名改成了 `DGCharts`，所以 import 和 pbxproj 里的 productName 都用 DGCharts |

## 采集指标对照表

三态：**A** 可用，**NP** 授权后可用，**U** 平台没有公开途径。不可用时 UI 一律显示"— 不可用"加原因，不做估算。

### Android

| 指标 | 数据来源 | 权限 | 状态 | 降级路径 |
| --- | --- | --- | --- | --- |
| FPS / 帧时间 / 帧时间标准差 / 掉帧 | Choreographer 帧回调，500ms 窗口 | 无 | A | 超过 2s 无帧判定"当前无持续绘制"→ U，不拿旧值冒充 |
| 屏幕刷新率 | DisplayManager | 无 | A | |
| 全局 FPS | `dumpsys gfxinfo <前台包名>` | root 或 Shizuku；前台包名另需使用情况访问权 | NP | 都没有 → U；部分 ROM 限制 shell 读 gfxinfo → U |
| CPU 使用率 / 各核心 | `/proc/stat` 差值 | 无 | A | ROM SELinux 拒绝 → U |
| 各核心频率 | `/sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq` | root 或 Shizuku | NP | 不可读 → U |
| CPU 温度 | `/sys/class/thermal/thermal_zoneN` 按类型匹配取最大值 | 无（多数机型可读） | A（看机型） | 匹配不到 CPU 温区 → U，不拿电池温度冒充 |
| GPU 负载 / 频率 / 温度 | Adreno 读 kgsl 节点，Mali 读 mali0 节点 | root 或 Shizuku | NP | 不可读 → U，不拿 CPU 负载估算 |
| 电池温度 / 电压 / 电量 / 充电状态 | ACTION_BATTERY_CHANGED | 无 | A | 广播失败 → U |
| 电池电流 | BatteryManager 实时属性 | 无 | A（看机型） | 读不到 → U |
| 电池健康（粗粒度） | EXTRA_HEALTH | 无 | A | 只有 GOOD/OVERHEAT 这类档位 |
| 电池健康（百分比） | 无公开接口 | | U | Android 读不到设计容量和循环次数，常态不可用 |
| 总 / 已用 / 可用内存 / 占用率 | ActivityManager.MemoryInfo | 无 | A | |
| 后台应用内存 | `dumpsys meminfo` | root 或 Shizuku | NP | 普通 App 从 Android 8 起读不了其他进程内存 |
| 系统热状态 | PowerManager.getCurrentThermalStatus | 无 | A | API 29 以下 → U |
| 热余量 | getThermalHeadroom(0)，返回 0~1 | 无 | A（看机型） | 返回 NaN → U |
| 机身温度 | 全部可读 thermal zone 最大值 | 无 | A（看机型） | 不可读 → U。温区命名各 ROM 不统一，数值口径有机型差异 |

### iOS

| 指标 | 数据来源 | 权限 | 状态 | 降级路径 |
| --- | --- | --- | --- | --- |
| FPS / 帧时间 / 帧时间标准差 / 掉帧 | CADisplayLink | 无 | A | 超过 2s 无帧 → U |
| 屏幕刷新率 | UIScreen.maximumFramesPerSecond | 无 | A | |
| 全局 FPS | 无公开 API | | U | iOS 读不到其他进程的渲染帧 |
| CPU 使用率 / 各核心 | host_processor_info 差分 | 无 | A | 首次采样没基线 → U，等第二次 |
| 各核心频率 | 无公开 API，沙箱读不了 sysfs | | U | |
| CPU 温度 | 无公开 API | | U | |
| GPU 负载 / 频率 / 温度 | 无公开 API | | U | |
| 电量 / 充电状态 | UIDevice.batteryLevel / batteryState | 无 | A | |
| 电池温度 / 电流 / 电压 | 无公开 API（iOS 无 IOKit） | | U | |
| 电池健康（百分比） | 无公开 API | | U | |
| 总 / 已用 / 可用内存 / 占用率 | host_statistics64 + ProcessInfo.physicalMemory | 无 | A | 口径与 Android 一致：可用 = (free + inactive) × 页大小 |
| 后台应用内存 | 无公开 API | | U | iOS 沙箱比 Android 更严 |
| 系统热状态 | ProcessInfo.thermalState | 无 | A | 只有 nominal/fair/serious/critical 四档 |
| 机身温度（℃） | 无传感器直读 API | | U | 不拿热状态档位换算冒充温度值 |
| 热余量 | 无对应公开接口 | | U | |

## 权限

Android 在 `AndroidManifest.xml` 里逐条声明：

| 权限 | 用途 | 必需 | 拒绝后 |
| --- | --- | --- | --- |
| SYSTEM_ALERT_WINDOW | 全局悬浮窗 | 是 | 引导去系统设置页授权，还拒绝就隐藏悬浮窗入口 |
| FOREGROUND_SERVICE + FOREGROUND_SERVICE_SPECIAL_USE | 采集和悬浮窗保活，Android 14+ 要声明类型 | 是 | 服务起不来 |
| POST_NOTIFICATIONS | 告警和常驻通知 | 运行时申请 | 只是没通知，采集不受影响 |
| RECEIVE_BOOT_COMPLETED | 开机后恢复悬浮窗 | 可选 | 不自动恢复，手动开 |
| PACKAGE_USAGE_STATS | 全局 FPS 拿前台包名 | 可选 | 相关指标不可用，可改走 Shizuku |
| moe.shizuku.manager.permission.API_V23 | Shizuku 免 root 提权 | 可选 | 无特权指标照常工作 |

`INTERNET` 明确不申请，全程离线。

iOS 侧没有运行时权限，采集全走公开 API。Live Activity 靠 `NSSupportsLiveActivity` 声明，数据经 ContentState 下发，不需要 App Group（两个 entitlements 是空的，文件里有注释说明原因）。

## 已知限制

这些是平台限制，做不了，都按降级方案落了：

**iOS**

1. 没有系统级全局悬浮窗，iOS 不允许第三方应用悬浮在其他 App 上面。改成应用内 `UIWindow` 悬浮窗，手势、收窄、垃圾桶这些交互都按 Android 的规格做了，App 退后台自动隐藏。
2. 全局 FPS、GPU 负载/频率/温度、CPU 温度、机身温度、电池电流/电压/温度、电池健康百分比、后台应用内存：iOS 没有公开 API，常态显示不可用。帧率稳定性维度因此降级用帧时间标准差。
3. 本地打不了 IPA（没有 Xcode），用 GitHub Actions 的 macOS runner 构建，产物未签名，装机要重签名。
4. `UIVisualEffectView` 不给精确指定模糊半径，只能选系统预设材质。玻璃效果靠预设模糊加自绘高光、内外阴影、弥散外阴影和噪点叠出来，参数对齐 token，视觉上跟 Android 一致，但不是同一个 API 路径。

**Android**

1. 电池健康百分比没有公开接口，只展示 EXTRA_HEALTH 的粗粒度档位。
2. 后台应用内存、各核心频率、GPU 节点要 root 或 Shizuku。普通 App 读不了，这是 Android 8 之后的沙箱规定。
3. Shizuku 13.1.5 把 `newProcess` 私有化了，改走公开 AIDL。
4. **Android 12（API 32）及以下没有真玻璃**：backdrop 的模糊走 `RuntimeShader`（AGSL），这是 API 33 才有的类，低版本上调用会在绘制期直接崩。所以低于 Android 13 一律走降级渲染——真实半透明底 + 自绘高光渐变 + 描边 + 内阴影 + 噪点，观感接近但不是真实背景模糊。判定在 `ui/kit/LiquidGlass.kt` 的 `supportsLiquidGlass`。

## design-tokens.json

色板、圆角、间距、玻璃参数（模糊 28~36、背景透明度 18%~28%、边框高光阴影饱和度噪点）、三条动效曲线（enter/exit/emphasized）、数值缓动规则、健康分权重和分段点，全在这个文件里。双端各自生成代码：

- Android：`buildSrc/GenerateDesignTokensTask.kt` → `ui/theme/DesignTokens.kt`
- iOS：`ios/Tools/generate_tokens.py` → `Theme/DesignTokens.generated.swift`

生成的文件不要手改。业务代码里不许出现硬编码色值和权重，`Tools/check_tokens.py` 能查出来。

Android 侧生成逻辑放在 buildSrc 的编译型 Task 而不是 `doLast` 闭包，因为闭包没法序列化，会让 Gradle 配置缓存每次失效。

字体（Inter 可变字体 + JetBrains Mono）随工程打包，许可证在 `licenses/`（OFL-1.1），双端文件 md5 一致。iOS 上 Inter 的 wght 轴（100~900）要走 CoreText 的 `kCTFontVariationAttribute` 取，SwiftUI 的 `Font.custom` 不支持可变轴。

## 校验

```bash
python3 Tools/check_tokens.py              # 双端 token 与字体一致性
python3 ios/Tools/check_plist_syntax.py    # pbxproj 的 old-style plist 全量语法解析
python3 ios/Tools/validate_pbxproj.py      # pbxproj 引用完整性、磁盘一致性、BuildFile 引用类型
python3 ios/Tools/check_swift_syntax.py    # Swift 括号配对与 import 汇总
```

Xcode 的 plist 解析器遇到第一个语法错误就中止，只报一个行号，所以本地用 `check_plist_syntax.py`
做全量解析一次报全；`validate_pbxproj.py` 管语义层，比如 PBXBuildFile 的 fileRef 必须指向
PBXReference，SPM 包产品得用 productRef。这几个脚本是给没有 macOS 的环境兜底用的，
真正常驻校验还是 `check_tokens.py`。

## License

Apache-2.0，见 `LICENSE`。字体是 OFL-1.1（`licenses/`）。Catppuccin 调色方案 MIT，只取了色值没分发代码。第三方库协议见上面的依赖表。
