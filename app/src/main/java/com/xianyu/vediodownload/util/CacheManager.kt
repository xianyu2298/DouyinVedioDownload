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
 * - "已下载内容"占用 = Movies/DouyinDownloader/ + Pictures/DouyinDownloader/
 *   这两个壳目录下的所有子目录和文件的字节数（壳目录本身保留，不计入）。
 *   加上合成 Live 图所在的 Pictures/LivePhotos/ 目录（不在 DouyinDownloader/ 壳下）。
 * - 壳目录下的具体内容：
 *   - Movies/DouyinDownloader/                        视频下载
 *   - Movies/DouyinDownloader/LivePicturesMovies/     Live 动态短片
 *   - Pictures/DouyinDownloader/Pictures/             图片下载 + Live 静态封面
 *   - Pictures/LivePhotos/                            合成 Live 图（直接挂在 Pictures/ 下）
 * - 清空策略（壳目录本身始终保留）：
 *   - Android 10+：通过 MediaStore 删除（系统同步清理 MediaStore 与物理文件）
 *   - Android 9-：逐个 File.delete()
 */
object CacheManager {

    private const val ALBUM_NAME = "DouyinDownloader"
    private const val LIVE_PHOTOS_DIR = "LivePhotos"

    fun rootDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        ALBUM_NAME,
    )

    /** Movies 根目录：/sdcard/Movies/DouyinDownloader/（视频类 + Live 动态短片）。 */
    fun moviesRootDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        ALBUM_NAME,
    )

    /** 合成 Live 图根目录：/sdcard/Pictures/LivePhotos/（不在 DouyinDownloader/ 壳下）。 */
    fun livePhotosRootDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        LIVE_PHOTOS_DIR,
    )

    /**
     * 用于"已下载内存占用"统计的所有壳目录：
     *   - Movies/DouyinDownloader/
     *   - Pictures/DouyinDownloader/
     *   - Pictures/LivePhotos/   （合成 Live 图，壳目录本身就是数据目录）
     */
    private fun downloadRoots(): List<File> = listOf(moviesRootDir(), rootDir(), livePhotosRootDir())

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

    /**
     * 已下载内容占用的总字节数 = Movies/DouyinDownloader/ + Pictures/DouyinDownloader/
     * 这两个壳目录下的所有子目录和文件的字节数，加上合成 Live 图所在的 Pictures/LivePhotos/
     * 数据目录本身的字节数（壳目录本身保留，不计入）。
     */
    suspend fun computeDownloadBytes(): Long = withContext(Dispatchers.IO) {
        var total = 0L
        for (root in downloadRoots()) {
            if (!root.exists()) continue
            if (root == livePhotosRootDir()) {
                // LivePhotos/ 直接放合成 Live 图（.MP.JPG），目录本身就是数据目录
                total += root.sumBytes()
            } else {
                // 两个 DouyinDownloader/ 壳目录：壳本身不计，只统计壳下的子目录
                (root.listFiles() ?: emptyArray())
                    .filter { it.isDirectory }
                    .forEach { sub -> total += sub.sumBytes() }
            }
        }
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
     * 清空 Movies/DouyinDownloader/ 与 Pictures/DouyinDownloader/ 这两个壳目录下的
     * 所有下载文件（普通视频 + Live 动态短片 + 图片 + Live 静态封面），以及
     * Pictures/LivePhotos/ 下的合成 Live 图（目录本身保留）。
     *
     * 注意：壳目录本身保留，下次下载时 App 会自动重建。
     * - Android 10+：通过 MediaStore 删除（系统会同步清理物理文件，壳目录不动）
     * - Android 9-：逐文件 File.delete()（子目录和壳目录都不动）
     *
     * 返回被释放的字节数（Movies + Pictures + LivePhotos 三边合计）。
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
        // 视频类：Movies/DouyinDownloader/ 下的普通视频 + Live 动态短片
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

        // 图片类：Pictures/DouyinDownloader/Pictures/ 下的图片 + Live 静态封面
        try {
            context.contentResolver.delete(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("${picturesMediaStorePrefix()}Pictures/%"),
            )
        } catch (_: Throwable) { /* ignore */ }

        // 合成 Live 图：Pictures/LivePhotos/（不在 DouyinDownloader/ 壳目录下）
        try {
            context.contentResolver.delete(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_PICTURES}/$LIVE_PHOTOS_DIR/%"),
            )
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun clearAllDownloadsLegacy() {
        // 各壳目录 + Pictures/LivePhotos/ 数据目录，按各自结构清理
        for (root in downloadRoots()) {
            if (!root.exists()) continue
            if (root == livePhotosRootDir()) {
                // LivePhotos/ 直接放文件：目录本身保留，逐文件删除
                root.listFiles()?.forEach { f -> runCatching { f.delete() } }
            } else {
                // 两个 DouyinDownloader/ 壳目录：每个子目录里的文件逐个删；子目录和壳目录都不动
                (root.listFiles() ?: emptyArray())
                    .filter { it.isDirectory }
                    .forEach { sub ->
                        sub.listFiles()?.forEach { f ->
                            runCatching { f.delete() }
                        }
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