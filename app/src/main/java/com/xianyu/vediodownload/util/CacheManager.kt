package com.xianyu.vediodownload.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 清空缓存 / 下载相关工具。
 *
 * - "App 缓存" = cacheDir + externalCacheDir（不会动 code/data/files 目录）
 * - "已下载内容"：
 *   - Movies/DouyinDownloader/                视频下载
 *   - Movies/DouyinDownloader/LivePicturesMovies/  Live 动态短片
 *   - Pictures/DouyinDownloader/Pictures/    图片下载 + Live 静态封面
 *   - Android 10+：通过 MediaStore 删除（同步清理 MediaStore 与物理文件）
 *   - Android 9-：直接 File.deleteRecursively
 */
object CacheManager {

    private const val ALBUM_NAME = "DouyinDownloader"

    fun rootDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        ALBUM_NAME,
    )

    /** Movies 根目录对应的 MediaStore 前缀（视频类都从这里走）。 */
    private fun moviesMediaStorePrefix(): String =
        "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME/"

    /** Pictures 根目录对应的 MediaStore 前缀（图片类）。 */
    private fun picturesMediaStorePrefix(): String =
        "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME/"

    suspend fun computeAppCacheBytes(context: Context): Long = withContext(Dispatchers.IO) {
        var total = 0L
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
            total += dir.sumBytes()
        }
        total
    }

    suspend fun computeDownloadBytes(): Long = withContext(Dispatchers.IO) {
        val root = rootDir()
        if (!root.exists()) return@withContext 0L
        var total = 0L
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .forEach { sub -> total += sub.sumBytes() }
        total
    }

    suspend fun clearAppCache(context: Context): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
            freed += dir.deleteRecursivelyBytes()
        }
        freed
    }

    /**
     * 清空所有下载：Movies/、Pictures/、LivePicturesMovies/ 三个子目录（包含 MediaStore 记录）。
     * 返回被释放的字节数（近似）。
     */
    suspend fun clearAllDownloads(context: Context): Long = withContext(Dispatchers.IO) {
        // 必须在 delete 之前统计大小
        val freed = computeDownloadBytes()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            clearAllDownloadsQ(context)
        } else {
            clearAllDownloadsLegacy()
        }
        freed
    }

    private fun clearAllDownloadsQ(context: Context) {
        // 视频类：Movies/（普通视频 + Live 动态短片）都属于 Video.Media
        try {
            context.contentResolver.delete(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(
                    "${moviesMediaStorePrefix()}%",
                    "${moviesMediaStorePrefix()}LivePicturesMovies/%",
                ),
            )
        } catch (_: Throwable) { /* ignore */ }

        // 图片类：Pictures/（含 Live 静态封面）
        try {
            context.contentResolver.delete(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("${picturesMediaStorePrefix()}Pictures/%"),
            )
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun clearAllDownloadsLegacy() {
        // 视频在 Movies/DouyinDownloader/，图片在 Pictures/DouyinDownloader/Pictures/
        val moviesRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            ALBUM_NAME,
        )
        val picturesRoot = rootDir()
        for (root in listOf(moviesRoot, picturesRoot)) {
            if (!root.exists()) continue
            (root.listFiles() ?: emptyArray())
                .filter { it.isDirectory }
                .forEach { sub ->
                    sub.listFiles()?.forEach { f ->
                        runCatching { f.delete() }
                    }
                }
        }
    }

    private fun File.sumBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        var total = 0L
        val children = listFiles() ?: return 0L
        for (c in children) total += c.sumBytes()
        return total
    }

    private fun File.deleteRecursivelyBytes(): Long {
        if (!exists()) return 0L
        val bytes = sumBytes()
        if (isDirectory) {
            listFiles()?.forEach { it.deleteRecursivelyBytes() }
        }
        delete()
        return bytes
    }
}

/** 把字节数格式化成 B / KB / MB / GB。 */
fun Long.toReadableSize(): String {
    var v = this.toDouble()
    val units = arrayOf("B", "KB", "MB", "GB")
    var idx = 0
    while (v >= 1024.0 && idx < units.lastIndex) {
        v /= 1024.0
        idx++
    }
    return if (idx == 0) "${v.toLong()} ${units[idx]}"
    else String.format("%.2f %s", v, units[idx])
}