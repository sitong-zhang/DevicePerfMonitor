package com.devperf.monitor.data.collectors

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 特权 Shell 通道：为「全局 FPS / 后台应用内存 / 各核心频率」等 shell 级指标提供执行能力。
 *
 * 后端优先级：root(su) > Shizuku(免 root) > 无。
 * 三态语义：
 * - 两者都不可用    -> UNAVAILABLE（UI 显示"— 不可用"）
 * - Shizuku 已安装未授权 -> NEEDS_PERMISSION（点击跳转 Shizuku 授权）
 *
 * 说明：Shizuku 通过 adb/无线调试启动，以 shell(2000) 权限执行命令，
 * 属于用户显式授权的合规提权方式；未授权时本 App 仅能读到无需特权的指标。
 */
class PrivilegedShell(private val context: Context) {

    enum class Backend { ROOT, SHIZUKU, NONE }

    @Volatile
    var backend: Backend = detectBackend()
        private set

    /** Shizuku 是否已安装（用于 UI 引导安装）。 */
    val shizukuInstalled: Boolean
        get() = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

    /** Shizuku 服务是否正在运行。 */
    val shizukuRunning: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Shizuku 是否已授权。 */
    val shizukuGranted: Boolean
        get() = runCatching {
            shizukuRunning && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** 重新探测后端（授权/撤销后调用）。 */
    fun refresh() {
        backend = detectBackend()
    }

    private fun detectBackend(): Backend = when {
        rootAvailable() -> Backend.ROOT
        shizukuGranted -> Backend.SHIZUKU
        else -> Backend.NONE
    }

    /** 探测 su 是否可用（带 3s 超时，避免卡死采集循环）。 */
    private fun rootAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").start()
        val done = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) {
            process.destroy()
            return false
        }
        val out = process.inputStream.bufferedReader().use { it.readText() }
        out.contains("uid=0")
    }.getOrDefault(false)

    /**
     * 请求 Shizuku 授权（弹出 Shizuku 授权对话框）。
     * 结果通过 [addBinderReceivedListener] 与权限回调异步到达，调用方需在获得结果后调用 [refresh]。
     */
    fun requestShizukuPermission(requestCode: Int) {
        runCatching { Shizuku.requestPermission(requestCode) }
            .onFailure { Log.w(TAG, "请求 Shizuku 权限失败", it) }
    }

    /**
     * 执行一条 shell 命令，返回合并输出；失败返回 null。
     * 注意：调用方必须对 null 做三态降级处理。
     */
    suspend fun run(command: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            when (backend) {
                Backend.ROOT -> {
                    val process = ProcessBuilder("su", "-c", command).start()
                    readProcess(process)
                }
                Backend.SHIZUKU -> {
                    val process = newShizukuProcess(command)
                    if (process != null) readProcess(process) else null
                }
                Backend.NONE -> null
            }
        }.onFailure { Log.w(TAG, "特权命令执行失败: $command", it) }.getOrNull()
    }

    /**
     * 通过 Shizuku 公开 AIDL 接口创建远端进程（以 shell/uid 2000 身份执行命令）。
     *
     * 背景（README「降级说明」已记录）：dev.rikka.shizuku:api 13.1.5 起
     * `Shizuku.newProcess` 变为 private，但其实现等价于：
     * ```
     * IShizukuService.Stub.asInterface(binder).newProcess(cmd, env, dir)
     * ```
     * 其中 `IShizukuService` / `IRemoteProcess` 是 `dev.rikka.shizuku:aidl`
     * （api 的 compile 级传递依赖，类路径可见）中的公开 AIDL 接口，
     * 且与 Shizuku 内部 onBinderReceived 给 service 字段赋值的方式完全一致
     * （已核对 13.1.5 字节码：onBinderReceived → IShizukuService$Stub.asInterface）。
     * 因此这里走公开接口，不反射私有 API。
     * ShizukuRemoteProcess 的构造器为包私有，故自行实现 [RemoteProcess] 适配器。
     */
    private fun newShizukuProcess(command: String): Process? {
        if (!shizukuRunning) return null
        val binder = Shizuku.getBinder() ?: return null
        val service = IShizukuService.Stub.asInterface(binder) ?: return null
        val remote = service.newProcess(arrayOf("sh", "-c", command), null, null) ?: return null
        return RemoteProcess(remote)
    }

    /**
     * 读取进程输出并等待退出。
     * - stderr 由独立线程读取，避免 stdout/stderr 管道同时写满导致死锁；
     * - 自带 5s 超时看门狗：只调用抽象方法 [Process.waitFor]（无参版本在所有
     *   API 级别均存在），不依赖 `waitFor(long, TimeUnit)`（API 26+ 才公开）；
     *   超时未退出则强杀，避免悬挂进程拖累采集循环；
     * - 强杀后管道关闭，两个读线程均会收到 EOF 自然结束。
     */
    private fun readProcess(process: Process): String {
        val stderr = StringBuilder()
        val errThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().use { it.readText() }
            }.onSuccess { stderr.append(it) }
        }.apply { isDaemon = true; start() }

        val stdout = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")

        val waiter = Thread { runCatching { process.waitFor() } }
            .apply { isDaemon = true; start() }
        waiter.join(EXIT_TIMEOUT_MS)
        if (waiter.isAlive) {
            Log.w(TAG, "特权命令 ${EXIT_TIMEOUT_MS}ms 未退出，强杀进程")
            runCatching { process.destroy() }
        }
        errThread.join(STDERR_JOIN_MS)
        return stdout + stderr.toString()
    }

    /**
     * IRemoteProcess → java.lang.Process 适配器。
     *
     * 仅使用 dev.rikka.shizuku:aidl 公开 AIDL 接口；流包装方式
     * （ParcelFileDescriptor.AutoClose*）与官方 ShizukuRemoteProcess 一致
     * （已核对 13.1.5 字节码）。不覆写 isAlive()：基类该方法 API 26+ 才公开，
     * 本工程不使用它，避免 minSdk 24 上的兼容风险。
     */
    private class RemoteProcess(private val remote: IRemoteProcess) : Process() {

        override fun getOutputStream(): java.io.OutputStream =
            ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream())

        override fun getInputStream(): java.io.InputStream =
            ParcelFileDescriptor.AutoCloseInputStream(remote.getInputStream())

        override fun getErrorStream(): java.io.InputStream =
            ParcelFileDescriptor.AutoCloseInputStream(remote.getErrorStream())

        override fun waitFor(): Int = remote.waitFor()

        override fun exitValue(): Int = remote.exitValue()

        override fun destroy() {
            runCatching { remote.destroy() }
        }
    }

    /** 三态描述：供 UI 展示"为什么不可用 / 怎么开启"。 */
    fun statusDescription(): String = when (backend) {
        Backend.ROOT -> "已通过 root 授权"
        Backend.SHIZUKU -> "已通过 Shizuku 授权"
        Backend.NONE -> when {
            shizukuInstalled && !shizukuRunning -> "Shizuku 已安装但未启动服务"
            shizukuInstalled && shizukuRunning && !shizukuGranted -> "Shizuku 待授权"
            else -> "未安装 Shizuku，也未获取 root"
        }
    }

    private companion object {
        const val TAG = "PrivilegedShell"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** 特权命令退出等待上限（超时强杀，防止悬挂进程阻塞采集）。 */
        const val EXIT_TIMEOUT_MS = 5_000L

        /** stderr 读取线程 join 上限（强杀后管道 EOF，通常远小于该值）。 */
        const val STDERR_JOIN_MS = 2_000L
    }
}
