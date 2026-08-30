package com.xianyu.vediodownload.util

/**
 * 快速校验用户粘贴的内容是否"看起来像"抖音分享链接/文案。
 *
 * 命中下列任一规则即视为通过：
 *  1. URL 域名属于 douyin.com / iesdouyin.com
 *  2. 文本中包含 http(s):// 链接（让下载器内部再做 host 校验）
 *  3. 抖音 App 分享口令关键字（"复制链接"、"打开抖音"、"https://v.douyin.com/" 等）
 *  4. 含纯数字作品 ID（长度 15~20）
 *
 * 该函数是粗筛，仅用于 UI 层拦截明显无效的输入。真正的 host / 链接合法性校验仍由
 * [com.xianyu.vediodownload.douyin.DouyinDownloader] 内部完成。
 */
internal fun looksLikeDouyinShareLink(text: String): Boolean {
    val lower = text.lowercase()
    if ("douyin.com" in lower || "iesdouyin" in lower) return true
    if (Regex("""https?://""").containsMatchIn(text)) return true
    if (Regex("""\b\d{15,20}\b""").containsMatchIn(text)) return true
    return false
}

/**
 * 把"任意抖音分享链接 / 文案"粗筛出作者 sec_uid。
 *
 * - 若输入本身包含 `/user/{secUid}` 或 `sec_user_id=...`，直接返回该 secUid；
 * - 否则调用 [DouyinDownloader.collectWorkInfo] 拿到单作品详情，再从作者字段读取 sec_uid；
 * - 都拿不到时返回 null，由调用方决定走账号链接校验还是直接报错。
 *
 * 用于 AuthorViewModel 的两阶段解析：先通过单个作品链接解析出作者 sec_uid，再走作者主页。
 */
internal fun extractAuthorSecUidFallback(
    raw: String,
    collectWorkInfo: (String) -> com.xianyu.vediodownload.douyin.AwemeProfile?
): String? {
    // 直接从输入里抽 sec_uid
    val urlPattern = Regex("""/user/([A-Za-z0-9_\-]+)""")
    val queryPattern = Regex("""(?:^|[?&])(?:sec_user_id|secUid)=([^&\s]+)""")
    urlPattern.find(raw)?.groupValues?.getOrNull(1)?.let { if (it.isNotBlank()) return it }
    queryPattern.find(raw)?.groupValues?.getOrNull(1)?.let { if (it.isNotBlank()) return it }

    // 否则用作品解析拿作者 sec_uid
    val profile = try {
        collectWorkInfo(raw)
    } catch (_: Throwable) {
        return null
    }
    val secUid = profile?.authorSecUserId()?.trim().orEmpty()
    return secUid.takeIf { it.isNotEmpty() }
}
