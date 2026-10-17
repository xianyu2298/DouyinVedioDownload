package com.xianyu.vediodownload.douyin.exception;

/**
 * 解析失败的细分类别，用于 UI 层展示针对性提示。
 *
 * <p>由 {@code DouyinDownloader} 在发起请求失败时，根据 HTTP 状态码与响应体内容判定，
 * 写入 {@link DouyinDownloaderException#reason()}，供 ViewModel 映射为具体文案。
 */
public enum FailReason {
  /** 网络不可达 / 超时 / SSL 等传输层问题。 */
  NETWORK,

  /** 抖音风控拦截（HTTP 403 / 412，或 IP 被标记）。通常与签名或环境指纹相关。 */
  RISK_CONTROL,

  /** 签名无效（a_bogus / WebSign 校验失败），响应体常含 invalid signature 字样。 */
  SIGNATURE_INVALID,

  /** 作品不存在、已删除或设为私密（响应体含作品不存在的标记）。 */
  WORK_NOT_FOUND,

  /** Cookie 缺失或关键字段（msToken / uifid / sessionid）不完整。 */
  COOKIE_MISSING,

  /** 无法归类的其他原因。 */
  UNKNOWN,
}
