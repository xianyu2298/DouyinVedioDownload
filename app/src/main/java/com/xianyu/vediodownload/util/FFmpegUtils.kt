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