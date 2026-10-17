package com.xianyu.vediodownload.douyin.exception;

public class DouyinDownloaderException extends Exception {
  private final FailReason reason;

  public DouyinDownloaderException(String message) {
    this(message, FailReason.UNKNOWN);
  }

  public DouyinDownloaderException(String message, Throwable cause) {
    this(message, cause, FailReason.UNKNOWN);
  }

  public DouyinDownloaderException(String message, FailReason reason) {
    super(message);
    this.reason = reason == null ? FailReason.UNKNOWN : reason;
  }

  public DouyinDownloaderException(String message, Throwable cause, FailReason reason) {
    super(message, cause);
    this.reason = reason == null ? FailReason.UNKNOWN : reason;
  }

  /** 失败原因类别，供 UI 层映射为针对性提示文案。 */
  public FailReason reason() {
    return reason;
  }
}
