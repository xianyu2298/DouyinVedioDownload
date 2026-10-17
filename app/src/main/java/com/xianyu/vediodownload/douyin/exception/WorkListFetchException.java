package com.xianyu.vediodownload.douyin.exception;

public final class WorkListFetchException extends DouyinDownloaderException {
  public WorkListFetchException(String message) {
    super(message);
  }

  public WorkListFetchException(String message, FailReason reason) {
    super(message, reason);
  }
}
