package com.xianyu.vediodownload.ui.screens

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import com.xianyu.vediodownload.douyin.exception.WorkListFetchException
import com.xianyu.vediodownload.util.CookieManager
import com.xianyu.vediodownload.util.DownloaderBridge
import com.xianyu.vediodownload.util.ForegroundDownloadNotifier
import com.xianyu.vediodownload.util.LivePhotoMaker
import com.xianyu.vediodownload.util.looksLikeDouyinShareLink
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 作者下载 ViewModel —— 复用 DownloadViewModel 的批量下载与通知基础设施，
 * 但把"单作品"扩展为"作者全部作品"。
 *
 * - parse() 调用 DouyinDownloader.collectAccountWorksInfo() 拉取作者全部作品列表。
 * - 后续 downloadAll() / downloadVideoOnly() / downloadSelected() 共享 BatchSession。
 * - 选择模式由 isSelectionMode + selectedIndices 维护，与 ImageScreen 一致。
 */
class AuthorViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthorViewModel::class.java)) {
            return AuthorViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * 作者预览信息。数据来源于作者主页的第一个作品：
 * - nickname / avatar 来自第一个作品的 author 字段；
 * - 抖音号 / 背景图 不在列表 API 返回值中，置空即可（背景图直接复用头像，避免突兀空白）。
 */
data class AuthorPreview(
    val secUid: String = "",
    val nickname: String = "",
    val avatarUrl: String = "",
    val douyinId: String = "",
    val signature: String = "",
    val totalWorks: Int = 0,
)

data class AuthorUiState(
    val url: String = "",
    val isLoading: Boolean = false,
    val loadingMessage: String = "正在解析作者主页...",
    val error: String? = null,
    val author: AuthorPreview? = null,
    val works: List<AwemeProfile> = emptyList(),
    val isSelectionMode: Boolean = false,
    val selectedIndices: Set<Int> = emptySet(),
    // null = 不弹窗, "all" = 下载全部, "video" = 仅下载视频, "selected" = 下载所选
    val confirmTarget: String? = null,
    val batch: BatchSession? = null,
    // 合成阶段：下载全部完成、且用户勾选了"自动合成 Live 图"时启动
    val compose: ComposeSession? = null,
    // 用户在弹窗勾选的"自动合成 Live 图"，默认关闭
    val autoComposeLive: Boolean = false,
    // 预览提示：当前作者作品中 Live 配对数量（弹窗文案用）
    val livePairCount: Int = 0,
)

/**
 * Live 配对任务。每个 Live 图 = 1 个 cover + 1 个 video，
 * 两者都下成功后入合成队列，串行合成。
 */
private data class LivePairTask(
    val taskId: Int,
    val workIndex: Int,
    val liveIndex: Int,
    var coverPath: String? = null,
    var videoPath: String? = null,
    var coverFailed: Boolean = false,
    var videoFailed: Boolean = false,
)

/**
 * 单个下载任务的上下文。Live 配对靠 [livePairIndex] + [role] 串起来。
 */
private data class DownloadTask(
    val taskId: Int,
    val workIndex: Int,
    val urlIndex: Int,
    val overrideType: MediaType,
    val livePairIndex: Int? = null,
    val role: LivePairRole? = null,
)

private enum class LivePairRole { COVER, VIDEO }

/**
 * 合成阶段会话。独立于 [BatchSession]（下载阶段），便于 UI 区分显示。
 */
data class ComposeSession(
    val total: Int = 0,
    val current: Int = 0,
    val failed: Int = 0,
    val active: Boolean = false,
)

class AuthorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AuthorViewModel"
    }

    private val _uiState = MutableStateFlow(AuthorUiState())
    val uiState: StateFlow<AuthorUiState> = _uiState.asStateFlow()

    private val cookieManager by lazy { CookieManager(application) }
    private val downloaderBridge by lazy { DownloaderBridge(application) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotifyAt = 0L

    // 合成协程作用域：独立于 viewModelScope，保证下载弹窗被关掉后合成仍可继续
    private val composeScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("AuthorCompose")
    )

    // Live 配对追踪：(workIndex, livePairIndex) → task
    // 用 @Synchronized 保护：DownloaderBridge 的回调在 executor 线程触发，UI / 合成在主线程读写
    private val livePairTasks = mutableMapOf<Pair<Int, Int>, LivePairTask>()
    private val pendingLivePairs = mutableListOf<LivePairTask>()
    @Volatile private var composeWorkerRunning = false

    fun updateUrl(url: String) {
        _uiState.update { it.copy(url = url, error = null) }
    }

    /**
     * 退出作者详情页（由 AuthorScreen 的 BackHandler / GlassTopAppBar 返回键触发）。
     * 清空作者数据 + 选择态，但保留 url 方便用户继续改链接重新解析。
     */
    fun exitAuthorDetail() {
        _uiState.update {
            it.copy(
                author = null,
                works = emptyList(),
                isSelectionMode = false,
                selectedIndices = emptySet(),
                error = null,
                isLoading = false,
            )
        }
    }

    fun parse() {
        val raw = _uiState.value.url.trim()
        if (raw.isEmpty()) {
            _uiState.update { it.copy(error = "请输入作者主页链接或作品分享链接") }
            return
        }

        // 预校验：看起来不像抖音分享链接就直接提示，避免发起网络请求后失败
        if (!looksLikeDouyinShareLink(raw)) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = "链接格式不正确，请粘贴作者主页链接或任意抖音作品分享链接"
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                loadingMessage = "正在解析作者主页...",
                error = null,
                author = null,
                works = emptyList(),
                isSelectionMode = false,
                selectedIndices = emptySet(),
            )
        }

        viewModelScope.launch {
            try {
                val cookie = cookieManager.getCookie()
                val profiles = withContext(Dispatchers.IO) {
                    val downloader = DouyinDownloader(cookie)
                    try {
                        // 第一阶段：按作者主页解析。
                        // - 直接作者主页链接（含 /user/、长链接、短链 redirect 后到作者主页）直接成功；
                        // - 单作品/合集短链 redirect 后不是作者主页，会抛 WorkListFetchException，
                        //   此时进入第二阶段（"作品 → 作者"）。
                        downloader.collectAccountWorksInfo(raw)
                    } catch (e: WorkListFetchException) {
                        // 第二阶段：用作品解析拿到作者 sec_uid，再构造作者主页走作者解析。
                        _uiState.update {
                            it.copy(loadingMessage = "正在识别作品作者...")
                        }
                        val workProfile = downloader.collectWorkInfo(raw)
                        val secUid = workProfile.authorSecUserId().trim()
                        if (secUid.isEmpty()) {
                            throw IllegalArgumentException(
                                "无法从该作品获取作者信息，请改用作者主页链接（含 /user/）"
                            )
                        }
                        _uiState.update {
                            it.copy(loadingMessage = "正在获取作者的全部作品...")
                        }
                        val accountUrl = "https://www.douyin.com/user/$secUid"
                        downloader.collectAccountWorksInfo(accountUrl)
                    }
                }

                if (profiles.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "该作者未发布任何作品或已被风控，请稍后重试"
                        )
                    }
                    return@launch
                }

                val first = profiles.first()
                val author = AuthorPreview(
                    secUid = first.authorSecUserId(),
                    nickname = first.authorNickname(),
                    avatarUrl = first.authorAvatarUrl(),
                    douyinId = "",
                    signature = first.desc(),
                    totalWorks = profiles.size,
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        author = author,
                        works = profiles,
                        livePairCount = profiles.sumOf { p ->
                            val mt = p.imageMediaTypes()
                            if (mt.isEmpty()) 0
                            else mt.count { type -> type == MediaType.VIDEO }
                        },
                    )
                }
            } catch (e: WorkListFetchException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = resolveErrorMessage(e, forWorkList = true)
                    )
                }
            } catch (e: AwemeDetailFetchException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = resolveErrorMessage(e, forWorkList = false)
                    )
                }
            } catch (e: IllegalArgumentException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message
                            ?: "链接格式不正确，请粘贴作者主页链接或任意抖音作品分享链接"
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

    // ========== 批量下载触发（先弹风控窗，确认后真正开始） ==========

    fun requestDownloadAll() {
        if (_uiState.value.works.isEmpty()) return
        _uiState.update { it.copy(confirmTarget = "all") }
    }

    fun requestDownloadVideoOnly() {
        val hasVideo = _uiState.value.works.any { isPureVideoWork(it) }
        if (!hasVideo) {
            // 没有视频就直接 toast 提示，无需弹窗
            Toast.makeText(getApplication(), "该作者没有视频作品（不含 Live）", Toast.LENGTH_SHORT).show()
            return
        }
        _uiState.update { it.copy(confirmTarget = "video") }
    }

    fun dismissConfirm() {
        _uiState.update { it.copy(confirmTarget = null) }
    }

    /**
     * 用户在确认弹窗里勾选"自动合成 Live 图"。
     * 仅影响下一次 confirmDownload() 触发的批次；批次结束后会自动重置为 false，
     * 避免误以为后续也会自动合成。
     */
    fun setAutoComposeLive(enabled: Boolean) {
        _uiState.update { it.copy(autoComposeLive = enabled) }
    }

    fun confirmDownload() {
        val target = _uiState.value.confirmTarget ?: return
        _uiState.update { it.copy(confirmTarget = null) }
        when (target) {
            "all" -> downloadAll()
            "video" -> downloadVideoOnly()
            "selected" -> downloadSelected()
        }
    }

    // ========== 选择模式 ==========

    fun toggleSelectionMode() {
        _uiState.update {
            it.copy(
                isSelectionMode = !it.isSelectionMode,
                selectedIndices = if (it.isSelectionMode) emptySet() else it.selectedIndices
            )
        }
    }

    fun exitSelectionMode() {
        _uiState.update {
            it.copy(isSelectionMode = false, selectedIndices = emptySet())
        }
    }

    fun toggleWorkSelection(index: Int) {
        _uiState.update {
            val current = it.selectedIndices
            val nowSelected = index !in current
            it.copy(
                // 首次点击作品时自动进入选择模式（避免再多点一次"选择下载"按钮）
                isSelectionMode = it.isSelectionMode || nowSelected,
                selectedIndices = if (nowSelected) current + index else current - index,
            )
        }
    }

    fun selectAllWorks() {
        _uiState.update { it.copy(selectedIndices = (0 until it.works.size).toSet()) }
    }

    fun deselectAllWorks() {
        _uiState.update { it.copy(selectedIndices = emptySet()) }
    }

    fun requestDownloadSelected() {
        if (_uiState.value.selectedIndices.isEmpty()) return
        _uiState.update { it.copy(confirmTarget = "selected") }
    }

    // ========== 实际批量下载 ==========

    /**
     * 把单个作品展开成可下载任务列表：
     * - 纯视频作品：1 个 (urlIndex=0, type=VIDEO)
     * - 纯图集：N 个 (urlIndex=i, type=IMAGE)
     * - Live 作品：每张 Live 拆成 (cover, video) 两个任务，并附 livePairIndex / role
     */
    private fun expandWorkToTasks(
        profile: AwemeProfile,
        workIndex: Int,
        startTaskId: Int,
    ): List<DownloadTask> {
        val mediaTypes = profile.imageMediaTypes()
        if (mediaTypes.isEmpty()) {
            // 纯视频作品（Live 走 IMAGE 分支，纯视频走这里）
            return listOf(
                DownloadTask(
                    taskId = startTaskId,
                    workIndex = workIndex,
                    urlIndex = 0,
                    overrideType = profile.mediaType(),
                )
            )
        }
        // 图集 / Live：按 (IMAGE, VIDEO) 配对扫描
        val tasks = mutableListOf<DownloadTask>()
        var i = 0
        var livePairIdx = 0
        var taskId = startTaskId
        while (i < mediaTypes.size) {
            if (i + 1 < mediaTypes.size
                && mediaTypes[i] == MediaType.IMAGE
                && mediaTypes[i + 1] == MediaType.VIDEO
            ) {
                tasks.add(
                    DownloadTask(
                        taskId = taskId++,
                        workIndex = workIndex,
                        urlIndex = i,
                        overrideType = MediaType.IMAGE,
                        livePairIndex = livePairIdx,
                        role = LivePairRole.COVER,
                    )
                )
                tasks.add(
                    DownloadTask(
                        taskId = taskId++,
                        workIndex = workIndex,
                        urlIndex = i + 1,
                        overrideType = MediaType.LIVE_VIDEO,
                        livePairIndex = livePairIdx,
                        role = LivePairRole.VIDEO,
                    )
                )
                livePairIdx++
                i += 2
            } else {
                // 普通 IMAGE（图集条目）
                tasks.add(
                    DownloadTask(
                        taskId = taskId++,
                        workIndex = workIndex,
                        urlIndex = i,
                        overrideType = mediaTypes[i],
                    )
                )
                i += 1
            }
        }
        return tasks
    }

    /** 纯视频（非 Live）作品判定 */
    private fun isPureVideoWork(profile: AwemeProfile): Boolean {
        return profile.imageMediaTypes().isEmpty() && profile.mediaType() == MediaType.VIDEO
    }

    private fun downloadAll() {
        val works = _uiState.value.works
        if (works.isEmpty()) return
        val tasks = buildTaskList(works.indices.map { it })
        if (tasks.isEmpty()) return
        startBatch(total = tasks.size) {
            tasks.forEachIndexed { idx, ctx ->
                downloadOne(works[ctx.workIndex], ctx)
                if (idx < tasks.lastIndex) delay(800)
            }
        }
    }

    /**
     * 仅下载视频 —— 严格过滤：只下 `imageMediaTypes` 为空且 `mediaType == VIDEO` 的作品，
     * **不包含 Live 作品**（Live 已在 `downloadAll` 的 Live 配对中处理）。
     */
    private fun downloadVideoOnly() {
        val works = _uiState.value.works
        val videoWorkIndices = works.indices.filter { isPureVideoWork(works[it]) }
        if (videoWorkIndices.isEmpty()) {
            Toast.makeText(getApplication(), "该作者没有纯视频作品（不含 Live）", Toast.LENGTH_SHORT).show()
            return
        }
        val tasks = buildTaskList(videoWorkIndices)
        if (tasks.isEmpty()) return
        startBatch(total = tasks.size) {
            tasks.forEachIndexed { idx, ctx ->
                downloadOne(works[ctx.workIndex], ctx)
                if (idx < tasks.lastIndex) delay(800)
            }
        }
    }

    private fun downloadSelected() {
        val works = _uiState.value.works
        val selected = _uiState.value.selectedIndices.sorted()
        if (selected.isEmpty()) return
        val tasks = buildTaskList(selected)
        if (tasks.isEmpty()) return
        startBatch(total = tasks.size) {
            tasks.forEachIndexed { idx, ctx ->
                downloadOne(works[ctx.workIndex], ctx)
                if (idx < tasks.lastIndex) delay(800)
            }
        }
    }

    /** 把 work 下标列表展开成 DownloadTask，全局递增 taskId */
    private fun buildTaskList(workIndices: List<Int>): List<DownloadTask> {
        val works = _uiState.value.works
        val tasks = mutableListOf<DownloadTask>()
        var taskId = 0
        for (workIdx in workIndices) {
            val sub = expandWorkToTasks(works[workIdx], workIdx, taskId)
            tasks.addAll(sub)
            taskId += sub.size
        }
        return tasks
    }

    private fun startBatch(total: Int, work: suspend () -> Unit) {
        if (total <= 0) return
        // 清空上一轮的 Live 配对残留（保险，正常流程 finalizeBatch 已清）
        synchronized(livePairTasks) {
            livePairTasks.clear()
            pendingLivePairs.clear()
        }
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

        viewModelScope.launch {
            try {
                work()
            } catch (_: Throwable) {
                finalizeBatchIfFinished()
            }
        }
    }

    private fun finalizeBatchIfFinished() {
        val batch = _uiState.value.batch ?: return
        if (!batch.active) return
        val completed = batch.successCount + batch.failureCount
        if (completed < batch.total) return
        finalizeBatch()
    }

    private fun finalizeBatch() {
        val batch = _uiState.value.batch ?: return
        val success = batch.successCount
        val failure = batch.failureCount
        ForegroundDownloadNotifier.finish(getApplication(), success, failure)
        val message = if (failure == 0) {
            "下载完成：$success 个文件"
        } else {
            "下载完成：$success 个成功，$failure 个失败"
        }
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
        _uiState.update { it.copy(batch = null) }

        // 收集已配对完成的 Live 任务（cover + video 都成功），清理内部状态
        val readyPairs = synchronized(livePairTasks) {
            val ready = pendingLivePairs.toList()
            livePairTasks.clear()
            pendingLivePairs.clear()
            ready
        }

        // 下载批次结束后重置 autoComposeLive 标志，避免后续误以为会自动合成
        val autoCompose = _uiState.value.autoComposeLive
        _uiState.update { it.copy(autoComposeLive = false) }

        if (autoCompose && readyPairs.isNotEmpty()) {
            startComposeWorker(readyPairs)
        } else if (autoCompose) {
            // 用户勾选了自动合成但本次批次没有 Live 配对成功，静默即可
            Log.d(TAG, "autoComposeLive=true but no Live pairs to compose")
        }
    }

    private fun advanceFile() {
        val current = _uiState.value.batch ?: return
        _uiState.update {
            it.copy(
                batch = current.copy(
                    current = (current.current + 1).coerceAtMost(current.total + 1),
                    percent = 0,
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

    private fun downloadOne(profile: AwemeProfile, ctx: DownloadTask) {
        val cookie = cookieManager.getCookie()
        downloaderBridge.download(profile, ctx.urlIndex, cookie, ctx.overrideType,
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
                    mainHandler.post {
                        if (ctx.livePairIndex != null && ctx.role != null) {
                            onLivePairPath(ctx.workIndex, ctx.livePairIndex, ctx.role, savedPath)
                        }
                        recordSuccess()
                        advanceFile()
                        notifyProgressThrottled(force = true)
                        finalizeBatchIfFinished()
                    }
                }

                override fun onError(error: Throwable) {
                    mainHandler.post {
                        if (ctx.livePairIndex != null && ctx.role != null) {
                            onLivePairFailure(ctx.workIndex, ctx.livePairIndex, ctx.role)
                        }
                        recordFailure()
                        advanceFile()
                        notifyProgressThrottled(force = true)
                        finalizeBatchIfFinished()
                    }
                }
            })
    }

    // ========== Live 配对追踪 ==========

    private fun onLivePairPath(workIdx: Int, liveIdx: Int, role: LivePairRole, path: String) {
        synchronized(livePairTasks) {
            val key = workIdx to liveIdx
            val pair = livePairTasks.getOrPut(key) {
                LivePairTask(taskId = livePairTasks.size, workIndex = workIdx, liveIndex = liveIdx)
            }
            when (role) {
                LivePairRole.COVER -> pair.coverPath = path
                LivePairRole.VIDEO -> pair.videoPath = path
            }
            if (pair.coverPath != null && pair.videoPath != null) {
                pendingLivePairs.add(pair)
                livePairTasks.remove(key)
            }
        }
    }

    private fun onLivePairFailure(workIdx: Int, liveIdx: Int, role: LivePairRole) {
        synchronized(livePairTasks) {
            val key = workIdx to liveIdx
            val pair = livePairTasks.getOrPut(key) {
                LivePairTask(taskId = livePairTasks.size, workIndex = workIdx, liveIndex = liveIdx)
            }
            when (role) {
                LivePairRole.COVER -> pair.coverFailed = true
                LivePairRole.VIDEO -> pair.videoFailed = true
            }
            // 失败对不进入合成队列，但仍保留在 map 中供 finalizeBatch 决定后续行为
        }
    }

    // ========== 合成阶段 ==========

    private fun startComposeWorker(pairs: List<LivePairTask>) {
        if (pairs.isEmpty()) return
        if (composeWorkerRunning) return
        composeWorkerRunning = true

        _uiState.update {
            it.copy(compose = ComposeSession(total = pairs.size, current = 0, failed = 0, active = true))
        }
        ForegroundDownloadNotifier.cancel(getApplication())
        ForegroundDownloadNotifier.startCompose(getApplication(), pairs.size)

        composeScope.launch {
            var successCount = 0
            var failureCount = 0
            for ((idx, pair) in pairs.withIndex()) {
                mainHandler.post {
                    _uiState.update {
                        it.copy(compose = it.compose?.copy(current = idx + 1))
                    }
                    ForegroundDownloadNotifier.updateCompose(
                        getApplication(), idx + 1, pairs.size
                    )
                }
                val ok = try {
                    composeOnePair(pair)
                } catch (e: Throwable) {
                    Log.e(TAG, "compose failed for workIdx=${pair.workIndex} liveIdx=${pair.liveIndex}", e)
                    false
                }
                if (ok) {
                    // 合成成功 → 删除源 cover / video（保留失败对的源文件不动）
                    pair.coverPath?.let { deleteMedia(it) }
                    pair.videoPath?.let { deleteMedia(it) }
                    successCount++
                } else {
                    failureCount++
                    mainHandler.post {
                        _uiState.update { state ->
                            val current = state.compose
                            if (current != null) {
                                state.copy(compose = current.copy(failed = current.failed + 1))
                            } else {
                                state
                            }
                        }
                    }
                }
            }
            mainHandler.post {
                composeWorkerRunning = false
                _uiState.update { it.copy(compose = null) }
                ForegroundDownloadNotifier.finishCompose(getApplication(), successCount, failureCount)
                val msg = when {
                    successCount > 0 && failureCount > 0 ->
                        "已合成 $successCount 张 Live 图，$failureCount 张失败（源文件已保留）"
                    failureCount > 0 ->
                        "$failureCount 张 Live 图合成失败（源文件已保留）"
                    successCount > 0 ->
                        "已合成 $successCount 张 Live 图"
                    else -> null
                }
                if (msg != null) {
                    Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 合成一张 Live 图。
     * - cover / video 可能是 content:// URI（Android 10+）或真实文件路径（Android 9-），
     *   先复制到 cache 文件再交给 LivePhotoMaker。
     * - 返回 true 表示合成成功（LivePhotoMaker 返回非 null 路径）。
     */
    private fun composeOnePair(pair: LivePairTask): Boolean {
        val ctx = getApplication<Application>()
        val videoPath = pair.videoPath ?: return false
        val coverPath = pair.coverPath
        val tmpDir = File(ctx.cacheDir, "live_compose_${System.currentTimeMillis()}_${pair.taskId}")
        if (!tmpDir.mkdirs()) {
            Log.w(TAG, "tmpDir mkdirs failed: ${tmpDir.absolutePath}")
        }
        return try {
            val videoFile = File(tmpDir, "video.mp4")
            if (!copyToCacheFile(videoPath, videoFile) || videoFile.length() == 0L) {
                Log.w(TAG, "copy video failed: $videoPath")
                return false
            }
            val coverFile: File? = coverPath?.let { p ->
                val f = File(tmpDir, "cover.jpg")
                if (copyToCacheFile(p, f) && f.length() > 0L) f else null
            }
            val composedPath = LivePhotoMaker.createMotionPhoto(
                context = ctx,
                videoPath = videoFile.absolutePath,
                coverPath = coverFile?.absolutePath,
                outputFileName = "LivePhoto_${pair.workIndex}_${pair.liveIndex}_${System.currentTimeMillis()}",
                quality = LivePhotoMaker.QUALITY_BALANCED,
                presentationTimestampUs = 1_000_000,
                resize = null,
                onProgress = null,
            )
            composedPath != null
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * 把 MediaStore URI 或文件路径复制到 cache 文件。
     * 返回 true 表示复制成功且目标文件存在。
     */
    private fun copyToCacheFile(pathOrUri: String, dest: File): Boolean {
        return try {
            val ctx = getApplication<Application>()
            if (pathOrUri.startsWith("content://")) {
                ctx.contentResolver.openInputStream(Uri.parse(pathOrUri))?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                } ?: return false
            } else {
                val src = File(pathOrUri)
                if (!src.exists()) return false
                src.copyTo(dest, overwrite = true)
            }
            dest.exists() && dest.length() > 0L
        } catch (e: Exception) {
            Log.w(TAG, "copyToCacheFile failed: $pathOrUri", e)
            false
        }
    }

    /**
     * 删除已保存的 MediaStore 条目或文件。
     * - content:// URI：走 ContentResolver.delete
     * - 真实文件路径：走 File.delete
     * 失败仅 log，不抛出（合成主流程不应被清理错误阻断）。
     */
    private fun deleteMedia(pathOrUri: String): Boolean {
        return try {
            val ctx = getApplication<Application>()
            if (pathOrUri.startsWith("content://")) {
                ctx.contentResolver.delete(Uri.parse(pathOrUri), null, null) > 0
            } else {
                File(pathOrUri).delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteMedia failed: $pathOrUri", e)
            false
        }
    }

    // ========== 退出 / 重入生命周期 ==========

    fun dismissBatchDialog() {
        val current = _uiState.value.batch ?: return
        _uiState.update {
            it.copy(batch = current.copy(dialogDismissed = true))
        }
        ForegroundDownloadNotifier.cancel(getApplication())
        notifyProgressThrottled(force = true)
    }

    fun onForegroundReturned() {
        ForegroundDownloadNotifier.cancel(getApplication())
        val current = _uiState.value.batch
        if (current != null && current.active) {
            _uiState.update {
                it.copy(batch = current.copy(dialogDismissed = false))
            }
        }
        // 合成阶段的"返回前台"：不重显弹窗（独立小卡片本身就不抢眼）
        // 让用户从 ComposeSession 自行查看
    }

    /**
     * 合成阶段小卡片点击：仅 dismiss（小卡片本身不阻塞工作），不影响后台合成协程。
     */
    fun dismissComposeCard() {
        if (_uiState.value.compose?.active != true) return
        _uiState.update { it.copy(compose = it.compose?.copy(active = false)) }
    }

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

    override fun onCleared() {
        super.onCleared()
        composeScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}