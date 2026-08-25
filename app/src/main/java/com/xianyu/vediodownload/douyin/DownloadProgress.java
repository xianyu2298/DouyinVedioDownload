package com.xianyu.vediodownload.douyin;

public final class DownloadProgress {
  private final String scope;
  private final int completed;
  private final int total;
  private final String currentId;
  private final boolean success;
  private final String message;

  public DownloadProgress(String scope, int completed, int total, String currentId, boolean success, String message) {
    this.scope = scope;
    this.completed = completed;
    this.total = total;
    this.currentId = currentId;
    this.success = success;
    this.message = message;
  }

  public String scope() { return scope; }
  public int completed() { return completed; }
  public int total() { return total; }
  public String currentId() { return currentId; }
  public boolean success() { return success; }
  public String message() { return message; }

  public double progress() {
    return total > 0 ? (double) completed / total : 0;
  }
}