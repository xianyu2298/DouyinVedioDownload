package com.xianyu.vediodownload.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder


/**
 * Live 图（Google Motion Photo）合成器
 *
 * 实现原理：
 * 1. 从视频中提取一帧作为 JPEG 封面
 * 2. 将视频编码为 MP4（H.264 + AAC）
 * 3. 生成 XMP 元数据，描述 Motion Photo 结构
 * 4. 将 XMP 注入 JPEG APP1 段（跳过已有标记段，插入到正确位置）
 * 5. 将带 XMP 的 JPEG 与 MP4 拼接为 .MP.JPG 文件
 *
 * 严格对齐 Python 脚本 make_motion_photo.py 的逻辑。
 */
object LivePhotoMaker {

    private const val TAG = "LivePhotoMaker"

    data class QualityPreset(
        val crf: Int,
        val audioBitrate: String,
        val preset: String
    )

    val QUALITY_COMPACT = QualityPreset(crf = 28, audioBitrate = "96k", preset = "medium")
    val QUALITY_BALANCED = QualityPreset(crf = 23, audioBitrate = "128k", preset = "medium")
    val QUALITY_PRISTINE = QualityPreset(crf = 18, audioBitrate = "256k", preset = "slow")

    /**
     * 合成 Live 图
     *
     * @param context Android 上下文
     * @param videoPath 视频文件路径
     * @param coverPath 可选的封面图片路径（如果为 null，从视频中提取第一帧）
     * @param outputFileName 输出文件名（不含扩展名）
     * @param quality 质量预设
     * @param presentationTimestampUs 视频开始播放的时间戳（微秒），默认 1000000（1秒后开始）
     * @param resize 可选的输出视频高度预设（"480p"/"720p"/"1080p"/"1440p"/"2160p"），不放大
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

            // 1. 提取或加载封面图片
            val coverFile = File(tmpDir, "cover.jpg")
            if (coverPath != null && File(coverPath).exists()) {
                File(coverPath).copyTo(coverFile, overwrite = true)
            } else {
                extractCoverFrame(context, videoPath, coverFile, presentationTimestampUs)
            }
            onProgress?.invoke(25)

            // 2. 编码视频为 MP4
            val encodedMp4 = File(tmpDir, "video.mp4")
            val maxHeight = resize?.let { RESIZE_MAP[it] }
            if (resize != null && maxHeight == null) {
                throw IllegalArgumentException("Unknown resize preset: $resize")
            }
            if (!encodeVideo(context, videoPath, encodedMp4, quality, maxHeight, onProgress)) {
                return null
            }
            val mp4Size = encodedMp4.length()
            onProgress?.invoke(75)

            // 3. 生成 XMP APP1 段（marker + length + identifier + xmp payload）
            val xmpSegment = buildXmpApp1Segment(mp4Size, presentationTimestampUs)

            // 4. 将 XMP 注入 JPEG：跳过已有的标记段，在正确位置插入
            val jpegWithXmp = File(tmpDir, "with_xmp.jpg")
            injectXmp(coverFile, xmpSegment, jpegWithXmp)
            onProgress?.invoke(85)

            // 5. 拼接 JPEG + MP4
            val outputFile = File(tmpDir, "output.mp.jpg")
            concatenateFiles(jpegWithXmp, encodedMp4, outputFile)
            onProgress?.invoke(95)

            // 6. 保存到相册
            val finalPath = saveToGallery(context, outputFile, outputFileName)
            onProgress?.invoke(100)
            return finalPath
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * 提取视频封面帧
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
     */
    private fun encodeVideo(
        context: Context,
        inputPath: String,
        outputFile: File,
        quality: QualityPreset,
        maxHeight: Int? = null,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        return FFmpegUtils.encodeToMp4(
            context = context,
            inputPath = inputPath,
            outputPath = outputFile.absolutePath,
            crf = quality.crf,
            audioBitrate = quality.audioBitrate,
            preset = quality.preset,
            maxHeight = maxHeight,
            onProgress = { progress ->
                onProgress?.invoke(25 + (progress * 0.5).toInt())
            }
        )
    }

    /**
     * 分辨率预设映射（与 Python 脚本 RESIZE_MAP 保持一致）
     */
    private val RESIZE_MAP = mapOf(
        "480p" to 480,
        "720p" to 720,
        "1080p" to 1080,
        "1440p" to 1440,
        "2160p" to 2160
    )

    /**
     * 构建 XMP 元数据字符串
     *
     * 严格对齐 Python build_xmp() 的输出格式（缩进、换行、xmlns）。
     */
    private fun buildXmp(mp4Size: Long, presentationTimestampUs: Long): String {
        return """<x:xmpmeta xmlns:x="adobe:ns:meta/">
    <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
             xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
             xmlns:Container="http://ns.google.com/photos/1.0/container/"
             xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
        <rdf:Description
            Camera:MotionPhoto="1"
            Camera:MotionPhotoVersion="1"
            Camera:MotionPhotoPresentationTimestampUs="$presentationTimestampUs">
            <Container:Directory>
                <rdf:Seq>
                    <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Mime="image/jpeg"
                                        Item:Semantic="Primary"
                                        Item:Length="0"
                                        Item:Padding="0"/>
                    </rdf:li>
                    <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Mime="video/mp4"
                                        Item:Semantic="MotionPhoto"
                                        Item:Length="$mp4Size"
                                        Item:Padding="0"/>
                    </rdf:li>
                </rdf:Seq>
            </Container:Directory>
        </rdf:Description>
    </rdf:RDF>
</x:xmpmeta>"""
    }

    /**
     * 构建 XMP APP1 段
     *
     * 结构：FF E1 | Length(2 bytes, big-endian) | "http://ns.adobe.com/xap/1.0/\0" | XMP
     * 严格使用 ByteBuffer 保证大端字节序，对齐 Python 的 struct.pack(">H", length)。
     */
    private fun buildXmpApp1Segment(mp4Size: Long, presentationTimestampUs: Long): ByteArray {
        val xmpStr = buildXmp(mp4Size, presentationTimestampUs)
        val xmpBytes = xmpStr.toByteArray(Charsets.UTF_8)

        // APP1 标识符："http://ns.adobe.com/xap/1.0/\0"（29 字节，US-ASCII）
        val identifier = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)

        val app1Data = identifier + xmpBytes
        // length 字段包含 length 本身 2 字节，但不包含 marker
        val length = app1Data.size + 2

        // 使用 ByteBuffer 显式指定 BIG_ENDIAN 字节序写入 2 字节长度
        val lengthBytes = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(length.toShort()).array()

        val result = ByteArray(2 + 2 + app1Data.size)
        result[0] = 0xFF.toByte()
        result[1] = 0xE1.toByte()
        System.arraycopy(lengthBytes, 0, result, 2, 2)
        System.arraycopy(app1Data, 0, result, 4, app1Data.size)
        return result
    }

    /**
     * 将 XMP APP1 段注入 JPEG
     *
     * 对齐 Python inject_xmp() 实际行为：
     * 1. 校验 SOI（FF D8）
     * 2. 无条件把 XMP 段插在 SOI 之后（Python 脚本虽然算了 pos，但最终用的是 data[:2] + xmp + data[2:]）
     *
     * 这样保证 XMP APP1 紧跟 SOI，是所有 JPEG 处理器的标准位置，
     * 其他相册（Samsung/Xiaomi/华为等）的 Motion Photo 检测器期望 XMP 在所有 APP 段之前。
     */
    private fun injectXmp(inputJpeg: File, xmpSegment: ByteArray, outputJpeg: File) {
        val data = inputJpeg.readBytes()

        // 校验 JPEG SOI 标记
        if (data.size < 2 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) {
            throw IllegalArgumentException("Not a valid JPEG file")
        }

        // 严格对齐 Python: result = data[:2] + xmp_segment + data[2:]
        // 即把 XMP 段无条件插在 SOI 之后
        val result = ByteArray(data.size + xmpSegment.size)
        System.arraycopy(data, 0, result, 0, 2)
        System.arraycopy(xmpSegment, 0, result, 2, xmpSegment.size)
        System.arraycopy(data, 2, result, 2 + xmpSegment.size, data.size - 2)

        outputJpeg.writeBytes(result)
    }

    /**
     * 拼接两个文件
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
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LivePhotos")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null
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
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val livePhotosDir = File(picturesDir, "LivePhotos")
            livePhotosDir.mkdirs()
            val destFile = File(livePhotosDir, finalName)
            file.copyTo(destFile, overwrite = true)
            // 通知媒体库扫描该文件
            context.sendBroadcast(
                android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    .setData(Uri.fromFile(destFile))
            )
            destFile.absolutePath
        }
    }

    /**
     * 将 Android 本地 Uri 转换为文件路径
     */
    fun getFilePathFromUri(context: Context, uri: Uri): String? {
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val tempFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.mp4")
                        FileOutputStream(tempFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                        tempFile.absolutePath
                    }
                }
                else -> {
                    uri.path
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
