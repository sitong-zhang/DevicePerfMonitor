package com.devperf.monitor.data

import android.app.Application
import android.util.Log
import com.devperf.monitor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内版本检查与更新下载。
 *
 * 唯一的联网路径（1.0.15 新增）：打开 App 时查询 GitHub Releases 最新版，
 * 有新版本就提示用户。仅读取公开 Release 元数据，不上报任何数据。
 * 用内置 HttpURLConnection + org.json，不引入任何联网依赖。
 */
class UpdateRepository(private val app: Application) {

    private val repo = "sitong-zhang/DevicePerfMonitor"
    private val apiUrl = "https://api.github.com/repos/$repo/releases/latest"

    /**
     * 查询最新 Release。返回 null 表示网络/解析失败（调用方按「无更新」降级处理，
     * 不阻塞主流程）。
     */
    suspend fun latestRelease(): AppRelease? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DevicePerfMonitor-UpdateCheck")
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "版本检查失败：HTTP ${conn.responseCode}")
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val tag = json.getString("tag_name")
            val version = tag.removePrefix("v")
            val notes = json.optString("body", "")
            val releaseUrl = json.optString("html_url", "")
            val assets = json.optJSONArray("assets") ?: JSONArray()
            val apkUrl = pickApk(assets, version)
            AppRelease(tag, version, notes, releaseUrl, apkUrl)
        } catch (e: Exception) {
            Log.w(TAG, "版本检查异常", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 按当前 flavor 选对应 APK 资产（glass 用主包，plain 用 -plain 包）。 */
    private fun pickApk(assets: JSONArray, version: String): String? {
        val suffix = if (BuildConfig.LIQUID_GLASS) "release.apk" else "plain-release.apk"
        // 先精确匹配版本号，避免拿到旧资产
        for (i in 0 until assets.length()) {
            val obj = assets.getJSONObject(i)
            val name = obj.optString("name", "")
            val url = obj.optString("browser_download_url", "")
            if (name.endsWith(suffix) && name.contains(version)) return url
        }
        // 兜底：只匹配后缀
        for (i in 0 until assets.length()) {
            val obj = assets.getJSONObject(i)
            val name = obj.optString("name", "")
            val url = obj.optString("browser_download_url", "")
            if (name.endsWith(suffix)) return url
        }
        return null
    }

    /** 下载 APK 到缓存目录，onProgress 回调 0~100。 */
    suspend fun downloadApk(apkUrl: String, dest: File, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "DevicePerfMonitor-Update")
                    connectTimeout = 10000
                    readTimeout = 30000
                }
                val total = conn.contentLength.takeIf { it > 0 } ?: -1
                conn.inputStream.use { input ->
                    BufferedInputStream(input).use { bis ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(8192)
                            var read: Int
                            var done = 0L
                            while (bis.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                                done += read
                                if (total > 0) onProgress((done * 100 / total).toInt())
                            }
                        }
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }

    fun updatesDir(): File = File(app.cacheDir, "updates").also { it.mkdirs() }

    companion object {
        const val TAG = "UpdateRepository"
    }
}

/** 一个 GitHub Release 的精简视图。 */
data class AppRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val releaseUrl: String,
    /** 当前 flavor 对应的 APK 直链；为 null 表示该 Release 没有匹配的 APK。 */
    val apkUrl: String?,
)

/** 语义化版本比较：latest 是否比 current 新。任意一段更大即更新。 */
fun isNewer(latest: String, current: String): Boolean {
    val a = latest.split('.').map { it.toIntOrNull() ?: 0 }
    val b = current.split('.').map { it.toIntOrNull() ?: 0 }
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
