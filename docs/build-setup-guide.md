# DevicePerfMonitor 环境搭建与打包指南（零坑版）

> 面向第一次给这个项目搭环境的人。所有命令均在本项目上实测通过。
> 环境要求速查见文末表格；踩坑速查表在第 6 节，按报错信息检索。

---

## 0. 先选路线：你要打哪个包

| 目标 | 需要什么 | 难度 |
| --- | --- | --- |
| 只要装上用 | 不用搭环境，去 Releases 页面下现成的 | ⭐ |
| 打 Android APK | JDK 17+ + Android SDK，Windows/macOS/Linux 都行 | ⭐⭐ |
| 打 iOS IPA | **必须有 macOS + Xcode 15+**，Linux/Windows 打不了 | ⭐⭐⭐ |
| 没有 Mac 但要 IPA | 用 GitHub Actions 云端构建（push 即触发，见 4.3） | ⭐ |

仓库：`https://github.com/sitong-zhang/DevicePerfMonitor`，主分支 `main`。

---

## 1. Android 环境搭建

### 1.1 装 JDK（17 或更高）

```bash
java -version   # 先看有没有；必须显示 javac 所在 JDK，JRE 不行
```

- 没有 JDK 就装一个：macOS 用 `brew install openjdk@17`；Linux 用 sdkman（`curl -s "https://get.sdkman.org" | bash` 后 `sdk install java 17.0.x-tem`）或发行版仓库的 `openjdk-17-jdk`。
- **别用系统源里太老的 JDK**：本项目 Gradle 8.14 要求 JDK 17+，openjdk 11 及以下直接拒绝启动。

### 1.2 装 Android SDK（从零开始，Linux 示例）

```bash
# 1) 下载命令行工具（约 150MB，从 Android Studio 官网页面底部
#    "Command line tools only" 拿链接；下面是 linux 版示例）
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip

# 2) 注意目录结构！sdkmanager 要求 bin/ 的祖父目录叫 latest
#    （不这么放会报 "Could not determine SDK directory" 之类的错）
mkdir -p latest && mv bin lib NOTICE.txt source.properties latest/ 2>/dev/null || true

# 3) 环境变量（写进 ~/.bashrc 或 ~/.zshrc）
export ANDROID_HOME=~/android-sdk
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

# 4) 接受许可（否则后面每一步都弹协议确认）
yes | sdkmanager --licenses

# 5) 装本项目需要的平台与构建工具
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

也可以直接在 Android Studio 里装 SDK（SDK Manager → SDK Platforms 勾选 Android 16 / API 36 → SDK Tools 勾选 Android SDK Build-Tools 36）。`ANDROID_HOME` 不设也行——在 `android/` 目录建 `local.properties` 写一行 `sdk.dir=/你的/sdk/路径` 等效。

> **本项目锁死的版本**：compileSdk 36、targetSdk 36、minSdk 24（Android 7.0+）、
> Gradle 8.14.6（wrapper 锁定，不用自己装 Gradle）、AGP 8.13.2、Kotlin 2.4.21。
> 平台没装齐的典型报错：`Failed to find target with hash string "android-36"`。

### 1.3 构建 APK

```bash
cd android

# 方式一：Gradle 直调（CI 同款）
./gradlew :app:assembleRelease

# 方式二：一键脚本（自动处理签名降级，等价）
./build-apk.sh
```

- 产物：`android/app/build/outputs/apk/release/app-release.apk`（约 3.3 MB）。
- 首次构建会下载 Gradle 8.14.6 发行包和全部依赖，等几分钟正常，需要联网。
- 装机：`adb install -r app-release.apk`，或 `./gradlew :app:installDebug`（debug 包）。
- 可选加验：`./gradlew :app:lintVitalRelease`（致命级 lint，CI 没跑但我们每次发版前都跑）。

### 1.4 签名

- **不配任何东西**：`assembleRelease` 也能出包，但用的是 debug 签名——能装机、不能上架。构建日志里会写明这是有意的降级。
- **正式签名**：`cp android/keystore.properties.template android/keystore.properties`，按模板里的 keytool 命令生成密钥填进去。这个文件被 gitignore，密码不会入库。

### 1.5 Android 坑位（按命中率排序）

1. **`JAVA_HOME is set to an invalid directory`**：gradlew 第一行就退出。九成是 JAVA_HOME 指向了不存在的路径。用 sdkman 装的要特别注意——**候选目录名不等于版本号**（`20.fx-zulu`、`20.0.2-open` 都可能出现），别凭记忆写死，用 `ls ~/.sdkman/candidates/java/` 看一眼，或者直接用稳定的符号链接 `export JAVA_HOME=~/.sdkman/candidates/java/current`。
2. **管道吞退出码**：`./gradlew ... | tail -5` 之后 `$?` 是 tail 的退出码，构建失败也会显示 0。验证构建结果要么看重定向文件里的 `BUILD SUCCESSFUL`，要么 `./gradlew ... > build.log 2>&1; echo $?`。
3. **编译报 `Unresolved reference 'interaction'`（import 路径）**：Compose 的交互源在 `androidx.compose.foundation.interaction` 包，**不存在** `androidx.compose.ui.interaction` 包。本项目代码已全部修正，别从旧资料里抄错包名。
4. 公司内网/代理环境拉不到 google() 仓库的话，配 `~/.gradle/init.gradle` 镜像即可，不要改 `settings.gradle.kts`（里面用了 `FAIL_ON_PROJECT_REPOS`，项目级仓库直接报错）。

---

## 2. iOS 环境搭建

### 2.1 硬性要求

- **macOS + Xcode 15 或更高**。没有 Mac 就跳转 2.5 用 GitHub Actions。
- 部署目标 **iOS 17.0**：真机和模拟器都要 iOS 17+，老系统装不上。
- Swift 5.0 工具链随 Xcode 附带，不用单独装。
- 联网：首次解析 SPM 依赖需要。

### 2.2 打开工程

```bash
# 方式一：Finder 双击 ios/DevicePerfMonitor.xcodeproj，用 Xcode 打开
# 方式二：命令行先解析依赖再编（CI 同款前两步）
cd ios
xcodebuild -project DevicePerfMonitor.xcodeproj -scheme DevicePerfMonitor \
  -resolvePackageDependencies
```

- 依赖只有一个：**DGCharts**（`github.com/danielgindi/Charts`，要求 5.0.0+，SPM 产品名从 4.0 起叫 DGCharts，import 和 pbxproj 里都用这个名字，别写成 Charts）。Xcode 首次打开会自动拉，拉取失败就 File → Packages → Resolve Package Versions。
- Scheme 选 `DevicePerfMonitor`，会自动带上 Widget 扩展 `DevicePerfMonitorWidget`（灵动岛/锁屏实时活动）。

### 2.3 三种产物怎么出

| 想要什么 | 做法 |
| --- | --- |
| 模拟器跑 | Xcode 选个 iOS 17+ 模拟器直接 Run |
| 真机跑 | 项目里 App 和 Widget **两个 target** 的 Signing & Capabilities 都选你的 Team（免费 Apple ID 也能签，7 天有效期），再 Run |
| 未签名 IPA（分发/归档） | 见下方命令，与 `.github/workflows/ios.yml` 完全一致 |

未签名 IPA 的完整命令（没有证书时的标准做法）：

```bash
cd ios
xcodebuild -project DevicePerfMonitor.xcodeproj -scheme DevicePerfMonitor \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath "$PWD/build/DevicePerfMonitor.xcarchive" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" DEVELOPMENT_TEAM="" archive

mkdir -p build/Payload
cp -R "build/DevicePerfMonitor.xcarchive/Products/Applications/DevicePerfMonitor.app" build/Payload/
(cd build && zip -qry DevicePerfMonitor-unsigned.ipa Payload)
```

**这个 IPA 是未签名的**，装到手机要用你自己的证书重签（App 和里面的 Widget 扩展 `PlugIns/*.appex` 两个都要签）：

```bash
codesign -f -s "Apple Development: 你的名字 (TEAMID)" Payload/DevicePerfMonitor.app
codesign -f -s "Apple Development: 你的名字 (TEAMID)" Payload/DevicePerfMonitor.app/PlugIns/DevicePerfMonitorWidget.appex
```

有 Mac 的话，真机验证直接用 Xcode Run 更省事，别折腾重签名。

### 2.4 iOS 坑位

1. **新增/删除 .swift 文件后工程不认**：`project.pbxproj` 是显式文件清单，不是文件夹引用。加了新文件必须重跑：

   ```bash
   python3 ios/Tools/generate_xcodeproj.py
   ```

   脚本按目录扫描 + uuid5 确定性 UUID 生成，重复跑不会漂移；**不要手改 pbxproj**，改了会被下次生成覆盖。
2. **改了版本号不进包**：工程是 `GENERATE_INFOPLIST_FILE = NO`，两个 Info.plist（App 和 Widget）的版本键是 `$(MARKETING_VERSION)`/`$(CURRENT_PROJECT_VERSION)` 变量引用。改版本只能改 `ios/Tools/generate_xcodeproj.py` 里那两个字段再重跑生成——直接手改 Info.plist 里的数字无效（1.0.4 真踩过）。
3. **模拟器上行为不一致**：实时活动（灵动岛/锁屏）和应用内悬浮窗的完整效果必须真机验证。
4. SPM 依赖解析失败先查网络；`-resolvePackageDependencies` 单独跑一遍能拿到更明确的报错。

---

## 3. GitHub Actions 云端构建（没有 Mac 的出路）

仓库里有两个 workflow，push 到 `main` 自动触发（都带路径过滤，只改一边不会触发另一边）：

| Workflow | 触发条件 | Runner | 产物 |
| --- | --- | --- | --- |
| `android.yml` | 改动含 `android/**` | ubuntu-latest + JDK 17 | `app-release.apk` |
| `ios.yml` | 改动含 `ios/**` | macos-14 | 未签名 `.ipa` + `.xcarchive` |

- 手动触发：仓库 Actions 页面 → 选 workflow → Run workflow（两个都支持 `workflow_dispatch`）。
- 下载：跑完后进该次 run 的页面，底部 Artifacts 里下 zip。
- 想让 iOS 出**签名包**：在仓库 Secrets 配好证书和描述文件，按 `ios.yml` 里注释的说明改成本地 export 流程。

---

## 4. 改完代码先跑本地校验（不用等编译）

```bash
python3 Tools/check_tokens.py                                   # 双端 token/字体一致性
python3 ios/Tools/check_plist_syntax.py ios/DevicePerfMonitor.xcodeproj/project.pbxproj
python3 ios/Tools/validate_pbxproj.py                           # pbxproj 引用完整性
python3 ios/Tools/check_swift_syntax.py                         # Swift 括号配对 + import 汇总
```

- 这四个脚本是「没有 Xcode 的环境」的兜底，只抓结构性错误，**不替代真编译**。
- 坑：`check_plist_syntax.py` 的默认路径是相对路径 `../DevicePerfMonitor.xcodeproj/...`，**必须显式传 pbxproj 路径**（像上面那样），否则大概率 FileNotFoundError。
- 真编译：Android 走 1.3，iOS 走 2.2。

---

## 5. 发版检查清单（版本号三处 + 文档）

| 位置 | 改什么 |
| --- | --- |
| `android/app/build.gradle.kts` | `versionCode` / `versionName` |
| `ios/Tools/generate_xcodeproj.py` | `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`（各 2 处），改完**重跑生成脚本**写进 pbxproj |
| `README.md` | 顶部版本号、更新记录、构建小节的包体积 |
| Release 资产名 | `DevicePerfMonitor-<版本>-release.apk` / `.ipa` |

验证产物版本：Android 用 `aapt dump badging app-release.apk | grep package`；iOS 解包看 `Info.plist` 的 `CFBundleShortVersionString` / `CFBundleVersion`。

---

## 6. 踩坑速查表（按报错检索）

| 报错 / 现象 | 原因 | 解法 |
| --- | --- | --- |
| `JAVA_HOME is set to an invalid directory` | JAVA_HOME 指向不存在目录（sdkman 目录名≠版本号） | 用 `candidates/java/current` 符号链接或真实路径 |
| `Failed to find target with hash string "android-36"` | SDK 平台没装 | `sdkmanager "platforms;android-36"` |
| sdkmanager 报找不到 SDK 目录 | cmdline-tools 目录结构不对 | `bin/` 的祖父目录必须叫 `latest` |
| 构建"成功"但退出码 0 实际失败了 | 管道到 `tail` 吞掉 gradlew 退出码 | 重定向到文件再 `echo $?` |
| `Unresolved reference 'interaction'` | import 了不存在的 `androidx.compose.ui.interaction` | 正确包名 `androidx.compose.foundation.interaction` |
| `ViewTreeLifecycleOwner not found`（悬浮窗不显示） | Service 托管的 ComposeView 没有生命周期宿主 | 已修复：Service 自跑 Recomposer，别回退 |
| `A MonotonicFrameClock is not available`（服务起来就崩） | 自跑 Recomposer 的协程缺帧时钟 | 已修复：`overlay/OverlayFrameClock.kt` |
| iOS 新写的 .swift 文件不生效 | pbxproj 是显式文件清单 | 重跑 `ios/Tools/generate_xcodeproj.py` |
| iOS 改版本号不进包 | Info.plist 写死 + `GENERATE_INFOPLIST_FILE=NO` | 已修复：版本键用变量引用，只改生成脚本 |
| git 推送 TLS 握手超时（部分 Linux 沙箱） | git 的 GnuTLS 后端问题 | 用 curl / python urllib 调 GitHub API，或换 SSH |

以上带「已修复」的四条是历史问题，代码里都已解决，列出来是为了别被旧报错带偏、也别把修复回退掉。

---

## 7. 实测环境速查（照这个配最稳）

| 项 | 值 |
| --- | --- |
| OS | Ubuntu 22.04 / macOS 均可 |
| JDK | 17+（CI 用 Temurin 17，本地实测 Zulu 20 通过） |
| Gradle | 8.14.6（wrapper 自动下载，勿手改） |
| AGP / Kotlin | 8.13.2 / 2.4.21 |
| Android SDK | platforms;android-36、build-tools;36.0.0 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 24 |
| Xcode | 15+ |
| iOS 部署目标 / Swift | iOS 17.0 / 5.0 |
| iOS 第三方依赖 | DGCharts 5.0.0+（SPM，仅此一个） |
| APK 体积 | 约 3.3 MB |

---

## 8. 运行时注意（不是环境问题，但会影响测试）

- **Android 12（API 32）及以下没有真玻璃**：液态玻璃的实时模糊依赖 AGSL（API 33+），低版本自动降级为「半透明底 + 自绘高光/描边/内阴影/噪点」，观感接近但不是真模糊，这是有意的降级不是 bug。
- **Android 10 起限制后台启动 Activity**：悬浮窗服务要从前台（主界面开关）拉起；从后台拉会被系统静默拒绝，代码里已做降级（不崩、不显示）。
- **悬浮窗授权**：设置页打开开关 → 跳系统设置授予「显示在其他应用上层」权限（`SYSTEM_ALERT_WINDOW`）→ 返回设置页会自动拉起服务，不用再拨一次开关。
- **iOS 机身温度没有公开 API**：所以 iOS 上常态只有三个指标（内存占用率/屏幕刷新率/帧率）+ 综合评判，温度维度缺席时权重按比例折算，界面上不摆占位符——这是设计行为，不是没采到。
