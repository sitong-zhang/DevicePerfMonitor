# DevicePerfMonitor

一个设备性能监控 App，Android 端为主（iOS 安装包自 1.0.14 起已下架，源码保留）。核心是三块：全局悬浮窗看实时数据、四个核心硬件指标实时采集、本地综合评判。全程不联网，数据只存本地——唯一的联网路径是应用内版本检查（1.0.15 起），仅读取公开的 GitHub Releases 元数据，不会向任何服务器上报任何数据。

两个端的界面是同一套设计：`design-tokens.json` 是唯一真源。色板由 Google 官方算法生成——种子色取 Catppuccin Mauve `#CBA6F7`，走 Material 3 Expressive（`SchemeExpressive` + SPEC_2025），深/浅各一套，共 24 个语义色（含 surface 层次、描边、tertiary）。生成工具在 `Tools/palette-gen/`，**构建期/离线跑一次，色值固化进 design-tokens.json，运行时零依赖**。

**视觉方案分端演进**：iOS 端用开源库 [LiquidGlassKit](https://github.com/muhittincamdali/LiquidGlassKit)（MIT，SPM 依赖）承载液态玻璃，iOS 26 上自动走苹果原生 `glassEffect`；Android 端 1.0.8 起换用 Material 3 Expressive（Compose Material3 稳定组件 + 官方同参数弹簧），1.0.13 起引入开源液态玻璃库 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（backdrop 2.0.0-alpha03，Apache-2.0）——1.0.16 起合并为单一发布包，不再提供「无玻璃」版本——两端各自使用平台最地道的表达，共享同一份色板与动效 token。

几条从一开始就定下的规矩：

- 采不到的数据就显示"不可用"并说明原因，不拿估算值冒充
- 液态玻璃（iOS）走开源库 LiquidGlassKit 的真实材质：iOS 26 上是原生 `glassEffect`（折射 + 高光 + 交互形变），低版本是库的纯 SwiftUI fallback，不是半透明纯色
- Android 用 Material 3 Expressive：官方稳定组件（SegmentedButton / Card / Surface）+ 与 androidx `ExpressiveMotionTokens` 同参数的弹簧，不引 internal API；1.0.13 起在其之上叠开源液态玻璃库 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（backdrop 2.0.0-alpha03，Apache-2.0）的真实折射（vibrancy + blur + lens）；1.0.16 起这是唯一发布版本（原 glass / plain 双包已合并）
- 视觉参数不写死：层次、描边、投影、底栏几何全部来自 design-tokens 的 `surface` / `elevation` / `bottomBar` 三段
- 不用系统默认控件和默认蓝，按钮卡片开关滑块全部自己封装
- 不申请 INTERNET 权限——**例外**：1.0.15 起为应用内版本检查申请了 `INTERNET`，但仅读取公开的 GitHub Releases 元数据，不向任何服务器上报数据；采集/分析/历史仍 100% 本地
- 平台做不到的功能，直接说做不到，降级方案写进文档

## 版本

当前 **1.0.17**。安装包（Android 液态玻璃版，单一发布包）在 [Releases](https://github.com/sitong-zhang/DevicePerfMonitor/releases) 页面，仓库里不存二进制。iOS 安装包自 1.0.14 起已下架；「无玻璃」的 `-plain` 包自 1.0.16 起停产。

发版要改的地方就这几处，漏一处两边就对不上：

| 位置 | 字段 |
| --- | --- |
| `android/app/build.gradle.kts` | `versionCode` / `versionName` |
| `ios/DevicePerfMonitor.xcodeproj/project.pbxproj` | `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`，各 4 处 |
| `ios/Tools/generate_xcodeproj.py` | 同样两个字段，各 2 处；重跑生成脚本时靠它写进 pbxproj |
| `ios/DevicePerfMonitor/Info.plist`、`ios/DevicePerfMonitorWidget/Info.plist` | 两个版本键必须是 `$(MARKETING_VERSION)` / `$(CURRENT_PROJECT_VERSION)` 变量引用。工程是 `GENERATE_INFOPLIST_FILE = NO`，写死数字的话上面改的版本号不会进包 |
| `README.md` | 本节版本号，和构建小节里的包体积 |
| Release 资产名 | `DevicePerfMonitor-<版本>-release.apk` / `.ipa` |

### 更新记录

**1.0.14**：修复液态玻璃版真机闪退（挂载结构），修复详情页/总览页「显示靠上、柱状/波状不显示」（滚动容器无界高度约束），修复「回看」窗口不记忆。**iOS 安装包自本版起下架，仅发 Android 双包。**

**1.0.17**：悬浮窗那一格由「实时帧率」改为显示**手机屏幕刷新率**（用户要求）。

- **悬浮窗显示屏幕刷新率**（用户要求：把悬浮窗的实时帧率换成手机屏幕刷新率）：卡片态与纯数字态的第三行，原先挂的是 `CoreMetricId.FPS`（全局帧率，需 Shizuku/root，否则整行退化成「需 Shizuku」引导），现在换成 `CoreMetricId.REFRESH_RATE`，数据源 `OverlayData.refreshHz`（= `snapshot.display.refreshRateHz`）——这是屏幕本身的刷新率，不需要任何授权就一定读得到，因此这行从此不会再出现"读不到"的引导。1.0.9 起曾刻意让刷新率只留在应用内、不上悬浮窗，现在按本次要求反过来。帧率本身没有删：它仍参与综合评判的流畅度计算（按 `fps ÷ refreshHz` 算达成率），只是不再单独占悬浮窗一行。

**1.0.16**：**只保留液态玻璃版**——删除「无玻璃」的 plain 变体与双 flavor，以后只发布这一个版本。

- **单一发布包**（用户要求：只要液态玻璃版，另一版删掉，以后也只发这个）：1.0.13~1.0.15 用 `productFlavors`（`glass` / `plain`）出两个 APK，1.0.16 起删除 flavor，产物固定 `DevicePerfMonitor-<版本>-release.apk`。`BuildConfig.LIQUID_GLASS` 保留并恒为 `true`（避免动到各 UI 组件的分支），原本 glass 专用的 R8 keep 规则合入共享 `proguard-rules.pro`。连带改动：CI 的产物路径要从 `apk/<flavor>/release/` 改成 `apk/release/`（旧 glob 在新布局下取不到文件）；应用内更新的资产选择固定取主 APK，并显式排除历史 Release 里的 `-plain-` 名字——那类文件名同样以 `release.apk` 结尾，不排除会被引到已停产的包上。

**1.0.15**：悬浮窗帧率单位显示「Hz」；采样周期统一为「不慢于 1 秒」；应用内新增「检查新版本 / 强制更新」功能（唯一的联网路径）。

- **悬浮窗帧率显示「Hz」**：`CoreMetricId.FPS` 的单位由 `fps` 改为 `Hz`，悬浮窗 / 详情页 / 总览页三处统一显示「60 Hz」这类读法（帧率本质就是每秒帧数，单位本就是赫兹）。
- **采样周期统一「不慢于 1 秒」**（用户要求：每个数据都要每秒刷新）：默认档位本来就是 1s，但「极简」档是 2s，会让所有指标的刷新慢于每秒。现在把 1 秒设为硬下限——极简档的采样周期改为 1s（只保留「关动画 + 停历史落盘」的省电特性），并在 `MetricsRepository` 里用 `MAX_TICK_MS` 把 tickMs 兜底钳到 1000ms，将来新增更慢的档位也会被钳住。四个核心指标因此每秒至少更新一次。
- **应用内「强制更新」**（用户要求：打开 App 自动检查，有新版本就必须更新，不更新整个应用任何功能都用不了）：打开 App 时查询 GitHub Releases 最新版，**只要确认有比当前更新的版本**，就弹「不可关闭」的更新提示（点背景、按返回键都关不掉，只有「立即更新」能走），旧版本在更新前任何功能都无法使用；点「立即更新」下载最新版本 APK 并拉起系统安装器完成升级。每次启动都重新检查，因此不再持久化「忽略版本」。**唯一的例外**：检查更新本身连不上服务器（断网/超时）时，按「无更新」处理并允许暂时关闭继续使用——否则会把离线用户永久锁死；联网成功后再打开会重新检查。检查/下载只走公开 Release 元数据，**这是工程唯一主动联网路径**，为它申请了 `INTERNET` 权限，但只读取 Releases、上报零数据（详见「权限」一节）。

- **液态玻璃版闪退根因与修复**（用户报「一进去就闪退，怀疑库要别的语言」）：不是语言问题——[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 是 Kotlin Multiplatform 工程，Android 工程消费 backdrop-android 就是官方支持路径；根因是挂载结构用错。1.0.13 把 `layerBackdrop` 挂在 NavHost 上，玻璃卡片因此落在录制子树内：`LayerBackdropNode` 录制时重放玻璃节点的 `drawLayer`，采样到的是正在录音的同一个 RenderNode——自指采样，未定义行为，一进首页就崩。1.0.14 照库官方 catalog `BackdropDemoScaffold` 的标准结构重写：`layerBackdrop` 只挂纯背景节点（主题渐变层，与各页面背景同源同色），NavHost 与浮动底栏互为兄弟，所有 `drawBackdrop` 玻璃元素都在录制子树之外。效果参数不变，仍全部取自库教程既有值。
- **详情页/总览页「显示靠上、图表不显示」根因与修复**（两个包都中招，与玻璃无关）：页面在 `verticalScroll` / `LazyColumn` 里，子节点拿到的是无界高度约束；卡片写 `heightIn(min=…)` 时高度按内容收缩，内部 `Box(fillMaxSize)` 退化成 wrap——数字/环形被 GlassCard 的 TopStart 对齐顶到卡顶，Sparkline / BarSeries 是无固有尺寸的 `Canvas`，拿到零高度什么都不画。1.0.10 只修了图表 Y 轴量程（数据层），没修这个布局根因，所以「和上一个状况一样」。现在图表卡改固定 240dp、指标卡 120dp、综合评判卡 176dp，详情页顶部卡交回内容高度，`fillMaxSize` 恢复有界，居中对齐与 Canvas 绘制全部正常（`Crossfade` 根节点是普通 Box，约束原样透传，固定卡片高度即可治愈整条链）。
- **「回看」窗口不记忆**（用户报：选 30 分钟退出详情页再进，被打回 1 分钟）：窗口选择以前是 `remember { mutableIntStateOf(0) }`，只活在页面 Composition 里，页面一销毁就重置。现在与「显示方式」同一套 DataStore 持久化（新增 `metricWindows`，序列化格式同 `metricViewModes`），每个指标各自记住上次选的窗口，重装才丢。

**1.0.13**：iOS 悬浮窗新增「仿灵动岛」态，监测形态改为用户自选；Android 出液态玻璃/无玻璃双包。

- **Android 液态玻璃**（1.0.13 起，1.0.16 起为唯一发布版本）：用开源库 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（backdrop 2.0.0-alpha03，Apache-2.0）：AppNav 根创建 `LayerBackdrop`（纯背景节点 + 页面内容录制成层），卡片与浮动底栏按库教程既有参数采样折射（vibrancy + blur(4dp) + lens(16dp, 32dp)，surface 层主题色 40% 透明；底栏 blur(8dp) + lens(24dp, 24dp)）——参数全部取自库文档与 catalog 示例，没有自拟。1.0.13~1.0.15 曾用构建期 flavor（`glass` / `plain`，`BuildConfig.LIQUID_GLASS`）出「玻璃 / 无玻璃」双包，其中 plain 包经 R8 把整个库裁干净（dex 内 0 处 kyant 引用，包体小 16KB）；**1.0.16 起按用户要求删除 plain 变体，只发布液态玻璃这一个版本**，产物固定为 `DevicePerfMonitor-<版本>-release.apk`。blur 需 Android 12+、lens 需 Android 13+，更低版本库内部自动跳过效果不崩。

- **新增灵动岛态悬浮窗**（overlayDefaultMode 默认 island，1.0.13 起默认形态）：紧凑药丸 = 综合评分小环 + 一个核心指标（帧率优先，采不到退内存）；单击展开为大圆角面板（药丸条作岛锚 + 综合评判 + 指标行），再单击收起；展开态以紧凑态顶边为锚向下增长，与真灵动岛展开方向一致。窗口尺寸瞬切 + 内容 spring 形变（与其他形态同一原则）。
- **监测形态自选**（覆盖 1.0.10 的「支持灵动岛就不给悬浮窗选项」）：设置页新增「监测形态」二选一——灵动岛/锁屏（实时活动）或应用内悬浮窗，两种互斥切换；悬浮窗形态三选一（灵动岛/卡片/纯数字）。1.0.10 分流规则的背景：灵动岛退后台即冻结，用户要悬浮窗扛前台实时显示。
- **平台事实**（README「降级说明」已记录，此处再明确）：iOS 没有 Android 那种 SYSTEM_ALERT_WINDOW 全局悬浮窗权限，第三方 App 不能浮在其他应用上；iOS 上「悬浮窗」的上限是应用内窗口——好处是 App 前台时它就是实时的，没有后台冻结问题；退后台随窗口隐藏，回前台自动挂出。

**1.0.12**：设置页「关于」段瘦身——删掉大段产品理念式说明文字，只留版本号与开源许可。

- Android 删：「全部数据本地采集、本地存储；不联网、不上传、不含第三方统计 SDK。指标三态规范：可用 / 不可用 / 需授权……」整段。
- iOS 删：「历史轨迹保留 2 小时……」「iOS 平台可用性差异与降级路径详见仓库 README……」两条。
- 两端「关于」段现在统一为：版本号 + 开源许可。

**1.0.11**：iOS 后台刷新通道落地——挂起后按系统调度周期性唤醒刷新灵动岛；「后台采集」死开关接活；灵动岛新增自走时的数据年龄。

- **iOS 挂起后有正规刷新通道了**（BGAppRefreshTask）：退后台提交后台刷新任务，系统按使用习惯唤醒 App（通常十几分钟到几小时一次），每次唤醒采样一次，经采集中枢把新数据推进灵动岛/锁屏。这不是实时——iOS 不允许第三方应用在后台常驻运行，唤醒时机由系统决定；但在「全程离线、不放服务器」的前提下，这是苹果提供的唯一正规手段。
- **「后台采集」开关以前是死的**：`backgroundCollection` 只存 UserDefaults、没有任何代码读它，Info.plist 也没声明 `UIBackgroundModes`，副标题却写着「退后台后继续采样」。现在真正接线到 BGTaskScheduler，关掉即取消待执行任务。
- **灵动岛/锁屏新增「更新于 X」**：用 `Text(_, style: .relative)` 由系统自己走时，App 被挂起后也持续走动——做不到实时就不假装，至少让数据有多旧在灵动岛上实时可见。
- **为什么不放服务器**（用户提议后查证，结论是不做）：① ActivityKit 推送有小时级预算，超了静默丢弃，苹果文档原话是体育直播这类秒级场景「就是装进不了这个预算」——加了服务器也做不到实时；② 推送需要 App 联网上传 push token，会撞破「数据只存本地」原则；③ GitHub Actions 当不了这个服务器：定时工作流最小 5 分钟一轮（负载高时更晚），且拿不到设备 push token。三条都记在 `BackgroundRefresh.swift` 注释里。
  - 注：1.0.15 起 App 新增**应用内强制更新检查**（仅 Android，iOS 已下架安装包），这是唯一主动联网路径，只读取公开的 GitHub Releases 元数据、不上报任何数据；为它申请了 `INTERNET` 权限。其余采集/分析/历史仍 100% 本地。

**1.0.10**：修悬浮窗拖动不跟手、帧率/温度采不到、图表贴顶、内存口径偏高（真机反馈集中修复）。

- **悬浮窗拖动全跟手**（用户原话「手移动一大段距离，它只移动一小段」）：以前要求**长按 350ms** 且位移超过 slop 才进入拖动，按下直接拖时前段位移全部丢失；且进入拖动的那一刻不结算已累积的位移。现在位移超过 slop 立即拖动、进入时一次性补发累计位移，之后按事件增量跟手——从按下点开始零丢失。iOS 侧本来就是全跟手实现（`translation` 从按下点算），无需修改。
- **悬浮窗/首页帧率改为全局帧率**：以前读的是**本 App 自身帧率**（Choreographer），退出应用后本 App 不绘制，悬浮窗的帧率永远是空的（用户看到的就是「帧率根本没做」）。现在优先取前台应用/游戏真实帧率（`dumpsys gfxinfo`，需 Shizuku 免 root 授权或 root），未授权时回落本 App 帧率，都采不到时悬浮窗显示「需 Shizuku 授权」引导行而不是静默消失；设置页「特权指标」文案点明悬浮窗帧率依赖。常驻通知同步改全局帧率。
- **温度回退电池温度**：华为等国产 ROM 的 SELinux 策略禁止普通应用读 `/sys/class/thermal`，这些机型悬浮窗/详情页完全没有温度。现在温区读不到时自动回退电池温度（`ACTION_BATTERY_CHANGED`，所有机型可读），详情页透明标注「当前为电池温度」，不冒充温区读数（两者通常差 5~15℃）。
- **图表 Y 轴抗离群点**（用户原话「所有数据他都偏上」）：以前直接用 min/max 做量程，一个离群低点（内存偶尔跌到 78.0，其余 59 点全在 79.5~80.0）把量程拉宽，密集点全被压到图表顶部，看起来就是贴顶一条线。现在主体范围取 2%/98% 分位数（离群点不绑架量程、由绘制侧 coerce 到边缘），近恒定数据（如 60Hz 刷新率）扩展到最小可范围让线居中、微小波动可见。5 例 JVM 单测钉住。
- **刷新率进历史**：`historyTracked` 白名单漏了 `REFRESH_RATE`，刷新率详情页回看永远「这个时间段还没有采到数据」。补上（用户天天看刷新率）。
- **内存口径修正**：以前用 `ActivityManager.availMem`，它只算真正空闲、不含可回收缓存，同一台机器占用率比系统「设置-内存」页偏高 5~15%（用户原话「内存数据偏上」）。改读 `/proc/meminfo` 的 `MemAvailable`（内核估算的真正可用内存，与系统内存页一致），字段缺失时按内核定义手算，`/proc` 读不到的极端情况才回退旧口径。
- **iOS 监测形态按平台能力分流**（用户原话「如果支持实况窗或者灵动岛就只能用灵动岛和实况窗，如果不支持再用悬浮窗，如果支持的话就直接没有悬浮窗这个选项」）：本机支持实时活动（iOS 16.1+ 且系统开关没关）时，监测数据只上灵动岛/锁屏，**设置里不再出现悬浮窗选项**；只有不支持的机型（或用户在系统设置里关了实时活动）才回退应用内悬浮窗。冷启动自动申请实时活动，并优先收养系统里仍在运行的实例（`Activity.activities` 跨进程重启仍有效）——无条件重新 request 会在 App 被杀重启后出现两个实时活动，历史版本留下的重复实例会顺手清掉。
- **实时活动驱动链路挪到采集中枢**：以前 iOS 的 `AlertManager.evaluate` 只挂在悬浮窗的 500ms 循环上，不挂悬浮窗的机型（即新默认形态）永远收不到更新，灵动岛会停在上次的值。现在由 `MetricsRepository.sampleOnce` 发布快照后统一驱动，前台 UI / 后台运行 / 灵动岛三种形态走同一条链路（对齐 Android 的 MonitorViewModel）。
- **iOS 实时活动的边界**（如实记录，不是 bug）：iOS 没有机身温度公开 API，灵动岛温度位不占位；帧率是本 App 绘制帧（CADisplayLink），退后台无绘制即停在最后一次真实测量；苹果官方与工程师确认 App 退后台会被挂起、定时器停摆，持续后台更新只能靠推送服务器——本项目全程离线无服务端，所以 App 被挂起后灵动岛数据停留在最后一次采样，超过 60s（staleDate）系统自动置灰明示不新鲜。
- **鸿蒙 6 实况窗需人工审核申请**，安卓 APK 做不了实况窗，所以安卓侧只做悬浮窗 + 常驻通知（用户原话「安卓版本的就只做悬浮窗就行」）。

**1.0.9**：修掉悬浮窗挂不出来的 bug，重做配色与底栏，悬浮窗/实况窗指标收敛为三项。

- **修掉「进过一次应用后悬浮窗再也挂不出来」**：服务里用一个裸 Boolean 记 `overlayVisible`，`hide()` 从不把它置回 false，于是退后台发的 ACTION_SHOW 全被 `if (overlayVisible) return` 吞掉。链路是 `MainActivity.onResume` 发 SHOW、随后 `MonitorApp` 发 HIDE，从第二次开始自动挂出就彻底失效。现在把可见性收敛成 `OverlayVisibilityState` 状态机，并用 JVM 单测钉死「hide → show 能再次挂出」这类状态 bug（新增 7 例）。
- **顺带修掉 Android 12+ 的后台启动限制**：应用在**前台**时就把服务 ENSURE 起来，退后台只给已运行的 FGS 发命令——后台新建前台服务会被系统拒绝，这是自动挂出在 Android 12+ 上能真正生效的前提。同时删掉 `MainActivity.onStop` 里与 `MonitorApp` 打架的重复调用。
- **配色换成官方算法生成**：种子色 Catppuccin Mauve，Material 3 Expressive（SPEC_2025），24 个语义色，层次 tone 阶梯手工拉开到 ~6 一档（M3 默认只差 2~3，深色下根本看不出层次）。生成脚本内做 WCAG 校验：正文在最差表面层上 ≥4.5、描边 ≥3.0，不达标直接失败退出。次要强调色固定为青色——M3 从紫色派生出的 tertiary 是黄绿，会和 success 绿撞色。
- **控件有层次了**：以前卡片是零阴影、零描边、零高光的纯色块，现在统一加真投影 + 描边 + 顶部高光（参数来自 token）。`Theme.kt` 补齐了 `surfaceContainer*` / `tertiary*` / `outline*` 的 ColorScheme 映射——以前这些组件拿的是 MD3 默认色，不是我们的色板。
- **底栏改成浮动胶囊**（双端同构）：圆角胶囊 + 投影 + 描边，选中态是滑动的 pill 指示器（Expressive fastSpatial 弹簧）。以前 Android 是系统 NavigationBar、iOS 是一块贴底的色块。
- **悬浮窗与实况窗只显示三项**：温度 / 实时帧率 / 内存占用率。刷新率移到应用内看。iOS 没有机身温度的公开 API，实况窗的温度整行不渲染——不拿热状态档位换算冒充。Android 常驻通知同步成同一口径。
- **删除区重做**：Android 删掉 Canvas 手画的铁桶，改用官方 Material Symbols 图标 + 圆形删除区（悬停放大转红 + 震动 +「松手关闭」）；**iOS 补上可见的删除区**——以前只有悬停判定逻辑，屏幕上什么都看不到，用户根本不知道拖到哪能删。
- 页面固定高度改自适应下限；修掉设置页「Panel 16dp + 内部 16dp」实为 32dp 的重复内边距；首页补上被浮动底栏挡住的底部安全区；删掉零引用的死代码 `MetricChart.kt`。
- 补 CI 漏洞：两个 workflow 的 `paths` 都漏了 `design-tokens.json`，改色板不触发任何一端构建。

**1.0.8**：双端视觉重构 + 悬浮窗交互重做。

- **iOS 换开源液态玻璃库**：弃用 `UIVisualEffectView` + 7 层自绘叠加（模糊半径只能取系统固定档位、追不上 iOS 26 原生质感），改走 [LiquidGlassKit](https://github.com/muhittincamdali/LiquidGlassKit)（MIT，SPM 2.1.0+）。iOS 26 上自动走原生 `glassEffect`，iOS 17~25 走库的纯 SwiftUI fallback，Reduce Transparency / Increase Contrast / Reduce Motion 由库内置适配。适配层在 `ios/DevicePerfMonitor/UIKit/LiquidGlass.swift`，19 处调用方零改动。
- **Android 换 Material 3 Expressive**：移除 backdrop 液态玻璃（Kyant0/AndroidLiquidGlass），换 Compose Material3 稳定版 1.4.0 的公开组件——NavigationBar 底栏、SingleChoiceSegmentedButtonRow 分段选择、可点击 Card。动效与 androidx `ExpressiveMotionTokens` 对齐：按压、形态切换走 fastSpatial 弹簧（dampingRatio 0.6 / stiffness 800）。1.4.0 稳定版的 `MaterialExpressiveTheme` / `MotionScheme` 还是 internal（1.5.0-beta 才公开），所以按官方同参数自行实现，bom 锁定的稳定版不受影响。
- **悬浮窗三态**：完整卡片 → 纯数字（无容器，只留四个数）→ 收起（竖线），单击循环。默认稳定显示卡片，不再自动收窄；拖完只吸附不再改形态。
- **退出应用自动挂出**：不用再去设置里手动打开（双端：Android `MainActivity.onStop` 幂等拉起，iOS 回前台自动挂出）。
- **悬浮窗不透明度可调**：0.4~1.0，双端实时接线（Android `params.alpha`，iOS `window.alpha`），默认 1.0 不透明。
- **悬浮窗动效修复**：删掉对窗口宽高的逐帧动画——那会让"不要动它"的卡片在切换时被拉伸形变。现在窗口尺寸瞬切，内容形变全交给内容层过渡（Compose `AnimatedContent` / SwiftUI spring，token：`motion.spring.defaultSpatial`）。
- **适配补全**：旋转屏幕、改系统字体大小后悬浮窗重新夹取位置，不会跑出屏幕（Android `Service.onConfigurationChanged` 转发）。
- `design-tokens.json` 新增 `motion.spring` 三档（fastSpatial / defaultSpatial / slowSpatial，参数同 androidx ExpressiveMotionTokens），双端生成器同步扩展，业务代码不再写死弹簧参数。

- 1.0.6 补 ViewTree 宿主时调用顺序写反了：先推 lifecycle 到 CREATED 再
  `performRestore`，而 savedstate 1.4.0 的 `SavedStateRegistryImpl.performAttach`
  要求宿主仍处于 INITIALIZED（Recreator 必须在 ON_CREATE 派发前注册），
  反了直接抛 `IllegalStateException: Restarter must be created only during
  owner's initialization stage`——服务 onCreate 就崩，悬浮窗永远出不来。
  改成与 ComponentActivity 一致：先 `performRestore(null)`，再推 CREATED。
- 新增仓库第一个 JVM 单元测试 `ServiceViewTreeOwnerTest`：把宿主的调用契约
  （onCreate 不抛且到 CREATED、onStartCommand 幂等、onDestroy 到 DESTROYED）
  钉住。三轮悬浮窗崩溃都是「编译通过、真机才炸」的类型，以后这类顺序 bug
  在本地就能拦住。

**1.0.6**：修掉悬浮窗 ComposeView 挂窗口即崩的问题。

- Compose 1.11 起，`AbstractComposeView` 挂窗口时会走 `resolveComposeViewContext()`，
  强制要求 ViewTree 上取到 `LifecycleOwner` 和 `SavedStateRegistryOwner`，缺任何一个
  直接抛 `IllegalStateException: Composed into the View which doesn't propagate
  ViewTreeLifecycleOwner!`。1.0.5 只补了帧时钟那一半，Service 自跑 Recomposer 虽然设了
  `setParentCompositionContext`，但父合成上下文不带宿主信息，同样机型上一起床就崩。
- 新增 `overlay/ServiceViewTreeOwner.kt`：Service 自实现的 ViewTree 宿主，
  生命周期由 Service 转发（onCreate→CREATED、onStartCommand→RESUMED、onDestroy→DESTROY），
  savedState 只做占位（悬浮窗状态在 OverlayController 的 StateFlow 里，不走 Bundle）。
- 悬浮窗和垃圾桶两个 ComposeView 都在 addView 之前挂上宿主——垃圾桶窗口同样是
  裸 ComposeView，拖动唤出时也会中同样的招。

**1.0.5**：修掉悬浮窗服务一起床就崩的问题。

- 1.0.3 给悬浮窗换成「Service 自跑 Recomposer」后，少了一味料：Compose 的
  Recomposer 和动画都要求协程上下文里带 `MonotonicFrameClock`，Activity 路径下
  这个元素由 compose-ui 内部的 `AndroidUiDispatcher` 提供（internal API 拿不到），
  自跑 Recomposer 必须自己补。缺了它 `runRecomposeAndApplyChanges` 直接抛
  `IllegalStateException: A MonotonicFrameClock is not available in this CoroutineContext`，
  华为 Android 10 上必现——服务 onCreate 就崩，悬浮窗永远出不来。
- 新增 `overlay/OverlayFrameClock.kt`：用 Choreographer 的 postFrameCallback
  实现的公开 API 帧时钟，协程取消时摘回调不泄漏。帧时钟同时进三处：合成上下文、
  Recomposer 的 effect 上下文（`AnimatedNumber` 的 `Animatable.animateTo` 在
  LaunchedEffect 里跑，也要帧时钟）、驱动 `runRecomposeAndApplyChanges` 的协程。

**1.0.4**：全界面动效与交互反馈打磨，iOS 侧同步收敛到四指标，修掉详情页编译问题。

- 新增统一动效出口 `ui/kit/Motion.kt`：入场错峰（淡入 + 上滑，按索引 45ms 错峰、
  上限 240ms）、按压缩放（走 `graphicsLayer`，不触发重新测量）、骨架屏微光扫过、
  轻触觉反馈。曲线和时长全部取自 `design-tokens.json` 的双端共用动效 token，
  业务代码不写死时长。
- 首帧数据未到显示骨架屏，不再先闪空白再跳内容；模式切换（数字 / 波形 / 环形 /
  柱状）改交叉淡入，不硬切。
- 设置页每个开关 / 标签 / 按钮都有触觉反馈；悬浮窗授权后返回本页自动把服务拉起来，
  不用再拨一次开关；关于页版本号改读 `BuildConfig.VERSION_NAME`，与打包版本永远一致。
- 环形 / 数字模式下采不到当前值不再摆「—」占位，直接显示"这个时间段还没有采到数据"。
- 液态玻璃控件补齐 `interactionSource` 参数，按压缩放与点击响应共用同一个交互源。
- iOS 侧完成与 Android 的收敛：新增 `FocusedHealth.swift`，权重（流畅度 0.4 +
  温度 0.35 + 内存 0.25）和分段文案与 Android 逐字一致；首页 / 详情页 / 应用内悬浮窗
  全部重写，只剩内存占用率 / 屏幕刷新率 / 帧率 + 综合评判，采不到的直接不渲染
  （iOS 机身温度没有公开 API，常态缺席，权重按比例折算）。
- iOS 四种显示方式（数字 / 波形 / 环形 / 柱状）的选择持久化在 `ViewModeStore`，
  回看窗口与 Android 对齐（最长 2 小时）；实时活动数据卡从 CPU 使用率换成屏幕刷新率，
  灵动岛和锁屏都按可用性渲染，采不到的段不摆「—」。
- 修掉 iOS 版本号失控：两个 Info.plist 把版本键写死成 1.0.0/1，而工程是
  `GENERATE_INFOPLIST_FILE = NO`，pbxproj 里的 MARKETING_VERSION 从来不进包，
  关于页和装机看到的版本都对不上。现在改成变量引用，pbxproj 是唯一真源。

**1.0.3**：整个应用收敛到四个核心指标 + 综合评判，并重修悬浮窗。

- **悬浮窗终于能出来了**。之前点了开关没反应，其实是三个叠在一起的问题：
  1. Service 托管的 ComposeView 没有生命周期宿主，`onAttachedToWindow` 直接抛
     `IllegalStateException: ViewTreeLifecycleOwner not found`，窗口根本加不上。
     改成 Service 里自己跑 `Recomposer` 作为合成上下文；
  2. 开关打开时只写了 DataStore 里的布尔值，没有任何地方去拉起 `OverlayService`——
     现在改设置的同时真的启停服务；
  3. 权限状态进页面取一次就不更新了，系统里开了应用里还显示"未授权"，
     现在跟着系统刷新，从设置页返回会重新问一次。
- 退到后台自动挂出悬浮窗，回到应用自动收起。
- 只保留内存占用率 / 设备温度 / 屏幕刷新率 / 帧率四个指标，采不到的不占位。
- 综合评判改为流畅度（帧率÷刷新率达成率）0.4 + 温度 0.35 + 内存 0.25。
- 每个指标可切换数字 / 波形 / 环形 / 柱状，选择持久化。
- 历史保留 30 分钟 → 2 小时，详情页可回看 1 分钟 ~ 2 小时。

**1.0.2**：修掉 Android 10/11/12 上打开即闪退的真正原因——液态玻璃的实时模糊走 `android.graphics.RuntimeShader`（AGSL），这个类是 **API 33** 才有的，低版本上绘制期直接抛 `NoClassDefFoundError`。现在低于 Android 13 自动切到降级渲染（真实半透明 + 自绘高光/描边/内阴影/噪点，不是假模糊）。另外崩溃监测改成"先落盘、下次启动必弹"：Android 10 起系统限制后台启动 Activity，崩溃当下弹的窗可能被静默拦掉，所以报告会写进文件，下次进主界面（前台）一定显示，并附上一次启动的逐步轨迹，用来定位"死在哪一步"。

**1.0.1**：修掉内存占用率算出来是 `Double`、泛型擦除后被当成 `Float` 送进 UI 导致的 `ClassCastException`。顺手给 `MetricsRepository` 的取值函数加了实际值类型校验，以后再有采集器写错类型只会降级显示，不会崩。

**1.0.0**：首个版本。

## 功能

**四个核心指标 + 综合评判**

整个应用只看四件事：内存占用率、设备温度、屏幕刷新率、帧率，外加一个综合评判。

早先把 CPU、GPU、电池、进程十几个指标全铺在界面上，结果绝大多数机型根本读不到（Android 没给公开接口），界面上一片"不可用"，既没用又碍眼。现在只留这四个每台机器都真实可读的，**采不到的不占位、不显示占位符**——没数据就当它不存在。

**综合评判**

```
流畅度（帧率 ÷ 屏幕刷新率 的达成率）× 0.4
+ 设备温度分 × 0.35
+ 内存分 × 0.25
```

流畅度用"帧率相对屏幕刷新率的达成率"而不是帧率绝对值：30fps 在 30Hz 屏上是满帧，在 120Hz 屏上就是严重掉帧，光看数字会误判。温度分段点和评级文案走 `design-tokens.json`（双端唯一真源）。哪个维度采不到就整体缺席、权重按比例分给剩下的；三个都采不到就直接不给分，不编。

**历史轨迹与回看**

秒级实时采集、落在本地，保留最近 2 小时。详情页可以往回翻看 1 分钟 / 5 分钟 / 30 分钟 / 2 小时——帧率什么时候掉的、温度什么时候上去的，翻回去就能看到。数据全部本地存储，不联网、不上传。

**显示方式可切换**

每个指标都能单独切换四种读法，选择会被记住：

| 模式 | 适合看什么 |
| --- | --- |
| 数字 | 瞬时值，最准 |
| 波形 | 线条上下波动，看趋势和抖动 |
| 环形 | 占用率这类 0~100 有界值，最直观 |
| 柱状 | 离散采样条，看分布和突变 |

环形只对有界指标开放（当前是内存占用率），其余指标选了环形会回落到波形——把一个没有上限的值硬塞进圆环没有意义。

**全局悬浮窗**（Android 系统级，iOS 应用内）

左侧综合评判小环，右侧四个指标 2×2 排开。**退到后台自动挂出来，回到应用自动收起**（iOS 是回前台自动挂出）。长按拖动、靠边吸附、拖到垃圾桶关闭、双击隐藏，窗口外触摸穿透到下层应用。采不到的指标在悬浮窗里同样不占位，悬浮窗寸土寸金，宁可少一格也不放「—」。

**三态形态**：单击在「完整卡片 → 纯数字 → 收起」之间循环——纯数字只留四个数值没有容器，收起变成一条竖线。默认显示完整卡片，拖动只吸附不改形态；不透明度可在设置里 0.4~1.0 连续调节。切换时窗口尺寸瞬切、内容层做 spring 过渡，不会出现卡片被拉伸形变的半成品画面。

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

已在 Linux 环境验证通过：`:app:testDebugUnitTest`、`:app:assembleRelease`、`:app:lintVitalRelease` 全部成功，release APK 约 3.2 MB。`releases/` 目录下会放打好的包，可以直接装，但这个目录不入库，正式分发走 GitHub Release。

### iOS

用 Xcode 打开 `ios/DevicePerfMonitor.xcodeproj`，选 `DevicePerfMonitor` scheme 跑起来。首次打开会去拉两个 SPM 依赖（danielgindi/Charts、muhittincamdali/LiquidGlassKit），需要联网。

要求 **Xcode 26+**（iOS 26 SDK，液态玻璃在 iOS 26 上走原生 `glassEffect`），部署目标 iOS 17.0（用到了 iOS 17 的 API：`navigationDestination(item:)`、`.numericText` 等）。这个工程是 `ios/Tools/generate_xcodeproj.py` 生成的，UUID 确定性生成，重复生成不会漂移，不建议手改 pbxproj。

**iOS 安装包（IPA）**：交付环境是 Linux，没有 Xcode，本地打不出 IPA。仓库里放了 `.github/workflows/ios.yml`，push 代码后 GitHub 的 macOS runner 会自动构建 IPA 并上传为 artifact。这个流程产物是未签名的，要装到手机上需要自己重签名（或者有开发者账号的话配好证书 secret，workflow 里有开关）。真机上实时活动（灵动岛）与应用内悬浮窗（仅不支持的机型回退）的完整效果建议在真机验证，模拟器行为不一致。

## 目录结构

```
├── design-tokens.json     # 双端唯一真源：色板/玻璃参数(仅 iOS)/动效(曲线+弹簧)/健康分公式
├── Tools/check_tokens.py  # 校验双端生成产物一致
├── licenses/              # Inter 与 JetBrains Mono 的 OFL 许可证
├── releases/              # 打好的 APK / IPA（本地副本，gitignore，不在仓库里）
├── android/
│   ├── buildSrc/          # design-tokens 代码生成任务（编译型，兼容配置缓存）
│   ├── build-apk.sh       # 一键打包
│   └── app/src/main/java/com/devperf/monitor/
│       ├── core/          # 指标定义与三态模型
│       ├── data/          # 采集器、特权通道（root/Shizuku）、本地存储与滤波
│       ├── analysis/      # 综合评判、健康分、告警
│       ├── overlay/       # 悬浮窗服务、三态手势、垃圾桶
│       ├── ui/kit/        # 自封装 M3 Expressive 组件
│       └── ui/            # 首页、详情、设置、主题
└── ios/
    ├── Tools/             # pbxproj 生成与校验脚本
    ├── DevicePerfMonitor/
    │   ├── Analysis/      # 综合评判（FocusedHealth，与 Android 逐字对齐）
    │   ├── Collection/    # 采集器与三态模型（与 Android 对齐）
    │   ├── Overlay/       # 应用内悬浮窗
    │   ├── LiveActivity/  # 实时活动（App 与 Widget 共享）
    │   ├── Charts/        # Charts 图表封装
│   ├── UIKit/         # 自绘波形 / 柱状 / 环形组件、LiquidGlassKit 适配层
│   ├── Storage/       # 本地历史存储
│   ├── Theme/         # 玻璃主题（LiquidGlassKit 封装）与生成的 tokens
    │   └── Screens/       # 首页、详情、设置
    └── DevicePerfMonitorWidget/  # Widget 扩展（灵动岛/锁屏）
```

## 依赖与版本

Android 的版本统一在 `android/gradle/libs.versions.toml`，iOS 的 Charts 和 LiquidGlassKit 走 SPM（`ios/Tools/generate_xcodeproj.py` 里锁版本）。

| 依赖 | 版本 | 协议 | 说明 |
| --- | --- | --- | --- |
| AGP | 8.13.2 | Apache-2.0 | 没用 AGP 9，它不再支持 kotlin.android 插件组合 |
| Kotlin | 2.4.21 | Apache-2.0 | |
| Gradle | 8.14.6 | Apache-2.0 | wrapper 锁定 |
| core-ktx | 1.15.0 | Apache-2.0 | 1.16+ 要 AGP 9.1 |
| activity-compose | 1.12.4 | Apache-2.0 | minAGP 8.9.1 |
| lifecycle | 2.9.4 | Apache-2.0 | |
| navigation-compose | 2.9.4 | Apache-2.0 | 2.10.x 硬依赖 lifecycle 2.11.0，要 AGP 9.1，所以不升 |
| compose-bom | 2026.06.01 | Apache-2.0 | compose 1.11.4 + material3 1.4.0（M3 Expressive 视觉的载体） |
| datastore-preferences | 1.3.0 | Apache-2.0 | 本地存储 |
| material-icons-core | 1.7.8（bom 管） | Apache-2.0 | 底栏图标。material3 1.4.0 起不再传递依赖 icons，需显式声明；R8 会裁掉未用图标，所以不引 extended |
| Vico | 3.2.3 | Apache-2.0 | Compose 图表。3.3.x 要 compileSdk 37 |
| Shizuku | 13.1.5 | Apache-2.0 | 免 root 提权通道，可选。13.1.5 起 `Shizuku.newProcess` 私有化了，改成走公开 AIDL `IShizukuService.Stub.asInterface(...)` |
| Charts (iOS) | 5.0.0+ | Apache-2.0 | iOS 图表，对应 Android 的 Vico。注意：这个库 4.0 起把包名和 SPM 产品名改成了 `DGCharts`，所以 import 和 pbxproj 里的 productName 都用 DGCharts |
| LiquidGlassKit (iOS) | 2.1.0+ | MIT | iOS 液态玻璃（muhittincamdali/LiquidGlassKit，SPM）。iOS 26 走原生 `glassEffect`，低版本走库的纯 SwiftUI fallback |
| Backdrop (Android) | 2.0.0-alpha03 | Apache-2.0 | Android 液态玻璃（Kyant0/AndroidLiquidGlass，Maven Central）。参数照库文档/catalog；2.0.0 正式版要 compileSdk 37 / AGP 9.1+，本项目 AGP 8.13.2 + compileSdk 36，故选 API 完全一致的 alpha03（javap 核对签名，minCompileSdk=36）。1.0.16 起为唯一发布版本，不再存在无玻璃的 plain 变体 |
| kyant-shapes (Android) | 1.2.0 | Apache-2.0 | Backdrop 的 G2 平滑圆角（RoundedRectangle / Capsule），随 Backdrop 传递引入 |
| material-color-utilities | 5.0.1 | MIT | **仅离线工具，不进 APK、不进 CI**。Google 官方 Material Color Utilities 的 KMP 移植（com.materialkolor），在 `Tools/palette-gen/` 里生成 1.0.9 的色板，结果固化进 design-tokens.json。只依赖 kotlin-stdlib，零 Compose 依赖——用它而不是 material-kolor，是因为后者 5.0.1 依赖 material3 1.12.0-alpha03（alpha），会逼着升 alpha 依赖 |

## 采集指标对照表

三态：**A** 可用，**NP** 授权后可用，**U** 平台没有公开途径。

不可用的指标**直接从界面消失**：不占位、不显示「—」、不做估算。宁可少一格也不放一个没有意义的值。

### Android

| 指标 | 数据来源 | 权限 | 状态 | 降级路径 |
| --- | --- | --- | --- | --- |
| 内存占用率 | ActivityManager.MemoryInfo（已用 ÷ 总量） | 无 | A | |
| 设备温度 | 全部可读 thermal zone 取最大值 | 无 | A（看机型） | 读不到则该指标直接从界面消失。温区命名各 ROM 不统一，数值口径有机型差异 |
| 屏幕刷新率 | DisplayManager | 无 | A | |
| 帧率 | Choreographer 帧回调，500ms 窗口 | 无 | A | 超过 2s 无帧判定"当前无持续绘制"，不拿旧值冒充 |

### iOS

界面与 Android 完全一致：四个核心指标 + 综合评判，采不到的不进界面。iOS 上机身温度没有公开传感器直读 API，所以常态只有三个指标加综合评判——温度维度缺席，权重按比例折算给其余维度，不拿热状态档位换算冒充温度值。

| 指标 | 数据来源 | 权限 | 状态 | 降级路径 |
| --- | --- | --- | --- | --- |
| 内存占用率 | host_statistics64 + ProcessInfo.physicalMemory | 无 | A | 口径与 Android 一致：可用 = (free + inactive) × 页大小 |
| 屏幕刷新率 | UIScreen.maximumFramesPerSecond | 无 | A | |
| 帧率 | CADisplayLink | 无 | A | 超过 2s 无帧 → 判定"当前无持续绘制"，不拿旧值冒充 |
| 机身温度（℃） | 无传感器直读 API | | U | 只有 `ProcessInfo.thermalState` 四档热状态，不换算冒充，该指标不进入界面 |

iOS 的数据层其实仍和 Android 一样全量采集（CPU、电池、热状态等），1.0.4 起两边同步收敛：界面只呈现上表里真实可读的指标，其余一律不占位、不显示占位符。

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

`INTERNET`：1.0.15 起为应用内版本检查申请，仅用于读取公开的 GitHub Releases 元数据（查新版本 + 下载最新 APK），不向任何服务器上报数据；其余功能全程离线。

iOS 侧没有运行时权限，采集全走公开 API。Live Activity 靠 `NSSupportsLiveActivity` 声明，数据经 ContentState 下发，不需要 App Group（两个 entitlements 是空的，文件里有注释说明原因）。

## 已知限制

这些是平台限制，做不了，都按降级方案落了：

**iOS**

1. 没有系统级全局悬浮窗，iOS 不允许第三方应用悬浮在其他 App 上面。1.0.10 起监测形态按平台能力分流：支持实时活动（iOS 16.1+ 且系统开关没关）的机型**只用灵动岛/锁屏，设置里不出现悬浮窗选项**；仅不支持的机型回退应用内 `UIWindow` 悬浮窗，手势、三态切换、垃圾桶这些交互都按 Android 的规格做了；App 回前台自动挂出、退后台自动隐藏。
2. 机身温度（℃）没有传感器直读 API，只有 `ProcessInfo.thermalState` 的四档热状态。不拿档位换算冒充温度值，该指标在 iOS 上不进入界面，综合评判的温度维度缺席、权重按比例折算。帧率只看本应用前台绘制（CADisplayLink），读不到其他进程的渲染帧。
3. 本地打不了 IPA（没有 Xcode），用 GitHub Actions 的 macOS runner（macos-15 + Xcode 26）构建，产物未签名，装机要重签名。
4. 液态玻璃分两档：iOS 26 及以上走原生 `glassEffect`（真折射 + 交互形变），iOS 17~25 走 LiquidGlassKit 的纯 SwiftUI fallback（Material blur + tint + 边缘高光），观感接近但不是同一个 API 路径。Reduce Transparency 下自动换不透明底。
5. 实时活动（灵动岛/锁屏）的后台更新是平台硬限制：苹果官方文档与工程师回复都确认，App 退后台会被挂起、定时器停摆，持续更新只能靠推送服务器。本项目全程离线、无服务端，所以 App 被挂起后灵动岛停留在最后一次采样。1.0.11 起用 `BGTaskScheduler` 兜底：系统会周期性唤醒 App 采样一次刷新实时活动（通常十几分钟到几小时，时机由系统按使用习惯决定），灵动岛上另有自走时的「更新于 X」明示数据年龄；超过 60s（staleDate）系统置灰。帧率同理——CADisplayLink 只在本 App 前台绘制时有帧，后台显示最后一次真实测量。机身温度没有公开传感器 API，灵动岛温度位整行不渲染（不拿热状态档位换算冒充）。

**Android**

下面 1、2 两条是数据层的既有边界，涉及电池、后台内存、GPU 这些已经从界面移除的指标，记录在此备查；界面上看得到的四个指标，各自的降级路径见上面的采集指标对照表。

1. 电池健康百分比没有公开接口，只展示 EXTRA_HEALTH 的粗粒度档位。
2. 后台应用内存、各核心频率、GPU 节点要 root 或 Shizuku。普通 App 读不了，这是 Android 8 之后的沙箱规定。
3. Shizuku 13.1.5 把 `newProcess` 私有化了，改走公开 AIDL。
4. **Android 液态玻璃**（1.0.13 起落地，1.0.16 起为唯一发布版本；修正 1.0.8「Android 没有液态玻璃」的决策）：安卓真实背景模糊要么走 API 33 的 `RuntimeShader`（Android 12 及以下直接崩）、要么观感与服务端合成对不上，所以 1.0.8 起全面换 Material 3 Expressive。1.0.13 应用户要求引入开源库 Kyant0/AndroidLiquidGlass（backdrop 2.0.0-alpha03）把液态玻璃做上——blur 需 Android 12+、lens 需 Android 13+，更低版本库内部自动跳过效果只保留采样层，不崩；当时为保守起见同时出了一份不引效果的 plain 版。**1.0.16 起按用户要求只保留玻璃版，删掉 plain 变体与双 flavor，单一发布包。** 玻璃质感 iOS 端仍走 LiquidGlassKit（1.0.8 起），两端共用同一份色板与动效 token。

## design-tokens.json

色板、圆角、间距、玻璃参数（仅 iOS 使用：模糊 28~36、背景透明度 18%~28%、边框高光阴影饱和度噪点）、动效（三条曲线 enter/exit/emphasized + 三档空间弹簧 fastSpatial/defaultSpatial/slowSpatial）、数值缓动规则、健康分权重和分段点，全在这个文件里。双端各自生成代码：

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
