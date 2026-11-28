package com.xianyu.vediodownload.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * FFmpeg / MediaCodec 工具类
 *
 * 由于本项目打包的 ffmpeg-kit-full-gpl 8.1.2 已禁用 mediacodec 和未编 libx264，
 * 无法直接用 ffmpeg 编码 H.264，转为使用 Android 原生 MediaCodec/MediaMuxer 完成。
 *
 * - extractFrame：抽帧仍走 ffmpeg（jpeg/解码都有 mjpeg/mediacodec 解码器）
 * - encodeToMp4：使用 Android MediaCodec 创建 H.264 + AAC 的 MP4
 * - 音频、视频、容器都使用系统硬编码/硬复用，兼容性最好
 */
object FFmpegUtils {

    private const val TAG = "FFmpegUtils"

    // ==================== 公开 API ====================

    /**
     * 从视频中提取一帧作为 JPEG 图片
     *
     * 注：先尝试 ffmpeg（fast seek），失败/不支持时回退到 MediaMetadataRetriever
     */
    fun extractFrame(
        context: Context,
        inputPath: String,
        outputPath: String,
        timestamp: Double,
        quality: Int = 2
    ): Boolean {
        File(outputPath).parentFile?.mkdirs()

        // 路径预处理
        val realInput = getRealPath(context, inputPath)
        val realOutput = getRealPath(context, outputPath)

        // 方案 1：ffmpeg（无 -ss 在前，直接 -vf select）
        val ffmpegCmd = buildString {
            append("-y -i \"$realInput\" ")
            append("-vf \"select='eq(n\\,0)',scale=iw:-1\" ")  // 抽第一帧（更快）
            append("-vsync vfr ")
            append("-frames:v 1 ")
            append("-q:v $quality ")
            append("\"$realOutput\"")
        }
        if (executeFFmpeg(ffmpegCmd)) {
            return File(realOutput).exists() && File(realOutput).length() > 0
        }

        // 方案 2：MediaMetadataRetriever 回退
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(realInput)
            val bitmap = retriever.getFrameAtTime(
                (timestamp * 1_000_000).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            retriever.release()
            if (bitmap != null) {
                FileOutputStream(realOutput).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                }
                bitmap.recycle()
                File(realOutput).exists() && File(realOutput).length() > 0
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractFrame fallback failed", e)
            false
        }
    }

    /**
     * 使用 Android MediaCodec/MediaMuxer 将视频编码为 MP4 (H.264 + AAC)
     *
     * @param crf 质量参数（20-28）。映射为 H.264 的码率：
     *              crf <=20 -> 5Mbps, 23->3Mbps, 26->1.5Mbps, 28->1Mbps
     * @param audioBitrate 音频比特率 (如 "128k")
     * @param maxHeight 最大高度（缩放，仅降采样）。null 保持原始。
     */
    fun encodeToMp4(
        context: Context,
        inputPath: String,
        outputPath: String,
        crf: Int = 23,
        audioBitrate: String = "128k",
        preset: String? = null,
        maxHeight: Int? = null,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        File(outputPath).parentFile?.mkdirs()
        val realInput = getRealPath(context, inputPath)
        val realOutput = getRealPath(context, outputPath)

        val audioBps = parseBitrate(audioBitrate)
        val videoBps = when {
            crf <= 20 -> 5_000_000
            crf <= 23 -> 3_000_000
            crf <= 26 -> 1_500_000
            else -> 1_000_000
        }

        return try {
            MediaCodecMp4Encoder.encode(
                inputPath = realInput,
                outputPath = realOutput,
                videoBitrate = videoBps,
                audioBitrate = audioBps,
                maxHeight = maxHeight,
                onProgress = onProgress
            )
        } catch (e: Exception) {
            Log.e(TAG, "encodeToMp4 failed", e)
            false
        }
    }

    /**
     * 导出音频文件（**不依赖 libmp3lame**）。
     *
     * 本工程打包的 ffmpeg-kit 未编译 libmp3lame：arm64 的 libffmpeg.so 里没有该编码器，
     * 执行 `-c:a libmp3lame` 会直接报 `Unknown encoder 'libmp3lame'`。
     *
     * 因此改为「优先流复制」：
     *  - 源是 mp3（抖音曲库直链常见）→ 输出 .mp3，无损且瞬时完成
     *  - 源是 AAC（视频音轨）→ 输出 .m4a，同样不重新编码
     *  - 复制失败再退回 AAC 转码（192k）
     *
     * @param outputDir 输出目录
     * @param baseName  不含扩展名的文件名
     * @return 实际生成的文件；失败返回 null（<10KB 视为空轨）
     */
    fun exportAudio(inputPath: String, outputDir: String, baseName: String): File? {
        val input = File(inputPath)
        if (!input.exists() || input.length() == 0L) return null
        File(outputDir).mkdirs()

        val extension = if (looksLikeMp3(input)) "mp3" else "m4a"
        val copied = File(outputDir, "$baseName.$extension")
        val copyCmd = buildString {
            append("-y -i \"${input.absolutePath}\" ")
            append("-vn -c:a copy -map_metadata -1 ")
            append("\"${copied.absolutePath}\"")
        }
        if (executeFFmpeg(copyCmd) && copied.length() > 10 * 1024) {
            return copied
        }
        //noinspection ResultOfMethodCallIgnored
        copied.delete()

        val transcoded = File(outputDir, "$baseName.m4a")
        val transcodeCmd = buildString {
            append("-y -i \"${input.absolutePath}\" ")
            append("-vn -c:a aac -b:a 192k -map_metadata -1 ")
            append("\"${transcoded.absolutePath}\"")
        }
        return if (executeFFmpeg(transcodeCmd) && transcoded.length() > 10 * 1024) {
            transcoded
        } else {
            //noinspection ResultOfMethodCallIgnored
            transcoded.takeIf { it.exists() }?.delete()
            null
        }
    }

    /** 读文件头判断是否 mp3：ID3 标签（"ID3"）或 MPEG 帧同步字（0xFFE0）。 */
    private fun looksLikeMp3(file: File): Boolean {
        return try {
            val head = ByteArray(3)
            val read = FileInputStream(file).use { it.read(head) }
            if (read < 2) return false
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            if (read == 3 && b0 == 0x49 && b1 == 0x44 && (head[2].toInt() and 0xFF) == 0x33) {
                return true
            }
            b0 == 0xFF && (b1 and 0xE0) == 0xE0
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 内部辅助 ====================

    /**
     * 执行 FFmpeg 命令
     */
    private fun executeFFmpeg(
        command: String,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        Log.i(TAG, "FFmpeg command: $command")
        val session = FFmpegKit.execute(command)
        val returnCode = session.returnCode
        val isSuccess = ReturnCode.isSuccess(returnCode)
        Log.i(TAG, "FFmpeg returnCode: $returnCode, success: $isSuccess")
        if (!isSuccess) {
            Log.e(TAG, "FFmpeg FAIL output:\n${session.output}")
        }
        return isSuccess
    }

    private fun parseBitrate(s: String): Int {
        val lower = s.lowercase()
        val num = lower.filter { it.isDigit() }.toIntOrNull() ?: 128
        return when {
            "m" in lower -> num * 1_000_000
            "k" in lower -> num * 1_000
            else -> num
        }
    }

    /**
     * 获取文件的真实路径
     */
    private fun getRealPath(context: Context, path: String): String {
        if (path.startsWith("content://")) {
            val tempFile = File(context.cacheDir, "ffmpeg_temp_${System.currentTimeMillis()}.tmp")
            context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            return tempFile.absolutePath
        }
        if (path.startsWith("file://")) return path.removePrefix("file://")
        return path
    }

    /**
     * 清理 FFmpeg 生成的临时文件
     */
    fun clearTempFiles(context: Context) {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("ffmpeg_") || file.name.startsWith("temp_")) {
                file.delete()
            }
        }
    }
}