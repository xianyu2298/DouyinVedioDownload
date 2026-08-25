package com.xianyu.vediodownload.douyin;

@FunctionalInterface
public interface DownloadProgressCallback {
  void onProgress(DownloadProgress progress);
}
