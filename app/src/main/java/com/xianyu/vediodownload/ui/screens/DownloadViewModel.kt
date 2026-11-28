package com.xianyu.vediodownload.ui.screens

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xianyu.vediodownload.douyin.AwemeProfile
import com.xianyu.vediodownload.douyin.DouyinDownloader
import com.xianyu.vediodownload.douyin.MediaType
import com.xianyu.vediodownload.douyin.exception.AwemeDetailFetchException
import com.xianyu.vediodownload.douyin.exception.DouyinDownloaderException
import com.xianyu.vediodownload.douyin.exception.FileDownloadException
import com.xianyu.vediodownload.douyin.exception.WorkListFetchException
import com.xianyu.vediodownload.kuaishou.KuaishouDownloader
import com.xianyu.vediodownload.model.Platform
import com.xianyu.vediodownload.util.CookieManager
import com.xianyu.vediodownload.util.DownloaderBridge
import com.xianyu.vediodownload.util.FFmpegUtils
import com.xianyu.vediodownload.util.ForegroundDownloadNotifier
import com.xianyu.vediodownload.util.LivePhotoMaker
import com.xianyu.vediodownload.util.PlatformModeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DownloadViewModel::class.java)) {
            return DownloadViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

data class MediaPreview(
    val coverUrl: String = "",
    val authorName: String = "",
    val authorAvatarUrl: String = "",   // 作者头像 URL
    val description: String = "",
    val imageCount: Int = 0,
    val videoUrl: String = "",
    val imageUrls: String = "",   // 图片URL列表，用 ||| 分隔
    val liveVideoUrls: String = "",  // Live图视频URL列表，用 ||| 分隔
    val liveCoverUrls: String = "",   // Live图封面URL列表，用 ||| 分隔
    val qualityLabel: String = "",    // 清晰度标注（"720p · 30fps"），快手作品如实展示
    val musicLabel: String = ""       // 背景音乐标注（"歌名 · 歌手"），无则空字符串
)

/** 音频来源：曲库背景音乐 / 纯原声 / 原声与背景音乐的混合音轨。 */
enum class AudioSource { BACKGROUND_MUSIC, PURE_ORIGINAL, MIXED_ORIGINAL }

/** 弹窗中的一个音频选项。 */
data class AudioOption(
    val source: AudioSource,
    val title: String,
    val description: String,
    val enabled: Boolean,
)

/** 「下载音频」弹窗状态：按作品实际情况动态生成选项。 */
data class AudioDialogState(
    val musicLabel: String = "",
    val options: List<AudioOption> = emptyList(),
)

data class BatchSession(
    val total: Int = 0,
    val current: Int = 0,
    val percent: Int = 0,
    val dialogDismissed: Boolean = false,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val active: Boolean = false,
)

enum class ComposePhase { DOWNLOADING, COMPOSING, DONE }

data class LiveComposeSession(
    val total: Int = 0,
    val current: Int = 0,
    val percent: Int = 0,
    val dialogDismissed: Boolean = false,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val phase: ComposePhase = ComposePhase.DOWNLOADING,
    val active: Boolean = false,
)

/**
 * 单对 cover + video 的下载结果（Live 图合成流水线的中间产物）。
 * 任一为 null 表示下载失败；合成阶段任一缺失即跳过整对。
 */
data class LiveComposePair(
    val coverUri: Uri? = null,
    val videoUri: Uri? = null,
)

data class DownloadUiState(
    val url: String = "",
    val isLoading: Boolean = false,
    val loadingMessage: String = "正在解析...",
    val error: String? = null,
    /** 解析失败时的诊断文本，供用户一键复制反馈（不含 Cookie / 账号信息）。 */
    val diagnostics: String? = null,
    val preview: MediaPreview? = null,
    val isVideo: Boolean = false,
    val isImage: Boolean = false,
    val isLivePhoto: Boolean = false,
    // 选择状态
    val isSelectionMode: Boolean = false,
    val selectedImageIndices: Set<Int> = emptySet(),
    val selectedLiveIndices: Set<Int> = emptySet(),  // 同时用于静态封面和动态短片
    // 批量下载会话（null 表示当前无下载任务）
    val batch: BatchSession? = null,
    // Live 图批量合成会话（null 表示当前无合成任务）
    val compose: LiveComposeSession? = null,
    // 「下载音频」的来源选择弹窗（null 表示不显示）
    val audioDialog: AudioDialogState? = null,
)

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    private val cookieManager by lazy { CookieManager(application) }
    private val downloaderBridge by lazy { DownloaderBridge(application) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // 最近一次通知刷新时间，用于节流（每个文件 progress 更新非常频繁，避免每帧都发通知）。
    private var lastNotifyAt = 0L

    private var cachedProfile: AwemeProfile? = null

    // ========== Live 图批量合成流水线（私有状态） ==========
    // 用于在 BatchSession 下载阶段捕获每对 cover / video 的 URI，
    // 待下载完成后进入 LiveComposeSession 合成阶段。
    private val pendingComposePairs = mutableListOf<LiveComposePair>()
    // 当下载批次属于"下载全部 Live 图"流程时置 true，使 finalizeBatch 不弹"下载完成"
    // Toast / 通知，而是直接把控制权交给 startComposePhase()。
    private var suppressBatchFinalization = false

    fun updateUrl(url: String) {
        _uiState.update { it.copy(url = url, error = null) }
    }

    /**
     * 切换解析模式时重置页面：清空输入框、预览与选择状态。
     *
     * 必须连输入框一起清空——否则抖音链接会残留在快手模式里，
     * 用户点解析会得到「模式与链接不匹配」的提示，体验割裂。
     *
     * 注意：不动 [DownloadUiState.batch] / [DownloadUiState.compose]，
     * 保证切换模式不会打断正在后台进行的下载或合成任务。
     */
    fun resetForPlatformSwitch() {
        cachedProfile = null
        _uiState.update {
            it.copy(
                url = "",
                preview = null,
                error = null,
                diagnostics = null,
                isLoading = false,
                loadingMessage = "正在解析...",
                isVideo = false,
                isImage = false,
                isLivePhoto = false,
                isSelectionMode = false,
                selectedImageIndices = emptySet(),
                selectedLiveIndices = emptySet(),
                audioDialog = null,
            )
        }
    }

    fun parse() {
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(error = "请输入分享链接") }
            return
        }

        val mode = PlatformModeManager(getApplication()).getMode()
        val kuaishouMode = mode == Platform.KUAISHOU
        val detectedLinks = runCatching { DouyinDownloader.describeLinks(url) }
            .getOrDefault(emptyList())

        if (kuaishouMode) {
            val hasKuaishouLink = detectedLinks.any { KuaishouDownloader.isTrustedShareUrl(it) } ||
                KuaishouDownloader.containsKuaishouLink(url)
            if (!hasKuaishouLink) {
                val message = if (detectedLinks.any { DouyinDownloader.isTrustedShareUrl(it) }) {
                    "当前为快手模式，你粘贴的是抖音链接，请到设置页切换为抖音模式"
                } else {
                    "没有识别到快手链接，请复制完整的分享文案（含作品链接）后重试"
                }
                failParse(message, url, mode, null)
                return
            }
        } else {
            val hasDouyinLink = detectedLinks.any { DouyinDownloader.isTrustedShareUrl(it) } ||
                looksLikeDouyinDomainOnly(url)
            if (!hasDouyinLink) {
                // 反向的跨平台提示：抖音模式下粘贴了快手链接
                val message = if (KuaishouDownloader.containsKuaishouLink(url)) {
                    "当前为抖音模式，你粘贴的是快手链接，请到设置页切换为快手模式"
                } else {
                    "没有识别到抖音链接，请复制完整的分享文案（含 v.douyin.com 链接）后重试"
                }
                failParse(message, url, mode, null)
                return
            }
            if (!cookieManager.isSet()) {
                _uiState.update {
                    it.copy(isLoading = false, error = "请先在设置页设置抖音 Cookie", diagnostics = null)
                }
                return
            }
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                loadingMessage = if (kuaishouMode) "正在解析快手作品..." else "正在解析...",
                error = null,
                diagnostics = null,
                preview = null,
            )
        }

        viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    if (kuaishouMode) {
                        // 快手当前解析路径不需要登录态，Cookie 仅作风控兜底预留
                        KuaishouDownloader().collectWorkInfo(url)
                    } else {
                        DouyinDownloader(cookieManager.getCookie()).collectWorkInfo(url)
                    }
                }

                cachedProfile = profile
                val mediaType = profile.mediaType()
                val hasLive = profile.imageMediaTypes().any { it == MediaType.VIDEO }

                // 分离图片和视频URL
                val imageUrls = mutableListOf<String>()
                val videoUrls = mutableListOf<String>()

                profile.downloadUrls().forEachIndexed { index, itemUrl ->
                    if (index < profile.imageMediaTypes().size) {
                        val type = profile.imageMediaTypes()[index]
                        if (type == MediaType.VIDEO) {
                            videoUrls.add(itemUrl)
                        } else {
                            imageUrls.add(itemUrl)
                        }
                    } else {
                        imageUrls.add(itemUrl)
                    }
                }

                // Live图预览用第一个视频URL
                val liveVideoUrl = if (videoUrls.isNotEmpty()) videoUrls[0] else ""

                val preview = MediaPreview(
                    coverUrl = profile.thumbnailUrl(),
                    authorName = profile.authorNickname(),
                    authorAvatarUrl = profile.authorAvatarUrl(),
                    description = profile.desc(),
                    imageCount = imageUrls.size + videoUrls.size,
                    videoUrl = if (mediaType == MediaType.VIDEO) profile.downloadUrls()[0] else "",
                    imageUrls = imageUrls.joinToString("|||"),
                    liveVideoUrls = videoUrls.joinToString("|||"),
                    liveCoverUrls = imageUrls.joinToString("|||"),  // Live图的封面就是静态图片
                    qualityLabel = profile.qualityLabel(),
                    musicLabel = buildMusicLabel(profile)
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        preview = preview,
                        isVideo = mediaType == MediaType.VIDEO,
                        isImage = mediaType == MediaType.IMAGE && !hasLive,
                        isLivePhoto = mediaType == MediaType.IMAGE && hasLive
                    )
                }
            } catch (e: AwemeDetailFetchException) {
                failParse(resolveErrorMessage(e, forWorkList = false), url, mode, e)
            } catch (e: WorkListFetchException) {
                failParse(resolveErrorMessage(e, forWorkList = true), url, mode, e)
            } catch (e: FileDownloadException) {
                failParse("作品数据缺失，请换个链接重试", url, mode, e)
            } catch (e: DouyinDownloaderException) {
                // 快手解析器复用同一套异常体系（含 FailReason），因此统一按 reason 映射文案
                failParse(resolveErrorMessage(e, forWorkList = false), url, mode, e)
            } catch (e: IllegalArgumentException) {
                failParse(
                    if (kuaishouMode) {
                        "链接无法识别，请复制完整的快手分享文案后重试"
                    } else {
                        "链接无法识别，请复制完整的抖音分享文案后重试"
                    },
                    url,
                    mode,
                    e
                )
            } catch (e: Exception) {
                failParse("解析失败，请检查网络后重试", url, mode, e)
            }
        }
    }

    /** 统一的失败出口：写错误文案，同时生成可一键复制的诊断文本。 */
    private fun failParse(message: String, rawText: String, mode: Platform, cause: Throwable?) {
        _uiState.update {
            it.copy(
                isLoading = false,
                error = message,
                diagnostics = buildDiagnostics(rawText, mode, cause),
            )
        }
    }

    /**
     * 生成诊断文本。
     *
     * <p>用户不会抓 Logcat，这段文本是他唯一能低成本提供的现场信息：
     * 版本号排除老包、模式排除跨平台误报、原文与识别到的链接能看出字符是否被改形。
     * 不含 Cookie、不含账号信息。
     */
    private fun buildDiagnostics(rawText: String, mode: Platform, cause: Throwable?): String {
        val app = getApplication<Application>()
        val pkg = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0)
        }.getOrNull()
        val versionName = pkg?.versionName ?: "?"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            (pkg?.versionCode ?: 0).toLong()
        }
        val links = runCatching { DouyinDownloader.describeLinks(rawText) }
            .getOrDefault(emptyList())
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("【抖净诊断】")
            appendLine("版本: $versionName ($versionCode)")
            appendLine("模式: ${if (mode == Platform.KUAISHOU) "快手" else "抖音"}")
            appendLine("时间: $time")
            appendLine("原文: " + rawText.take(200).replace("\n", " ⏎ "))
            appendLine("识别到的链接: " + if (links.isEmpty()) "无" else links.joinToString(" , "))
            if (cause != null) {
                appendLine("异常: ${cause.javaClass.simpleName}: ${cause.message}")
            }
        }
    }

    /** 只判断域名、不要求带 scheme：兼容用户手输 "v.douyin.com/xxx" 的情况。 */
    private fun looksLikeDouyinDomainOnly(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("douyin.com") || lower.contains("iesdouyin.com")
    }

    fun downloadVideo() {
        val profile = cachedProfile ?: return
        startBatch(total = 1) {
            downloadOne(profile, 0)
        }
    }

    // ========== 下载音频 ==========

    /**
     * 弹出音频来源选择弹窗。
     *
     * 选项按作品实际情况动态生成，而不是固定的「背景音乐 / 原声」两条：
     *  - 曲库音乐作品：背景音乐（纯 BGM）+ 原声 + 背景音乐（混合音轨）
     *  - 原声作品（未加 BGM）：只有「原声（纯原声）」一个实质选项
     *  - 图文作品：最多只有背景音乐
     *  - Live 作品：只有背景音乐，原声项置灰
     */
    fun requestAudioDownload() {
        val profile = cachedProfile ?: return
        val live = isLiveWork(profile)
        val videoUrl = firstVideoUrl(profile)
        val hasVideo = videoUrl.isNotBlank()
        // 使用原声的作品，musicUrl 指向的是原声录音本身，不是曲库 BGM
        val pureOriginal = profile.musicIsOriginal()
        val hasPureBgm = profile.musicUrl().isNotBlank() && !pureOriginal

        val options = mutableListOf<AudioOption>()
        if (hasPureBgm) {
            options += AudioOption(
                source = AudioSource.BACKGROUND_MUSIC,
                title = "背景音乐",
                description = "曲库里的那首歌，纯 BGM（不含人声）",
                enabled = true,
            )
        }
        if (hasVideo && pureOriginal) {
            options += AudioOption(
                source = AudioSource.PURE_ORIGINAL,
                title = "原声（纯原声）",
                description = "博主未添加背景音乐，这条音轨就是现场原声",
                enabled = true,
            )
        }
        if (hasVideo && !pureOriginal) {
            options += AudioOption(
                source = AudioSource.MIXED_ORIGINAL,
                title = "原声 + 背景音乐",
                description = if (live) {
                    "Live 图不支持下载原声，请选择背景音乐"
                } else {
                    "视频音轨：现场原声与背景音乐已混合，抖音未提供可分离的纯原声"
                },
                enabled = !live,
            )
        }
        if (!hasVideo) {
            options += AudioOption(
                source = AudioSource.MIXED_ORIGINAL,
                title = "原声",
                description = "该作品没有视频音轨（纯图片作品）",
                enabled = false,
            )
        }

        if (options.none { it.enabled }) {
            _uiState.update {
                it.copy(
                    error = if (live) {
                        "该作品没有背景音乐，Live 图不支持下载原声"
                    } else {
                        "该作品没有可下载的音频（无背景音乐，也没有音轨）"
                    }
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                audioDialog = AudioDialogState(
                    musicLabel = buildMusicLabel(profile),
                    options = options,
                )
            )
        }
    }

    fun dismissAudioDialog() {
        _uiState.update { it.copy(audioDialog = null) }
    }

    /**
     * 下载音频并转成 mp3。
     *
     * 两条来源最终都走同一条管线：下载源文件到 cache → ffmpeg 转 mp3 → 写入 Music/。
     * 统一转码是为了让产物格式、命名、失败语义保持一致。
     */
    fun downloadAudio(source: AudioSource) {
        val profile = cachedProfile ?: return
        val sourceUrl = when (source) {
            AudioSource.BACKGROUND_MUSIC -> profile.musicUrl()
            AudioSource.PURE_ORIGINAL, AudioSource.MIXED_ORIGINAL ->
                if (isLiveWork(profile)) "" else firstVideoUrl(profile)
        }
        _uiState.update { it.copy(audioDialog = null) }
        if (sourceUrl.isBlank()) {
            _uiState.update { it.copy(error = "该来源的音频不存在，请选择另一项") }
            return
        }

        startBatch(total = 1) {
            val success = runAudioPipeline(profile, sourceUrl, source)
            mainHandler.post {
                if (success) recordSuccess() else recordFailure()
                advanceFile()
                notifyProgressThrottled(force = true)
                finalizeBatchIfFinished()
            }
        }
    }

    private suspend fun runAudioPipeline(
        profile: AwemeProfile,
        sourceUrl: String,
        source: AudioSource,
    ): Boolean = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val tempDir = File(app.cacheDir, "audio_tmp").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        // 源文件扩展名影响 ffmpeg 探测，直链按 m4a、原声按 mp4 给
        val srcFile = File(
            tempDir,
            if (source == AudioSource.BACKGROUND_MUSIC) "src_$stamp.m4a" else "src_$stamp.mp4"
        )
        try {
            downloaderBridge.downloadUrlToFile(
                profile, sourceUrl, srcFile, cookieManager.getCookie()
            ) { percent, _, _ ->
                // 下载阶段占 0–85%，把剩余进度留给转码，避免进度条到 100% 后长时间停住
                mainHandler.post {
                    val batch = _uiState.value.batch ?: return@post
                    _uiState.update {
                        it.copy(batch = batch.copy(percent = (percent * 0.85f).toInt()))
                    }
                    notifyProgressThrottled()
                }
            }
            if (!srcFile.exists() || srcFile.length() == 0L) {
                Log.w("DownloadViewModel", "audio source download produced empty file")
                return@withContext false
            }
            mainHandler.post {
                val batch = _uiState.value.batch ?: return@post
                _uiState.update { it.copy(batch = batch.copy(percent = 90)) }
                notifyProgressThrottled(force = true)
            }
            // 不依赖 libmp3lame：源是 mp3 就复制成 mp3，是 AAC 就复制成 m4a，均不重新编码
            val exported = FFmpegUtils.exportAudio(
                srcFile.absolutePath, tempDir.absolutePath, "out_$stamp"
            )
            if (exported == null) {
                Log.w("DownloadViewModel", "ffmpeg audio export failed: $source")
                return@withContext false
            }
            try {
                val displayName = buildAudioFileName(profile, source) + "." + exported.extension
                downloaderBridge.saveAudioToMediaStore(exported, displayName)
            } finally {
                //noinspection ResultOfMethodCallIgnored
                exported.delete()
            }
            true
        } catch (e: Exception) {
            Log.w("DownloadViewModel", "audio download failed", e)
            false
        } finally {
            //noinspection ResultOfMethodCallIgnored
            srcFile.delete()
        }
    }

    /** Live 作品 = 图文结构 + 含动态短片。这类作品不允许下载原声。 */
    private fun isLiveWork(profile: AwemeProfile): Boolean =
        profile.mediaType() == MediaType.IMAGE &&
            profile.imageMediaTypes().contains(MediaType.VIDEO)

    /** 原声来源：纯视频取首个直链；图文 / Live 取第一个动态短片；纯图片返回空串。 */
    private fun firstVideoUrl(profile: AwemeProfile): String {
        val types = profile.imageMediaTypes()
        if (types.isEmpty()) {
            return profile.downloadUrls().firstOrNull().orEmpty()
        }
        val index = types.indexOfFirst { it == MediaType.VIDEO }
        return if (index >= 0) profile.downloadUrls().getOrNull(index).orEmpty() else ""
    }

    private fun buildAudioFileName(profile: AwemeProfile, source: AudioSource): String {
        val author = profile.authorNickname().trim()
        val body = profile.desc().trim().ifEmpty {
            (if (profile.platform() == Platform.KUAISHOU) "kuaishou_" else "douyin_") + profile.awemeId()
        }
        val suffix = when (source) {
            AudioSource.BACKGROUND_MUSIC -> "_背景音乐"
            AudioSource.PURE_ORIGINAL -> "_原声"
            AudioSource.MIXED_ORIGINAL -> "_原声+背景音乐"
        }
        val raw = (if (author.isEmpty()) "" else author + "_") + body + suffix
        return sanitizeAudioName(raw).take(80)
    }

    private fun sanitizeAudioName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim()
        return cleaned.ifEmpty { "audio" }
    }

    /** 卡片上展示的音乐标注：歌名 · 歌手（相同则只显示一次）。 */
    private fun buildMusicLabel(profile: AwemeProfile): String {
        val title = profile.musicTitle()
        val author = profile.musicAuthor()
        return when {
            title.isNotEmpty() && author.isNotEmpty() && author != title -> "$title · $author"
            title.isNotEmpty() -> title
            else -> author
        }
    }

    fun downloadAllImages() {
        val profile = cachedProfile ?: return
        val urls = profile.downloadUrls()
        if (urls.isEmpty()) return
        startBatch(total = urls.size) {
            urls.indices.forEach { index ->
                downloadOne(profile, index)
                if (index < urls.lastIndex) {
                    delay(800) // 间隔 800ms，降低风控触发
                }
            }
        }
    }

    fun downloadLivePhoto() {
        val profile = cachedProfile ?: return
        val indices = profile.imageMediaTypes().indices
            .filter { it % 2 == 0 && profile.imageMediaTypes()[it] == MediaType.IMAGE }
        if (indices.isEmpty()) return
        startBatch(total = indices.size) {
            indices.forEachIndexed { idx, index ->
                downloadOne(profile, index)
                if (idx < indices.lastIndex) {
                    delay(800)
                }
            }
        }
    }

    fun downloadLiveVideo() {
        val profile = cachedProfile ?: return
        val indices = profile.imageMediaTypes().indices
            .filter { it % 2 == 1 && profile.imageMediaTypes()[it] == MediaType.VIDEO }
        if (indices.isEmpty()) return
        startBatch(total = indices.size) {
            indices.forEachIndexed { idx, index ->
                downloadOne(profile, index, overrideType = MediaType.LIVE_VIDEO)
                if (idx < indices.lastIndex) {
                    delay(800)
                }
            }
        }
    }

    // ========== 选择下载相关 ==========

    fun toggleSelectionMode() {
        _uiState.update {
            it.copy(
                isSelectionMode = !it.isSelectionMode,
                selectedImageIndices = if (it.isSelectionMode) emptySet() else it.selectedImageIndices,
                selectedLiveIndices = if (it.isSelectionMode) emptySet() else it.selectedLiveIndices
            )
        }
    }

    fun exitSelectionMode() {
        _uiState.update {
            it.copy(
                isSelectionMode = false,
                selectedImageIndices = emptySet(),
                selectedLiveIndices = emptySet()
            )
        }
    }

    fun toggleImageSelection(index: Int) {
        _uiState.update {
            val current = it.selectedImageIndices
            it.copy(selectedImageIndices = if (index in current) current - index else current + index)
        }
    }

    fun selectAllImages(totalCount: Int) {
        _uiState.update { it.copy(selectedImageIndices = (0 until totalCount).toSet()) }
    }

    fun deselectAllImages() {
        _uiState.update { it.copy(selectedImageIndices = emptySet()) }
    }

    fun downloadSelectedImages() {
        val profile = cachedProfile ?: return
        val indices = _uiState.value.selectedImageIndices.toList().sorted()
        if (indices.isEmpty()) return
        // 不主动退出选择模式，也不清空已选索引：保留勾选状态让用户能继续选 / 看进度
        startBatch(total = indices.size) {
            indices.forEachIndexed { idx, index ->
                downloadOne(profile, index)
                if (idx < indices.lastIndex) {
                    delay(800)
                }
            }
        }
    }

    fun toggleLiveSelection(index: Int) {
        _uiState.update {
            val current = it.selectedLiveIndices
            it.copy(selectedLiveIndices = if (index in current) current - index else current + index)
        }
    }

    fun selectAllLive(totalCount: Int) {
        _uiState.update { it.copy(selectedLiveIndices = (0 until totalCount).toSet()) }
    }

    fun deselectAllLive() {
        _uiState.update { it.copy(selectedLiveIndices = emptySet()) }
    }

    fun downloadSelectedLivePhotos() {
        val profile = cachedProfile ?: return
        val indices = _uiState.value.selectedLiveIndices.toList().sorted()
        if (indices.isEmpty()) return
        // 不主动退出选择模式，也不清空已选索引：保留勾选状态让用户能继续选 / 看进度
        startBatch(total = indices.size) {
            // UI 索引 i 对应原始索引 i * 2（Live 图的封面是偶数索引）
            val originalIndices = indices.map { it * 2 }
            originalIndices.forEachIndexed { idx, originalIndex ->
                if (originalIndex < profile.imageMediaTypes().size) {
                    downloadOne(profile, originalIndex)
                    if (idx < originalIndices.lastIndex) {
                        delay(800)
                    }
                }
            }
        }
    }

    fun downloadSelectedLiveVideos() {
        val profile = cachedProfile ?: return
        val indices = _uiState.value.selectedLiveIndices.toList().sorted()
        if (indices.isEmpty()) return
        // 不主动退出选择模式，也不清空已选索引：保留勾选状态让用户能继续选 / 看进度
        startBatch(total = indices.size) {
            // UI 索引 i 对应原始索引 i * 2 + 1（Live 图的视频是奇数索引）
            val originalIndices = indices.map { it * 2 + 1 }
            originalIndices.forEachIndexed { idx, originalIndex ->
                if (originalIndex < profile.imageMediaTypes().size) {
                    downloadOne(profile, originalIndex, overrideType = MediaType.LIVE_VIDEO)
                    if (idx < originalIndices.lastIndex) {
                        delay(800)
                    }
                }
            }
        }
    }

    /**
     * 全部 Live 图下载 + 合成：先顺序下载所有对（cover → video → cover → video → ...），
     * 全部完成后自动进入合成阶段。整对 cover / video 任一缺失则跳过合成，但保留源文件。
     */
    fun downloadAllLivePhotos() {
        val profile = cachedProfile ?: return
        val pairs = computeAllLivePairs(profile)
        if (pairs.isEmpty()) return
        startLivePhotoFlow(profile, pairs)
    }

    /** 选择模式下：只把已选中的若干对 cover + video 拉下来再合成。 */
    fun downloadSelectedLivePhotosCompose() {
        val profile = cachedProfile ?: return
        val selectedUi = _uiState.value.selectedLiveIndices.toList().sorted()
        if (selectedUi.isEmpty()) return
        val pairs = selectedUi.mapNotNull { uiIdx ->
            val coverIdx = uiIdx * 2
            val videoIdx = uiIdx * 2 + 1
            val types = profile.imageMediaTypes()
            if (videoIdx < types.size) coverIdx to videoIdx else null
        }
        if (pairs.isEmpty()) return
        startLivePhotoFlow(profile, pairs)
    }

    /**
     * 筛选 profile 中所有"cover(IMAGE) + video(LIVE_VIDEO)"对，按出现顺序返回原始索引对。
     * 与 downloadLivePhoto / downloadLiveVideo 的筛选规则保持一致。
     */
    private fun computeAllLivePairs(profile: AwemeProfile): List<Pair<Int, Int>> {
        val types = profile.imageMediaTypes()
        return types.indices
            .filter { it % 2 == 0 && it + 1 < types.size
                    && types[it] == MediaType.IMAGE && types[it + 1] == MediaType.VIDEO }
            .map { it to (it + 1) }
    }

    /**
     * 启动"下载全部 Live 图"流水线：
     *   Phase 1 (DOWNLOADING)：按对顺序下载 cover / video，共 N*2 个文件，复用 BatchSession 进度。
     *   Phase 2 (COMPOSING)：逐对用 LivePhotoMaker.createMotionPhoto 合成，独立的 LiveComposeSession。
     *
     * 通过 suppressBatchFinalization 拦截 finalizeBatch，使下载完成时不弹"下载完成"Toast / 通知，
     * 紧接着切到合成阶段。
     */
    private fun startLivePhotoFlow(profile: AwemeProfile, pairs: List<Pair<Int, Int>>) {
        pendingComposePairs.clear()
        repeat(pairs.size) { pendingComposePairs.add(LiveComposePair()) }
        suppressBatchFinalization = true

        val totalFiles = pairs.size * 2
        startBatch(total = totalFiles) {
            // 按"对"顺序提交：pair[0].cover → pair[0].video → pair[1].cover → pair[1].video → ...
            pairs.forEachIndexed { pairIdx, (coverIdx, videoIdx) ->
                downloadOneCaptured(profile, coverIdx, MediaType.IMAGE) { uri ->
                    if (pairIdx < pendingComposePairs.size) {
                        pendingComposePairs[pairIdx] = pendingComposePairs[pairIdx].copy(coverUri = uri)
                    }
                }
                if (pairIdx < pairs.lastIndex || videoIdx != coverIdx + 1) {
                    delay(800)
                }
                downloadOneCaptured(profile, videoIdx, MediaType.LIVE_VIDEO) { uri ->
                    if (pairIdx < pendingComposePairs.size) {
                        pendingComposePairs[pairIdx] = pendingComposePairs[pairIdx].copy(videoUri = uri)
                    }
                }
                if (pairIdx < pairs.lastIndex) {
                    delay(800)
                }
            }
        }
    }

    /**
     * 与 [downloadOne] 行为相同，但额外提供一个 onComplete 回调，把成功时的 MediaStore URI 抛回来，
     * 供 Live Photo 流水线记入 pendingComposePairs。失败时回调 null。
     */
    private fun downloadOneCaptured(
        profile: AwemeProfile,
        index: Int,
        overrideType: MediaType,
        onComplete: (Uri?) -> Unit,
    ) {
        val cookie = cookieManager.getCookie()
        downloaderBridge.download(profile, index, cookie, overrideType,
            object : DownloaderBridge.ProgressListener {
                override fun onProgress(percent: Int, downloaded: Long, total: Long) {
                    mainHandler.post {
                        val current = _uiState.value.batch ?: return@post
                        _uiState.update {
                            it.copy(batch = current.copy(percent = percent))
                        }
                        notifyProgressThrottled()
                    }
                }
            },
            object : DownloaderBridge.CompleteListener {
                override fun onSuccess(savedPath: String) {
                    val parsed = runCatching { Uri.parse(savedPath) }.getOrNull()
                    onComplete(parsed)
                    mainHandler.post {
                        recordSuccess()
                        advanceFile()
                        notifyProgressThrottled(force = true)
                        finalizeBatchIfFinished()
                    }
                }

                override fun onError(error: Throwable) {
                    onComplete(null)
                    mainHandler.post {
                        recordFailure()
                        advanceFile()
                        notifyProgressThrottled(force = true)
                        finalizeBatchIfFinished()
                    }
                }
            })
    }

    /**
     * 用户点击"后台下载"：把 dialogDismissed 置 true，弹窗即在 UI 层不再展示，但下载任务
     * 仍在 viewModelScope 中跑。后续进度通过 ForegroundDownloadNotifier 推送通知。
     */
    fun dismissBatchDialog() {
        val current = _uiState.value.batch ?: return
        _uiState.update {
            it.copy(batch = current.copy(dialogDismissed = true))
        }
        // 清掉可能残留的旧"下载完成"通知（如果上一批刚完用户又开了一批），
        // 紧接着立刻发一条本次批次的进度通知，避免状态栏出现无通知的空白瞬间。
        ForegroundDownloadNotifier.cancel(getApplication())
        notifyProgressThrottled(force = true)
    }

    /**
     * UI 返回前台时调用：
     * 1) 清掉通知栏残留的"下载完成 / 合成完成"等持续通知；
     * 2) 如果当前有活跃的下载 / 合成批次，把 dialogDismissed 重置为 false，把弹窗交还给用户。
     */
    fun onForegroundReturned() {
        ForegroundDownloadNotifier.cancel(getApplication())
        val batch = _uiState.value.batch
        if (batch != null && batch.active) {
            _uiState.update {
                it.copy(batch = batch.copy(dialogDismissed = false))
            }
        }
        val compose = _uiState.value.compose
        if (compose != null && compose.active) {
            _uiState.update {
                it.copy(compose = compose.copy(dialogDismissed = false))
            }
        }
    }

    // ========== 内部：批次元数据管理 ==========

    private fun startBatch(total: Int, work: suspend () -> Unit) {
        if (total <= 0) return
        _uiState.update {
            it.copy(
                batch = BatchSession(
                    total = total,
                    current = 1,
                    percent = 0,
                    dialogDismissed = false,
                    successCount = 0,
                    failureCount = 0,
                    active = true,
                )
            )
        }
        ForegroundDownloadNotifier.cancel(getApplication())
        ForegroundDownloadNotifier.start(getApplication(), total)

        // 用外部作用域跑"提交任务"循环；批次实际结束由 CompleteListener 统计
        // 到 successCount + failureCount == total 后回调 finalizeBatch，不再依赖 work() 返回时机。
        viewModelScope.launch {
            try {
                work()
            } catch (_: Throwable) {
                // 即使提交阶段异常，也不让程序直接吞掉统计
                finalizeBatchIfFinished()
            }
        }
    }

    /**
     * 真正的批次收尾：当累计成功+失败达到 total 才推"下载完成"通知和一次性 Toast。
     * 这样无论"提交循环"何时返回，通知 / Toast 都精确反映"最后一个文件已完成"的时刻。
     *
     * 例外：当 [suppressBatchFinalization] 为 true（"下载全部 Live 图"流程），跳过标准的
     * 下载完成 Toast / 通知，直接把控制权交给 [startComposePhase] 进入合成阶段。
     */
    private fun finalizeBatchIfFinished() {
        val batch = _uiState.value.batch ?: return
        if (!batch.active) return
        val completed = batch.successCount + batch.failureCount
        if (completed < batch.total) return  // 还有文件在跑，等下一次回调
        if (suppressBatchFinalization) {
            suppressBatchFinalization = false
            _uiState.update { it.copy(batch = null) }
            startComposePhase()
            return
        }
        finalizeBatch()
    }

    // ========== 内部：Live 图合成阶段（独立于下载会话） ==========

    /**
     * 把已下载的所有 cover / video 对依次合成。任一对缺一个就跳过（保留源文件给用户手动重试）。
     * 合成成功后删除源 cover + video（避免相册堆积）。
     */
    private fun startComposePhase() {
        val pairs = pendingComposePairs.toList()
        if (pairs.isEmpty()) {
            // 没有任何对，直接收尾
            finalizeCompose(0, 0)
            return
        }
        _uiState.update {
            it.copy(
                compose = LiveComposeSession(
                    total = pairs.size,
                    current = 1,
                    percent = 0,
                    dialogDismissed = false,
                    successCount = 0,
                    failureCount = 0,
                    phase = ComposePhase.COMPOSING,
                    active = true,
                )
            )
        }
        // 关掉下载阶段的进度通知，发"正在合成"通知
        ForegroundDownloadNotifier.cancel(getApplication())
        ForegroundDownloadNotifier.startCompose(getApplication(), pairs.size)

        viewModelScope.launch {
            pairs.forEachIndexed { idx, pair ->
                val success = composeOneLivePhoto(idx, pair)
                mainHandler.post {
                    val current = _uiState.value.compose ?: return@post
                    val nextCurrent = (current.current + 1).coerceAtMost(current.total + 1)
                    val nextSuccess = current.successCount + (if (success) 1 else 0)
                    val nextFailure = current.failureCount + (if (success) 0 else 1)
                    _uiState.update {
                        it.copy(
                            compose = current.copy(
                                current = nextCurrent,
                                percent = 0,
                                successCount = nextSuccess,
                                failureCount = nextFailure,
                            )
                        )
                    }
                    ForegroundDownloadNotifier.updateCompose(
                        getApplication(),
                        nextCurrent.coerceAtMost(current.total),
                        current.total,
                    )
                    if (nextSuccess + nextFailure >= current.total) {
                        finalizeCompose(nextSuccess, nextFailure)
                    }
                }
            }
        }
    }

    /**
     * 合成单张 Live 图。返回 true 表示合成成功，false 表示跳 / 失败。
     * - 任一 URI 为空 → 跳过（保留源文件）
     * - 合成成功 → 删除源 cover + video
     * - 合成失败 → 保留源文件
     */
    private suspend fun composeOneLivePhoto(@Suppress("UNUSED_PARAMETER") pairIndex: Int, pair: LiveComposePair): Boolean {
        val coverUri = pair.coverUri
        val videoUri = pair.videoUri
        if (coverUri == null || videoUri == null) return false

        val app = getApplication<Application>()
        val tmpDir = File(app.cacheDir, "live_compose_${System.currentTimeMillis()}_$pairIndex")
        tmpDir.mkdirs()
        return try {
            updateComposeProgress(15)
            val videoFile = File(tmpDir, "input_video.mp4")
            copyUriToFile(videoUri, videoFile)
            if (!videoFile.exists() || videoFile.length() == 0L) {
                return false
            }
            updateComposeProgress(30)
            val coverFile = File(tmpDir, "input_cover.jpg")
            copyUriToFile(coverUri, coverFile)

            updateComposeProgress(45)
            val outputFileName = "LivePhoto_${System.currentTimeMillis()}_$pairIndex"
            val result = LivePhotoMaker.createMotionPhoto(
                context = app,
                videoPath = videoFile.absolutePath,
                coverPath = coverFile.absolutePath,
                outputFileName = outputFileName,
                quality = LivePhotoMaker.QUALITY_BALANCED,
                presentationTimestampUs = 1_000_000,
                resize = null,
                onProgress = { pct ->
                    updateComposeProgress(45 + pct * 50 / 100)
                }
            )
            updateComposeProgress(98)
            if (result != null) {
                // 合成成功：删除源文件（best-effort，失败也不影响本次结果）
                runCatching { app.contentResolver.delete(coverUri, null, null) }
                runCatching { app.contentResolver.delete(videoUri, null, null) }
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun updateComposeProgress(percent: Int) {
        mainHandler.post {
            _uiState.update {
                val current = it.compose ?: return@post
                it.copy(compose = current.copy(percent = percent.coerceIn(0, 100)))
            }
        }
    }

    private fun copyUriToFile(uri: Uri, dest: File) {
        val app = getApplication<Application>()
        app.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        } ?: throw java.io.IOException("openInputStream returned null for $uri")
    }

    private fun finalizeCompose(success: Int, failure: Int) {
        ForegroundDownloadNotifier.finishCompose(getApplication(), success, failure)
        val message = if (failure == 0) {
            "合成完成：$success 张 Live 图"
        } else {
            "合成完成：$success 张成功，$failure 张失败"
        }
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
        // 清理 pending 列表，避免下一次误用
        pendingComposePairs.clear()
        _uiState.update { it.copy(compose = null) }
    }

    /** 用户点击"后台合成"：隐藏合成弹窗，保留后台任务 + 通知。 */
    fun dismissComposeDialog() {
        val current = _uiState.value.compose ?: return
        if (!current.active) return
        _uiState.update {
            it.copy(compose = current.copy(dialogDismissed = true))
        }
        // 清掉旧通知，立刻补一条当前进度的合成通知，避免通知栏空白
        ForegroundDownloadNotifier.cancel(getApplication())
        ForegroundDownloadNotifier.updateCompose(
            getApplication(),
            current.current.coerceAtMost(current.total),
            current.total,
        )
    }

    private fun finalizeBatch() {
        val batch = _uiState.value.batch ?: return
        val success = batch.successCount
        val failure = batch.failureCount
        // 1) 推一条"下载完成"通知
        ForegroundDownloadNotifier.finish(getApplication(), success, failure)
        // 2) 仅弹一次 Toast，把"第 N 个文件下载成功/失败"的多次提示合并为一次
        val message = if (failure == 0) {
            "下载完成：$success 个文件"
        } else {
            "下载完成：$success 个成功，$failure 个失败"
        }
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
        // 3) 清空批次状态，让弹窗收尾关闭
        _uiState.update { it.copy(batch = null) }
        // 4) 缓存的 profile 仍保留，UI 上还能继续看预览
    }

    private fun advanceFile(percent: Int = 0) {
        val current = _uiState.value.batch ?: return
        _uiState.update {
            it.copy(
                batch = current.copy(
                    current = (current.current + 1).coerceAtMost(current.total + 1),
                    percent = percent,
                )
            )
        }
    }

    private fun recordSuccess() {
        val current = _uiState.value.batch ?: return
        _uiState.update {
            it.copy(batch = current.copy(successCount = current.successCount + 1))
        }
    }

    private fun recordFailure() {
        val current = _uiState.value.batch ?: return
        _uiState.update {
            it.copy(batch = current.copy(failureCount = current.failureCount + 1))
        }
    }

    private fun downloadOne(
        profile: AwemeProfile,
        index: Int,
        overrideType: MediaType? = null,
    ) {
        val cookie = cookieManager.getCookie()
        downloaderBridge.download(profile, index, cookie, overrideType,
            object : DownloaderBridge.ProgressListener {
                override fun onProgress(percent: Int, downloaded: Long, total: Long) {
                    mainHandler.post {
                        val current = _uiState.value.batch ?: return@post
                        _uiState.update {
                            it.copy(batch = current.copy(percent = percent))
                        }
                        notifyProgressThrottled()
                    }
                }
            },
            object : DownloaderBridge.CompleteListener {
                // CompleteListener 是 DownloaderBridge 的 executor 工作线程里回调的，
                // 不是主线程。所有"更新 ViewModel 状态 / 推送系统通知 / 弹 Toast"的代码
                // 都必须切到主线程执行，否则 Toast.makeText 会抛 NullPointerException。
                override fun onSuccess(savedPath: String) {
                    mainHandler.post {
                        recordSuccess()
                        // 文件切到下一个；单文件下载也走这条路，保持逻辑统一
                        advanceFile()
                        notifyProgressThrottled(force = true)
                        finalizeBatchIfFinished()
                    }
                }

                override fun onError(error: Throwable) {
                    mainHandler.post {
                        recordFailure()
                        advanceFile()
                        notifyProgressThrottled(force = true)
                        finalizeBatchIfFinished()
                    }
                }
            })
    }

    /**
     * 节流后的"刷新下载进度通知"。每个文件 0~100 的 progress 更新非常密集，
     * 没必要每帧都发通知，这里至少 250ms 一次，或在被强制调用（文件完成 / dialogDismissed）时
     * 不做节流。
     */
    private fun notifyProgressThrottled(force: Boolean = false) {
        val batch = _uiState.value.batch ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAt < 250) return
        lastNotifyAt = now
        ForegroundDownloadNotifier.update(
            getApplication(),
            batch.current.coerceAtMost(batch.total),
            batch.total,
            batch.percent,
        )
    }
}
