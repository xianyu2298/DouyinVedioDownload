package com.xianyu.vediodownload.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Live 图（Google Motion Photo）合成器
 *
 * 对齐 https://github.com/hellodk34/make_motion_photo 的 Python 实现
 */
object LivePhotoMaker {

    private const val TAG = "LivePhotoMaker"

    data class QualityPreset(
        val crf: Int,
        val audioBitrate: String,
        val preset: String?
    )

    val QUALITY_COMPACT = QualityPreset(crf = 28, audioBitrate = "96k", preset = null)
    val QUALITY_BALANCED = QualityPreset(crf = 23, audioBitrate = "128k", preset = null)
    val QUALITY_PRISTINE = QualityPreset(crf = 18, audioBitrate = "256k", preset = null)

    /** 分辨率映射：仅降采样，不升采样 */
    private val RESIZE_MAP = mapOf(
        "480p" to 480,
        "720p" to 720,
        "1080p" to 1080,
        "1440p" to 1440,
        "2160p" to 2160
    )

    /**
     * 合成 Live 图
     *
     * @param context Android 上下文
     * @param videoPath 输入视频路径
     * @param coverPath 可选的封面图片路径（如果为 null，从视频中按 presentationTimestampUs 抽帧）
     * @param outputFileName 输出文件名（不含扩展名）
     * @param quality 质量预设
     * @param presentationTimestampUs 视频开始播放的时间戳（微秒），默认 1000000（1秒后开始）
     * @param resize 输出视频高度（480p/720p/1080p/1440p/2160p），null 则保持原始分辨率
     * @param onProgress 进度回调（0-100）
     * @return 合成后的文件路径，失败返回 null
     */
    fun createMotionPhoto(
        context: Context,
        videoPath: String,
        coverPath: String? = null,
        outputFileName: String,
        quality: QualityPreset = QUALITY_BALANCED,
        presentationTimestampUs: Long = 1_000_000,
        resize: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): String? {
        val tmpDir = File(context.cacheDir, "live_photo_tmp")
        tmpDir.mkdirs()

        try {
            onProgress?.invoke(5)
            Log.i(TAG, "=== start createMotionPhoto ===")
            Log.i(TAG, "videoPath=$videoPath, coverPath=$coverPath, resize=$resize")

            // 1. 解析 resize 参数
            val targetHeight = resize?.let { RESIZE_MAP[it] }
            if (resize != null && targetHeight == null) {
                Log.e(TAG, "Invalid resize: $resize")
                return null
            }
            Log.i(TAG, "targetHeight=$targetHeight")

            // 2. 提取或加载封面图片
            val coverFile = File(tmpDir, "cover.jpg")
            if (coverPath != null && File(coverPath).exists()) {
                File(coverPath).copyTo(coverFile, overwrite = true)
                Log.i(TAG, "cover copied from $coverPath")
            } else {
                val ok = extractCoverFrame(context, videoPath, coverFile, presentationTimestampUs)
                Log.i(TAG, "extractCoverFrame=$ok, file=${coverFile.absolutePath}, exists=${coverFile.exists()}, size=${if (coverFile.exists()) coverFile.length() else -1}")
                if (!ok || !coverFile.exists()) return null
            }
            onProgress?.invoke(25)

            // 3. 编码视频
            val encodedMp4 = File(tmpDir, "video.mp4")
            if (!encodeVideo(
                    context = context,
                    inputPath = videoPath,
                    outputFile = encodedMp4,
                    quality = quality,
                    targetHeight = targetHeight,
                    onProgress = onProgress
                )
            ) {
                Log.e(TAG, "encodeVideo failed")
                return null
            }
            val mp4Size = encodedMp4.length()
            Log.i(TAG, "encoded MP4 size=$mp4Size")
            onProgress?.invoke(75)

            // 4. 生成 XMP APP1 段
            val xmpSegment = buildXmpApp1Segment(mp4Size, presentationTimestampUs)
            Log.i(TAG, "XMP segment size=${xmpSegment.size}")

            // 5. 将 XMP 注入 JPEG
            val jpegWithXmp = File(tmpDir, "with_xmp.jpg")
            injectXmp(coverFile, xmpSegment, jpegWithXmp)
            Log.i(TAG, "after injectXmp, size=${jpegWithXmp.length()}")

            // 6. 拼接 JPEG + MP4
            val outputFile = File(tmpDir, "output.mp.jpg")
            concatenateFiles(jpegWithXmp, encodedMp4, outputFile)
            Log.i(TAG, "final output size=${outputFile.length()}")
            onProgress?.invoke(95)

            // 7. 保存到相册
            val finalPath = saveToGallery(context, outputFile, outputFileName)
            Log.i(TAG, "saved to gallery: $finalPath")
            onProgress?.invoke(100)

            return finalPath
        } catch (e: Exception) {
            Log.e(TAG, "createMotionPhoto exception", e)
            return null
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * 提取视频封面帧（与 Python 脚本保持一致）
     * 使用 ffmpeg: ffmpeg -y -i video -ss <ts> -vframes 1 -q:v 2 cover.jpg
     */
    private fun extractCoverFrame(
        context: Context,
        videoPath: String,
        outputFile: File,
        timestampUs: Long
    ): Boolean {
        val timestampSec = timestampUs / 1_000_000.0
        return FFmpegUtils.extractFrame(
            context = context,
            inputPath = videoPath,
            outputPath = outputFile.absolutePath,
            timestamp = timestampSec,
            quality = 2
        )
    }

    /**
     * 编码视频为 MP4
     * 与 Python 脚本的 ffmpeg 命令保持一致：
     *   ffmpeg -y -i input -c:v libx264 [-preset X] -crf X -pix_fmt yuv420p
     *          -c:a aac -b:a X [-vf scale=-2:H] -movflags +faststart output.mp4
     */
    private fun encodeVideo(
        context: Context,
        inputPath: String,
        outputFile: File,
        quality: QualityPreset,
        targetHeight: Int? = null,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        return FFmpegUtils.encodeToMp4(
            context = context,
            inputPath = inputPath,
            outputPath = outputFile.absolutePath,
            crf = quality.crf,
            audioBitrate = quality.audioBitrate,
            preset = quality.preset,
            maxHeight = targetHeight,
            onProgress = { progress ->
                onProgress?.invoke(25 + (progress * 0.5).toInt())
            }
        )
    }

    /**
     * 构建 XMP APP1 段
     * 与 Python 脚本的 make_xmp_app1_segment 完全一致
     */
    private fun buildXmpApp1Segment(mp4Size: Long, presentationTimestampUs: Long): ByteArray {
        val xmpStr = buildXmp(mp4Size, presentationTimestampUs)
        val xmpBytes = xmpStr.toByteArray(Charsets.UTF_8)

        // APP1 marker: 0xFF 0xE1
        // Identifier: "http://ns.adobe.com/xap/1.0/\x00" (29 bytes)
        val identifier = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val app1Data = identifier + xmpBytes

        // Length: 2 bytes (includes length field itself, but not the marker)
        val length = app1Data.size + 2

        val buffer = ByteBuffer.allocate(2 + 2 + app1Data.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(0xFF.toByte())
        buffer.put(0xE1.toByte())
        buffer.putShort(length.toShort())
        buffer.put(app1Data)

        return buffer.array()
    }

    /**
     * 构建 XMP 元数据字符串
     * 严格对齐 Python 脚本 build_xmp 的格式
     */
    private fun buildXmp(mp4Size: Long, presentationTimestampUs: Long): String {
        return """
<?xml version="1.0" encoding="UTF-8"?>
 <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.6.0">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
   <rdf:Description rdf:about=""
    xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
    Camera:MotionPhotoVersion="1"
    Camera:MotionPhoto="1"
    Camera:MotionPhotoPresentationTimestampUs="$presentationTimestampUs">
    <Container:Directory>
     <rdf:Seq>
      <rdf:li rdf:parseType="Resource">
       <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/>
      </rdf:li>
      <rdf:li rdf:parseType="Resource">
       <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$mp4Size"/>
      </rdf:li>
     </rdf:Seq>
    </Container:Directory>
   </rdf:Description>
  </rdf:RDF>
 </x:xmpmeta>
 """.trimIndent()
    }

    /**
     * 将 XMP 注入 JPEG 文件
     * 与 Python 脚本的 inject_xmp 逻辑一致：
     *   - 验证 SOI
     *   - 跳过现有 APP 段 (0xE0-0xEF)、DQT (0xDB)、DHT (0xC4)、COM (0xFE) 等
     *   - 在 SOI 之后插入 XMP
     */
    private fun injectXmp(inputJpeg: File, xmpSegment: ByteArray, outputJpeg: File) {
        val data = inputJpeg.readBytes()

        // 验证 JPEG SOI 标记
        if (data.size < 2 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) {
            throw IllegalArgumentException("Not a valid JPEG file")
        }

        // 找到插入位置：跳过 SOI 和现有的标记段
        var pos = 2
        while (pos < data.size) {
            if (data[pos] != 0xFF.toByte()) break

            val marker = data[pos + 1]

            // SOI 不应再次出现
            if (marker == 0xD8.toByte()) break
            // EOI - 到达文件末尾
            if (marker == 0xD9.toByte()) break
            // 填充字节
            if (marker == 0x00.toByte()) {
                pos += 2
                continue
            }

            // 跳过带长度的标记段
            // APP0-APP15 (0xE0-0xEF), DQT (0xDB), DHT (0xC4), COM (0xFE),
            // SOF0-15 (0xC0-0xCF, except DHT=0xC4), DRI (0xDD)
            val hasLength = when {
                marker.toInt() and 0xF0 == 0xE0 -> true  // APPn
                marker == 0xDB.toByte() -> true          // DQT
                marker == 0xC4.toByte() -> true          // DHT
                marker == 0xFE.toByte() -> true          // COM
                marker.toInt() and 0xF0 == 0xC0 && marker != 0xC4.toByte() -> true // SOF
                marker == 0xDD.toByte() -> true          // DRI
                else -> false
            }

            if (hasLength) {
                if (pos + 4 > data.size) break
                val segLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or
                        (data[pos + 3].toInt() and 0xFF)
                pos += 2 + segLen
                continue
            }

            // SOS (0xDA) 或其他无长度标记，停止扫描
            break
        }

        // 插入 XMP：在 SOI 之后、现有段之前
        val result = ByteArray(data.size + xmpSegment.size)
        System.arraycopy(data, 0, result, 0, pos)
        System.arraycopy(xmpSegment, 0, result, pos, xmpSegment.size)
        System.arraycopy(data, pos, result, pos + xmpSegment.size, data.size - pos)

        outputJpeg.writeBytes(result)
    }

    /**
     * 拼接两个文件：JPEG + MP4
     */
    private fun concatenateFiles(file1: File, file2: File, output: File) {
        FileOutputStream(output).use { fos ->
            FileInputStream(file1).use { fis -> fis.copyTo(fos) }
            FileInputStream(file2).use { fis -> fis.copyTo(fos) }
        }
    }

    /**
     * 保存到相册
     */
    private fun saveToGallery(context: Context, file: File, fileName: String): String? {
        val finalName = "$fileName.MP.JPG"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, finalName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/LivePhotos"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            ) ?: return null

            resolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(file).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            )
            val livePhotosDir = File(picturesDir, "LivePhotos")
            livePhotosDir.mkdirs()

            val destFile = File(livePhotosDir, finalName)
            file.copyTo(destFile, overwrite = true)

            MediaStore.Images.Media.insertImage(
                context.contentResolver,
                destFile.absolutePath,
                finalName,
                "Live Photo"
            )

            destFile.absolutePath
        }
    }

    /**
     * 将 Android 本地 Uri 转换为文件路径
     */
    fun getFilePathFromUri(context: Context, uri: Uri): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val tempFile = File(
                        context.cacheDir,
                        "temp_${System.currentTimeMillis()}.mp4"
                    )
                    FileOutputStream(tempFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    tempFile.absolutePath
                }
            } else {
                uri.path
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
