package com.xianyu.vediodownload.ui.screens

import com.xianyu.vediodownload.douyin.exception.DouyinDownloaderException
import com.xianyu.vediodownload.douyin.exception.FailReason

/**
 * 把解析失败的异常映射为用户可读的针对性提示文案。
 *
 * 与旧版"笼统报错"不同，这里按 [FailReason] 细分，让用户能明确知道
 * 是 Cookie 缺失、被风控、签名失效还是作品本身不可用。
 */
fun resolveErrorMessage(e: DouyinDownloaderException, forWorkList: Boolean): String {
    return when (e.reason()) {
        FailReason.NETWORK ->
            "网络连接失败，请检查网络或稍后重试"

        FailReason.RISK_CONTROL ->
            "请求被平台风控拦截（可能是网络 IP 被限制），请尝试切换网络后重试"

        FailReason.SIGNATURE_INVALID ->
            "签名校验失败，软件解析算法可能需要更新，请更新到最新版本"

        FailReason.COOKIE_MISSING ->
            "Cookie 缺失或不完整，请在「设置」页重新获取 Cookie（需包含 sessionid / uifid / ttwid）"

        FailReason.WORK_NOT_FOUND ->
            if (forWorkList) "该作者暂无作品或作品不可见" else "该作品可能已删除、设为私密或所在地区不可访问"

        FailReason.NO_LINK_FOUND ->
            "没有识别到可用的分享链接，请复制完整的分享文案（含作品链接）后重试"

        FailReason.LINK_INCOMPLETE ->
            "链接无法解析，可能被换行截断或已失效，请回到 App 重新复制一次分享文案"

        FailReason.UNKNOWN ->
            if (forWorkList) "无法获取作品列表，请稍后重试" else "无法解析该作品，请稍后重试"
    }
}
