# DevicePerfMonitor

一个设备性能监控 App，Android 和 iOS 双端。核心是三块：全局悬浮窗看实时数据、四个核心硬件指标实时采集、本地综合评判。全程不联网，数据只存本地。

两个端的界面是同一套设计：`design-tokens.json` 是唯一真源，色板用 Catppuccin Mocha/Latte。**视觉方案分端演进**：iOS 端用开源库 [LiquidGlassKit](https://github.com/muhittincamdali/LiquidGlassKit)（MIT，SPM 依赖）承载液态玻璃，iOS 26 上自动走苹果原生 `glassEffect`；Android 端 1.0.8 起换用 Material 3 Expressive（Compose Material3 稳定组件 + 官方同参数弹簧），不再叠液态玻璃——两端各自使用平台最地道的表达，共享同一份色板与动效 token。

几条从一开始就定下的规矩：

- 采不到的数据就显示"不可用"并说明原因，不拿估算值冒充
- 液态玻璃（iOS）走开源库 LiquidGlassKit 的真实材质：iOS 26 上是原生 `glassEffect`（折射 + 高光 + 交互形变），低版本是库的纯 SwiftUI fallback，不是半透明纯色
- Android 用 Material 3 Expressive：官方稳定组件（NavigationBar / SegmentedButton / Card）+ 与 androidx `ExpressiveMotionTokens` 同参数的弹簧，不引 internal API
- 不用系统默认控件和默认蓝，按钮卡片开关滑块全部自己封装
- 不申请 INTERNET 权限，没有任何联网代码路径
- 平台做不到的功能，直接说做不到，降级方案写进文档

## 版本

当前 **1.0.8**。安装包在 [Releases](https://github.com/sitong-zhang/DevicePerfMonitor/releases) 页面，仓库里不存二进制。

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

**iOS 安装包（IPA）**：交付环境是 Linux，没有 Xcode，本地打不出 IPA。仓库里放了 `.github/workflows/ios.yml`，push 代码后 GitHub 的 macOS runner 会自动构建 IPA 并上传为 artifact。这个流程产物是未签名的，要装到手机上需要自己重签名（或者有开发者账号的话配好证书 secret，workflow 里有开关）。真机上 Live Activity 和应用内悬浮窗的完整效果建议在真机验证，模拟器行为不一致。

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
| LiquidGlassKit (iOS) | 2.1.0+ | MIT | iOS 液态玻璃（muhittincamdali/LiquidGlassKit，SPM）。iOS 26 走原生 `glassEffect`，低版本走库的纯 SwiftUI fallback。Android 端 1.0.8 起不用玻璃，走 Material 3 Expressive |

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

`INTERNET` 明确不申请，全程离线。

iOS 侧没有运行时权限，采集全走公开 API。Live Activity 靠 `NSSupportsLiveActivity` 声明，数据经 ContentState 下发，不需要 App Group（两个 entitlements 是空的，文件里有注释说明原因）。

## 已知限制

这些是平台限制，做不了，都按降级方案落了：

**iOS**

1. 没有系统级全局悬浮窗，iOS 不允许第三方应用悬浮在其他 App 上面。改成应用内 `UIWindow` 悬浮窗，手势、三态切换、垃圾桶这些交互都按 Android 的规格做了；App 回前台自动挂出、退后台自动隐藏。
2. 机身温度（℃）没有传感器直读 API，只有 `ProcessInfo.thermalState` 的四档热状态。不拿档位换算冒充温度值，该指标在 iOS 上不进入界面，综合评判的温度维度缺席、权重按比例折算。帧率只看本应用前台绘制（CADisplayLink），读不到其他进程的渲染帧。
3. 本地打不了 IPA（没有 Xcode），用 GitHub Actions 的 macOS runner（macos-15 + Xcode 26）构建，产物未签名，装机要重签名。
4. 液态玻璃分两档：iOS 26 及以上走原生 `glassEffect`（真折射 + 交互形变），iOS 17~25 走 LiquidGlassKit 的纯 SwiftUI fallback（Material blur + tint + 边缘高光），观感接近但不是同一个 API 路径。Reduce Transparency 下自动换不透明底。

**Android**

下面 1、2 两条是数据层的既有边界，涉及电池、后台内存、GPU 这些已经从界面移除的指标，记录在此备查；界面上看得到的四个指标，各自的降级路径见上面的采集指标对照表。

1. 电池健康百分比没有公开接口，只展示 EXTRA_HEALTH 的粗粒度档位。
2. 后台应用内存、各核心频率、GPU 节点要 root 或 Shizuku。普通 App 读不了，这是 Android 8 之后的沙箱规定。
3. Shizuku 13.1.5 把 `newProcess` 私有化了，改走公开 AIDL。
4. **Android 没有液态玻璃**（1.0.8 起的刻意决策）：安卓这边真实背景模糊要么走 API 33 的 `RuntimeShader`（Android 12 及以下直接崩）、要么观感与服务端合成对不上，所以 Android 全面换 Material 3 Expressive——Compose 的 M3 组件不依赖系统版本，从 minSdk 24 起视觉与动效完全一致。玻璃质感只在 iOS 端保留（LiquidGlassKit）。

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
