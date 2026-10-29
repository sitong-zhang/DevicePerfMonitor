package com.devperf.monitor.data

import android.app.Application
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 公告拉取（1.0.22 新增）：从仓库根目录的 announcement.json 读最新公告。
 *
 * 与版本检查一样，是应用的联网路径之一，读取服务端公告数据。
 * 用内置 HttpURLConnection + org.json，不引入联网依赖。文件不存在（404）或网络
 * 失败都返回 null，不阻塞主流程。
 */
class AnnouncementRepository(private val app: Application) {

    private val repo = "sitong-zhang/DevicePerfMonitor"
    private val apiUrl =
        "https://api.github.com/repos/$repo/contents/announcement.json"

    /** 拉取最新公告；任何失败都返回 null（调用方不显示公告）。 */
    suspend fun latestAnnouncement(): AppAnnouncement? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DevicePerfMonitor-Announcement")
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode != 200) {
                Log.w(TAG, "公告拉取失败：HTTP ${conn.responseCode}")
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val decoded = String(
                Base64.decode(
                    json.optString("content", "").replace("\n", ""),
                    Base64.DEFAULT,
                ),
                Charsets.UTF_8,
            )
            val obj = JSONObject(decoded)
            val id = obj.optString("id", "")
            if (id.isBlank()) return@withContext null
            AppAnnouncement(
                id = id,
                title = obj.optString("title", ""),
                content = obj.optString("content", ""),
            )
        } catch (e: Exception) {
            Log.w(TAG, "公告拉取异常", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val TAG = "AnnouncementRepository"
    }
}

/** 一条公告。 */
data class AppAnnouncement(
    val id: String,
    val title: String,
    val content: String,
)
