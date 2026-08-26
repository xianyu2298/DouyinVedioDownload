package com.xianyu.vediodownload.ui.screens

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.media.MediaExtractor
import android.media.MediaFormat
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xianyu.vediodownload.util.LivePhotoMaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class LivePhotoComposeViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LivePhotoComposeViewModel::class.java)) {
            return LivePhotoComposeViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

enum class ComposeQuality(val displayName: String, val preset: LivePhotoMaker.QualityPreset) {
    COMPACT("小体积 (快速)", LivePhotoMaker.QUALITY_COMPACT),
    BALANCED("均衡 (推荐)", LivePhotoMaker.QUALITY_BALANCED),
    PRISTINE("高画质 (大文件)", LivePhotoMaker.QUALITY_PRISTINE)
}

data class ComposeUiState(
    val videoUri: Uri? = null,
    val coverUri: Uri? = null,
    val quality: ComposeQuality = ComposeQuality.BALANCED,
    val resize: String? = null,
    val isComposing: Boolean = false,
    val progress: Int = 0,
    val progressMessage: String = "",
    val error: String? = null,
    val successPath: String? = null
)

class LivePhotoComposeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val context get() = getApplication<Application>()

    fun setVideoUri(uri: Uri?) {
        _uiState.update { it.copy(videoUri = uri, error = null, successPath = null) }
    }

    fun setCoverUri(uri: Uri?) {
        _uiState.update { it.copy(coverUri = uri, error = null, successPath = null) }
    }

    fun setQuality(quality: ComposeQuality) {
        _uiState.update { it.copy(quality = quality) }
    }

    fun setResize(resize: String?) {
        _uiState.update { it.copy(resize = resize) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successPath = null) }
    }

    fun startCompose() {
        val state = _uiState.value
        if (state.videoUri == null) {
            _uiState.update { it.copy(error = "请先选择一个视频文件") }
            return
        }

        val codecError = checkVideoCodec(state.videoUri)
        if (codecError != null) {
            _uiState.update { it.copy(error = codecError) }
            return
        }

        _uiState.update {
            it.copy(
                isComposing = true,
                progress = 0,
                progressMessage = "准备中...",
                error = null,
                successPath = null
            )
        }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    composeLivePhoto()
                }

                if (result != null) {
                    _uiState.update {
                        it.copy(
                            isComposing = false,
                            progress = 100,
                            progressMessage = "完成！",
                            successPath = result
                        )
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Live 图已保存到相册", Toast.LENGTH_LONG).show()
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isComposing = false,
                            error = "合成失败，请重试"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isComposing = false,
                        error = "合成失败: ${e.message}"
                    )
                }
            }
        }
    }

    private suspend fun composeLivePhoto(): String? {
        val state = _uiState.value
        val videoUri = state.videoUri ?: return null
        val coverUri = state.coverUri

        val tmpDir = File(context.cacheDir, "live_photo_compose_${System.currentTimeMillis()}")
        tmpDir.mkdirs()

        try {
            updateProgress(5, "复制视频文件...")

            // 复制视频到临时文件
            val videoFile = File(tmpDir, "input_video.mp4")
            copyUriToFile(videoUri, videoFile)
            if (!videoFile.exists() || videoFile.length() == 0L) {
                throw Exception("视频文件复制失败")
            }

            updateProgress(30, "复制封面图片...")

            // 复制封面到临时文件（如果有）
            val coverFile = if (coverUri != null) {
                val file = File(tmpDir, "input_cover.jpg")
                copyUriToFile(coverUri, file)
                if (file.exists() && file.length() > 0) file else null
            } else null

            updateProgress(40, "提取视频帧作为封面...")

            // 如果没有封面，从视频提取一帧
            val actualCover = coverFile ?: run {
                val extracted = File(tmpDir, "extracted_cover.jpg")
                val success = com.xianyu.vediodownload.util.FFmpegUtils.extractFrame(
                    context = context,
                    inputPath = videoFile.absolutePath,
                    outputPath = extracted.absolutePath,
                    timestamp = 1.0,
                    quality = 2
                )
                if (success && extracted.exists()) extracted else null
            }

            updateProgress(50, "开始合成 Live 图...")

            // 合成 Live Photo
            val outputFileName = "LivePhoto_${System.currentTimeMillis()}"
            val savedPath = LivePhotoMaker.createMotionPhoto(
                context = context,
                videoPath = videoFile.absolutePath,
                coverPath = actualCover?.absolutePath,
                outputFileName = outputFileName,
                quality = state.quality.preset,
                presentationTimestampUs = 1_000_000,
                resize = state.resize,
                onProgress = { percent ->
                    mainHandler.post {
                        val adjustedProgress = 50 + (percent * 50 / 100)
                        _uiState.update {
                            it.copy(
                                progress = adjustedProgress,
                                progressMessage = when {
                                    percent < 25 -> "编码视频中..."
                                    percent < 50 -> "注入元数据..."
                                    percent < 75 -> "拼接文件中..."
                                    else -> "保存到相册..."
                                }
                            )
                        }
                    }
                }
            )

            return savedPath
        } finally {
            // 清理临时文件
            tmpDir.deleteRecursively()
        }
    }

    private fun checkVideoCodec(videoUri: Uri): String? {
        return try {
            val extractor = MediaExtractor()
            context.contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: extractor.setDataSource(context, videoUri, null)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    extractor.release()
                    if (!mime.contains("avc", ignoreCase = true) && !mime.contains("h264", ignoreCase = true)) {
                        return "该视频无法合成Live图，因为视频非H.264编码！"
                    }
                    return null
                }
            }
            extractor.release()
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun updateProgress(progress: Int, message: String) {
        mainHandler.post {
            _uiState.update {
                it.copy(progress = progress, progressMessage = message)
            }
        }
    }

    private fun copyUriToFile(uri: Uri, destFile: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
