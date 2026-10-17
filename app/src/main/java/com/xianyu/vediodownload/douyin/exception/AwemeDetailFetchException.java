package com.xianyu.vediodownload.douyin.exception;

public final class AwemeDetailFetchException extends DouyinDownloaderException {
  public AwemeDetailFetchException(String message) {
    super(message);
  }

  public AwemeDetailFetchException(String message, FailReason reason) {
    super(message, reason);
  }
}
