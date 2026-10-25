package com.devperf.monitor.ui.wallpaper

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.imageResource
import com.devperf.monitor.R
import com.devperf.monitor.data.MonitorSettings
import com.devperf.monitor.data.WallpaperId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 壁纸解析（1.0.26「壁纸自由切换」的唯一读取入口）。
 *
 * 主界面背景层（AppNav）与悬浮窗背景层（OverlayService）共用这一份逻辑：
 * - 内置三张（青蓝 / 极光 / 薄荷）：走资源解码，与 1.0.25 行为一致；
 * - 自定义：字节由设置页复制到 `filesDir/wallpaper_custom.jpg`，这里按需
 *   解码（IO 线程 + inSampleSize 限尺寸，防 OOM），**文件缺失或解码失败
 *   一律回落到内置青蓝**——设置被系统清理过也不能白屏。
 *
 * 不存 Uri：相册返回的 Uri 权限是临时的（PickVisualMedia 不签发持久授权），
 * 所以设置页选图当场复制字节，只把文件路径写进 DataStore。
 */
object Wallpapers {

    /** 自定义壁纸在 filesDir 内的固定文件名。 */
    const val CUSTOM_FILE_NAME = "wallpaper_custom.jpg"

    /** 自定义壁纸解码的最大边长：屏幕两倍足够铺满 + 防低端机 OOM。 */
    private const val MAX_DECODE_EDGE = 2048

    fun customFile(context: Context): File = File(context.filesDir, CUSTOM_FILE_NAME)

    @DrawableRes
    fun drawableRes(id: WallpaperId): Int = when (id) {
        WallpaperId.LIGHT_TEAL -> R.drawable.wallpaper_light
        WallpaperId.AURORA -> R.drawable.wallpaper_aurora
        WallpaperId.MINT -> R.drawable.wallpaper_mint
        WallpaperId.CUSTOM -> R.drawable.wallpaper_light // 占位：CUSTOM 走文件路径
    }

    /**
     * 把相册选中的图片复制成自定义壁纸，返回可持久化的文件绝对路径。
     * 失败（非图片 / IO 错误）返回 null，由调用方忽略（保持当前壁纸）。
     */
    suspend fun importFromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dest = customFile(context)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            // 复制完必须能解码出位图才算有效：拒绝用户误选的非图片文件
            if (!isDecodableImage(dest.absolutePath)) {
                dest.delete()
                return@runCatching null
            }
            dest.absolutePath
        }.getOrNull()
    }

    /** 解码自定义壁纸；失败返回 null（调用方回落到内置）。 */
    fun decodeCustom(path: String): ImageBitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = sampleSizeFor(options.outWidth, options.outHeight)
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
        bitmap.asImageBitmap()
    }.getOrNull()

    private fun isDecodableImage(path: String): Boolean = decodeCustom(path) != null

    /** 计算 inSampleSize：2 的幂，缩小到最长边 <= MAX_DECODE_EDGE。 */
    internal fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var maxEdge = maxOf(width, height)
        while (maxEdge / 2 >= MAX_DECODE_EDGE) {
            maxEdge /= 2
            sample *= 2
        }
        return sample
    }
}

/**
 * 按当前设置解析背景壁纸（Composable，供 AppNav / OverlayService 直接调用）。
 *
 * 自定义图走 [produceState] 在 IO 线程解码，解码完成前先用内置青蓝顶住
 * （一帧的事，用户无感）；失败同样回落，不会黑屏。
 */
@Composable
fun rememberWallpaperImage(settings: MonitorSettings): ImageBitmap {
    val builtIn = when (settings.wallpaper) {
        WallpaperId.LIGHT_TEAL, WallpaperId.CUSTOM -> R.drawable.wallpaper_light
        WallpaperId.AURORA -> R.drawable.wallpaper_aurora
        WallpaperId.MINT -> R.drawable.wallpaper_mint
    }
    // 注意：compose-ui 的 imageResource 是 ImageBitmap.Companion 的扩展，
    // 没有顶层重载（1.11.4 确认），必须走 ImageBitmap.imageResource(id)。
    val fallback: ImageBitmap = ImageBitmap.imageResource(builtIn)
    val customPath = settings.wallpaperCustomPath?.takeIf { settings.wallpaper == WallpaperId.CUSTOM }
    val custom: State<ImageBitmap?> = produceState(initialValue = null, customPath) {
        value = customPath
            ?.takeIf { File(it).isFile }
            ?.let { path ->
                withContext(Dispatchers.IO) { Wallpapers.decodeCustom(path) }
            }
    }
    return custom.value ?: fallback
}
