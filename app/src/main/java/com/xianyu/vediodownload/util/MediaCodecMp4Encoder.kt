package com.xianyu.vediodownload.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 将视频重新封装为 MP4（remux），不改编码格式。
 *
 * 适用场景：源视频已经是 MP4 可封装的编码（H.264 / HEVC / MPEG-4 Part 2），
 *         仅需要把容器格式标准化为 Motion Photo 兼容的 MP4。
 *
 * 优势：速度极快（无解码/编码），无画质损失。
 *
 * 限制：VP9 / AV1 等 MP4 不通用或 MediaMuxer 不支持的编码会返回 false。
 *      本工程的 ffmpeg-kit 未编译 libx264，无法软转码，故不走重编码兜底。
 */
object MediaCodecMp4Encoder {

    private const val TAG = "Mp4Encoder"

    /**
     * 允许原样封装进 MP4 的视频编码。
     *
     * HEVC 从 API 24 起可由 MediaMuxer 写入 MP4，相册端（三星 / 谷歌）也能硬解，
     * 因此不再像早期版本那样只放行 H.264 —— 否则 HEVC 源会直接合成失败。
     */
    private val PASS_THROUGH_MIMES = setOf("video/avc", "video/hevc", "video/mp4v-es")

    /**
     * 该视频编码能否直通封装（UI 预检与编码器共用同一份白名单，避免两处判断不一致）
     */
    fun isCodecSupported(mime: String?): Boolean =
        mime != null && mime.lowercase() in PASS_THROUGH_MIMES

    /**
     * @param inputPath 输入视频路径
     * @param outputPath 输出 MP4 路径
     * @param videoBitrate 视频码率（remux 模式忽略）
     * @param audioBitrate 音频码率（remux 模式忽略）
     * @param maxHeight 最大高度（remux 模式忽略）
     * @param onProgress 进度回调 0-100
     */
    fun encode(
        inputPath: String,
        outputPath: String,
        videoBitrate: Int,
        audioBitrate: Int,
        maxHeight: Int? = null,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        val inputFile = File(inputPath)
        if (!inputFile.exists() || inputFile.length() == 0L) {
            Log.e(TAG, "input file missing: $inputPath")
            return false
        }

        // 删除已存在输出
        File(outputPath).delete()

        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)

        try {
            val videoTrackIdx = findTrack(extractor, "video/")
            val audioTrackIdx = findTrack(extractor, "audio/")

            if (videoTrackIdx < 0) {
                Log.e(TAG, "no video track")
                return false
            }

            val videoMime = extractor.getTrackFormat(videoTrackIdx)
                .getString(MediaFormat.KEY_MIME) ?: ""

            // 编码白名单：只要 MediaMuxer 能封装进 MP4、相册能解码，就允许原样直通。
            // 早期版本只放行 H.264，会导致 HEVC 源在合成阶段直接失败。
            if (!isCodecSupported(videoMime)) {
                Log.e(TAG, "video codec $videoMime not in pass-through whitelist, abort remux")
                return false
            }

            Log.i(TAG, "remux mode: $videoMime passthrough")

            // 总时长
            val durationUs = try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(inputPath)
                val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                retriever.release()
                d * 1000
            } catch (e: Exception) {
                0L
            }

            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 处理旋转
            try {
                val rotation = extractor.getTrackFormat(videoTrackIdx)
                    .getInteger(MediaFormat.KEY_ROTATION)
                if (rotation != 0) {
                    muxer.setOrientationHint(rotation)
                }
            } catch (_: Exception) { /* KEY_ROTATION 不存在时忽略 */ }

            // 添加视频轨
            extractor.selectTrack(videoTrackIdx)
            val videoFormat = extractor.getTrackFormat(videoTrackIdx)
            val outVideoIdx = muxer.addTrack(videoFormat)
            Log.i(TAG, "video track added, mime=$videoMime, idx=$outVideoIdx")

            // 添加音频轨（如果存在）。不按编码过滤：能封装就原样带上，
            // 只有 MediaMuxer 明确拒绝该编码时才降级为无音轨（并在日志里留痕）。
            var outAudioIdx = -1
            if (audioTrackIdx >= 0) {
                extractor.selectTrack(audioTrackIdx)
                val audioFormat = extractor.getTrackFormat(audioTrackIdx)
                val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
                outAudioIdx = try {
                    muxer.addTrack(audioFormat).also {
                        Log.i(TAG, "audio track added, mime=$audioMime, idx=$it")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "muxer rejected audio mime=$audioMime, fallback to no-audio", e)
                    extractor.unselectTrack(audioTrackIdx)
                    -1
                }
            }

            muxer.start()

            // 写入 sample
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            var maxPtsUs = 0L
            var lastProgressUs = 0L

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val trackIdx = extractor.sampleTrackIndex
                val pts = extractor.sampleTime
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = pts

                val targetTrack = when (trackIdx) {
                    videoTrackIdx -> outVideoIdx
                    audioTrackIdx -> outAudioIdx
                    else -> -1
                }

                if (targetTrack >= 0) {
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(targetTrack, buffer, bufferInfo)
                    if (pts > maxPtsUs) maxPtsUs = pts
                }
                extractor.advance()

                if (durationUs > 0 && (pts - lastProgressUs) > 1_000_000) {
                    lastProgressUs = pts
                    val pct = ((pts.toDouble() / durationUs) * 100).toInt().coerceIn(0, 99)
                    onProgress?.invoke(pct)
                }
            }

            muxer.stop()
            muxer.release()

            Log.i(TAG, "remux done, output=$outputPath, size=${File(outputPath).length()}")
            onProgress?.invoke(100)
            return File(outputPath).exists() && File(outputPath).length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "encode exception", e)
            return false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }
}