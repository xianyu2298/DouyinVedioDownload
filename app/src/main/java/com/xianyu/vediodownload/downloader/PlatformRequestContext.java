package com.xianyu.vediodownload.downloader;

import java.net.URI;
import java.util.List;
import java.util.Locale;

public final class PlatformRequestContext {
  private final String userAgent;
  private final String referer;
  private final String cookie;
  private final List<String> cookieHostSuffixes;

  public PlatformRequestContext(String userAgent, String referer, String cookie, List<String> cookieHostSuffixes) {
    this.userAgent = userAgent == null ? "" : userAgent.trim();
    this.referer = referer == null ? "" : referer.trim();
    this.cookie = cookie == null ? "" : cookie.trim();
    this.cookieHostSuffixes = cookieHostSuffixes == null ? List.of() : List.copyOf(cookieHostSuffixes);
  }

  public String userAgent() { return userAgent; }
  public String referer() { return referer; }
  public String cookie() { return cookie; }
  public List<String> cookieHostSuffixes() { return cookieHostSuffixes; }

  public boolean shouldAttachCookie(String url) {
    if (cookie.isEmpty() || url == null || url.isBlank()) {
      return false;
    }
    try {
      String host = URI.create(url.trim()).getHost();
      if (host == null || host.isBlank()) {
        return false;
      }
      String normalizedHost = host.toLowerCase(Locale.ROOT);
      for (String suffix : cookieHostSuffixes) {
        if (suffix == null || suffix.isBlank()) {
          continue;
        }
        String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals(normalizedSuffix)
            || normalizedHost.endsWith("." + normalizedSuffix)) {
          return true;
        }
      }
      return false;
    } catch (Exception ignored) {
      return false;
    }
  }
}