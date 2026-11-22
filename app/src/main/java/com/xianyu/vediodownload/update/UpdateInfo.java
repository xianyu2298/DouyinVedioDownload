package com.xianyu.vediodownload.update;

/**
 * 一次「有新版本」的检查结果。
 *
 * <p>数据来源：Gitee OpenAPI {@code /repos/{owner}/{repo}/releases/latest}。
 * 字段与接口一一对应，不做二次加工，避免下游再猜。
 */
public final class UpdateInfo {

  private final String version;
  private final String title;
  private final String changelog;
  private final String apkUrl;
  private final String releasePageUrl;
  private final String publishedAt;

  public UpdateInfo(
      String version,
      String title,
      String changelog,
      String apkUrl,
      String releasePageUrl,
      String publishedAt) {
    this.version = safe(version);
    this.title = safe(title);
    this.changelog = safe(changelog);
    this.apkUrl = safe(apkUrl);
    this.releasePageUrl = safe(releasePageUrl);
    this.publishedAt = safe(publishedAt);
  }

  /** 纯版本号，已去掉 v 前缀，如 {@code "1.5"}。 */
  public String version() {
    return version;
  }

  /** 发行版标题，如 {@code "抖净v1.5"}。 */
  public String title() {
    return title;
  }

  /** 更新说明（release body 原文）。 */
  public String changelog() {
    return changelog;
  }

  /** APK 直链；该 release 未挂 APK 时为空串。 */
  public String apkUrl() {
    return apkUrl;
  }

  /** 下载页兜底地址。 */
  public String releasePageUrl() {
    return releasePageUrl;
  }

  /** 发布日期，格式 {@code yyyy-MM-dd}；解析失败为空串。 */
  public String publishedAt() {
    return publishedAt;
  }

  /** 优先打开 APK 直链，没有则退回下载页。 */
  public String downloadUrlOrDefault() {
    return apkUrl.isEmpty() ? releasePageUrl : apkUrl;
  }

  private static String safe(String v) {
    return v == null ? "" : v.trim();
  }
}
