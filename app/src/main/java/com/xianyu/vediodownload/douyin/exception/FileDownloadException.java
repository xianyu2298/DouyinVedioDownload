package com.xianyu.vediodownload.douyin.exception;

public final class FileDownloadException extends DouyinDownloaderException {
  public FileDownloadException(String message) {
    super(message);
  }

  public FileDownloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
