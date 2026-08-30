package com.xianyu.vediodownload.ui.screens

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import com.xianyu.vediodownload.util.CookieManager
import com.xianyu.vediodownload.util.DownloaderBridge
import com.xianyu.vediodownload.util.ForegroundDownloadNotifier
import com.xianyu.vediodownload.util.LivePhotoMaker
import com.xianyu.vediodownload.util.looksLikeDouyinShareLink
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
    val liveCoverUrls: String = ""    // Live图封面URL列表，用 ||| 分隔
)

/**
 * 一次批量下载的会话状态。每次有下载任务开始就创建一个新会话。
 *
 * - [current] / [total]：1-based 当前文件序号 / 文件总数（用于弹窗与通知展示"第 N / M 个"）。
 * - [percent]：当前文件的下载进度（0..100）。整体进度 = (current-1 + percent/100) / total * 100。
 * - [dialogDismissed]：用户是否点击过"后台下载"。为 true 时弹窗不再显示，但下载仍继续。
 * - [successCount] / [failureCount]：本批次已完成的成功 / 失败计数，用于批次结束后的 Toast。
 */
data class BatchSession(
    val total: Int = 0,
    val current: Int = 0,
    val percent: Int = 0,
    val dialogDismissed: Boolean = false,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val active: Boolean = false,
)

/**
 * Live 图批量合成会话：完成下载后进入此会话，依次合成每对 cover + video。
 *
 * - [current] / [total]：1-based 当前合成序号 / 总对数。
 * - [percent]：当前合成步骤的内部进度（0..100）。
 * - [dialogDismissed]：用户是否点过"后台合成"，与 BatchSession 同语义。
 * - [successCount] / [failureCount]：成功合成 / 跳过的对数（cover 或 video 缺失视为跳过）。
 * - [phase]：当前阶段（DONE 表示整个流程已结束）。
 */
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
 * 与 HistoryScreens.kt 中以 "baseName" 为键的历史对类型不同，本类用于 in-memory 流水线。
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

    fun parse() {
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(error = "请输入分享链接") }
            return
        }

        // 预校验：看起来不像抖音分享链接就直接提示，避免发起网络请求后失败
        if (!looksLikeDouyinShareLink(url)) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = "链接格式不正确，请粘贴完整的抖音分享文案或链接"
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                loadingMessage = "正在解析...",
                error = null,
                preview = null,
            )
        }

        viewModelScope.launch {
            try {
                val cookie = cookieManager.getCookie()
                val profile = withContext(Dispatchers.IO) {
                    val downloader = DouyinDownloader(cookie)
                    downloader.collectWorkInfo(url)
                }

                cachedProfile = profile
                val mediaType = profile.mediaType()
                val hasLive = profile.imageMediaTypes().any { it == MediaType.VIDEO }

                // 分离图片和视频URL
                val imageUrls = mutableListOf<String>()
                val videoUrls = mutableListOf<String>()

                profile.downloadUrls().forEachIndexed { index, url ->
                    if (index < profile.imageMediaTypes().size) {
                        val type = profile.imageMediaTypes()[index]
                        if (type == MediaType.VIDEO) {
                            videoUrls.add(url)
                        } else {
                            imageUrls.add(url)
                        }
                    } else {
                        imageUrls.add(url)
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
                    liveCoverUrls = imageUrls.joinToString("|||")  // Live图的封面就是静态图片
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
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "无法解析该作品，可能已删除、设为私密或所在地区不可访问"
                    )
                }
            } catch (e: WorkListFetchException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "无法获取作品列表，请稍后重试"
                    )
                }
            } catch (e: IllegalArgumentException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "链接格式不正确，请粘贴完整的抖音分享文案或链接"
                    )
                }
            } catch (e: FileDownloadException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "作品数据缺失，请换个链接重试"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "解析失败，请检查网络后重试"
                    )
                }
            }
        }
    }

    fun downloadVideo() {
        val profile = cachedProfile ?: return
        startBatch(total = 1) {
            downloadOne(profile, 0)
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
