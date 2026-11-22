package com.xianyu.vediodownload.kuaishou;

import android.util.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu.vediodownload.douyin.AwemeProfile;
import com.xianyu.vediodownload.douyin.DouyinDownloader;
import com.xianyu.vediodownload.douyin.exception.DouyinDownloaderException;
import com.xianyu.vediodownload.douyin.exception.FailReason;

import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 快手作品解析（单作品）。
 *
 * <p>技术路线：解析 H5 分享页 {@code https://v.m.chenzhongtech.com/fw/photo/{photoId}}
 * 里内联的 {@code window.INIT_STATE}。实测结论：
 * <ul>
 *   <li>匿名可访问，<b>无需 Cookie</b>；
 *   <li>必须使用移动端 UA，桌面 UA 会被 302 到 www 且页面不含作品数据；
 *   <li>网页端（含登录态）最高只下发 720p 一档，这是服务端策略，客户端无法突破。
 * </ul>
 *
 * <p>短链形态：{@code v.kuaishou.com/xxx}、{@code kuaishou.com/f/xxx}，跟随 302 取最终地址。
 * 详情形态：{@code /short-video/{id}}、{@code /fw/photo/{id}}、{@code ?photoId={id}}。
 *
 * <p>⚠️ 仅供个人技术学习使用，禁止商用
 */
public final class KuaishouDownloader {

  private static final String TAG = "KuaishouDownloader";

  private static final String[] TRUSTED_SHARE_HOSTS = {
    "kuaishou.com", "kuaishou.cn", "chenzhongtech.com", "chenzhongtech.cn"
  };

  private static final String MOBILE_USER_AGENT =
      "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36";
  private static final String DETAIL_REFERER = "https://v.m.chenzhongtech.com/";
  private static final String DETAIL_PAGE = "https://v.m.chenzhongtech.com/fw/photo/";

  private static final Pattern SHORT_VIDEO_PATH = Pattern.compile("/short-video/([^/?#]+)");
  private static final Pattern FW_PHOTO_PATH = Pattern.compile("/fw/photo/([^/?#]+)");
  private static final Pattern PLAIN_PHOTO_PATH = Pattern.compile("/photo/([^/?#]+)");
  private static final Pattern PHOTO_ID_QUERY =
      Pattern.compile("(?:^|[?&])(?:photoId|photo_id)=([^&\\s]+)");
  private static final Pattern LIVE_PATH = Pattern.compile("/u/[^/]+/([^/?#]+)");

  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String cookie;

  public KuaishouDownloader() {
    this("");
  }

  /**
   * @param cookie 可选的快手网页 Cookie（当前解析路径不需要，仅作为风控兜底预留；传空字符串即可）
   */
  public KuaishouDownloader(String cookie) {
    this.httpClient =
        new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    this.cookie = cookie == null ? "" : cookie.trim();
  }

  /** 文本中是否包含快手分享链接（粗筛，供 UI 层拦截明显无效输入）。 */
  public static boolean containsKuaishouLink(String text) {
    if (text == null || text.trim().isEmpty()) {
      return false;
    }
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.contains("kuaishou.com")
        || lower.contains("kuaishou.cn")
        || lower.contains("chenzhongtech.com")
        || lower.contains("chenzhongtech.cn")) {
      return true;
    }
    return false;
  }

  public static boolean isTrustedShareUrl(String url) {
    return isHostInAllowList(url, TRUSTED_SHARE_HOSTS);
  }

  public AwemeProfile collectWorkInfo(String shareLink) throws DouyinDownloaderException {
    if (shareLink == null || shareLink.trim().isEmpty()) {
      throw new IllegalArgumentException("shareLink is blank");
    }

    String rawUrl = extractFirstUrl(shareLink).orElse(shareLink.trim());
    if (!rawUrl.contains("://") && containsKuaishouLink(rawUrl)) {
      rawUrl = "https://" + rawUrl;
    }
    if (!isTrustedShareUrl(rawUrl)) {
      throw new IllegalArgumentException("Unsupported share link host");
    }

    String photoId = extractPhotoId(rawUrl).orElse("");
    if (photoId.isEmpty()) {
      // 短链：跟随 302 拿到最终地址后再提取
      String resolved = resolveFinalUrl(rawUrl);
      if (!isTrustedShareUrl(resolved)) {
        throw new IllegalArgumentException("Unsupported resolved share link host");
      }
      photoId = extractPhotoId(resolved).orElse("");
      if (photoId.isEmpty()) {
        photoId = extractPhotoIdFromHtml(fetchPage(resolved)).orElse("");
      }
    }
    if (photoId.isEmpty()) {
      throw new IllegalArgumentException("Cannot extract kuaishou photo id from shareLink");
    }

    String html = fetchPage(DETAIL_PAGE + photoId);
    JsonNode photo = parsePhotoNode(html);
    if (photo == null) {
      Log.w(TAG, "photo node missing, html length=" + html.length());
      throw new DouyinDownloaderException(
          "kuaishou photo node not found (page might be a captcha or the work is gone)",
          FailReason.WORK_NOT_FOUND);
    }

    AwemeProfile profile = KuaishouPhotoParser.parse(photo, photoId);
    Log.d(
        TAG,
        "parsed photoId="
            + photoId
            + " type="
            + photo.path("photoType").asText("")
            + " quality="
            + profile.qualityLabel()
            + " urls="
            + profile.downloadUrls().size());
    return profile;
  }

  private String fetchPage(String url) throws DouyinDownloaderException {
    Request.Builder builder =
        new Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", DETAIL_REFERER);
    if (!cookie.isEmpty()) {
      builder.header("Cookie", cookie);
    }
    try (Response response = httpClient.newCall(builder.build()).execute()) {
      ResponseBody body = response.body();
      String text = body != null ? body.string() : "";
      if (!response.isSuccessful() && text.isEmpty()) {
        throw new DouyinDownloaderException(
            "kuaishou page HTTP " + response.code(), FailReason.NETWORK);
      }
      return text;
    } catch (IOException e) {
      throw new DouyinDownloaderException(
          "kuaishou page request failed: " + e.getMessage(), e, FailReason.NETWORK);
    }
  }

  private String resolveFinalUrl(String url) throws DouyinDownloaderException {
    Request.Builder builder =
        new Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
    if (!cookie.isEmpty()) {
      builder.header("Cookie", cookie);
    }
    try (Response response = httpClient.newCall(builder.build()).execute()) {
      return response.request().url().toString();
    } catch (IOException e) {
      throw new DouyinDownloaderException(
          "kuaishou short link resolve failed: " + e.getMessage(), e, FailReason.NETWORK);
    }
  }

  /**
   * 解析 {@code window.INIT_STATE} 并取出 {@code photo} 节点。
   *
   * <p>页面结构：{@code window.INIT_STATE = {"<混淆键>": {..., "photo": {...}}, ...}}。
   * 键名做过凯撒 +1 移位，但值是标准 JSON，因此只按值递归查找，不依赖键名。
   */
  private JsonNode parsePhotoNode(String html) {
    if (html == null || html.isEmpty()) {
      return null;
    }
    int marker = html.indexOf("window.INIT_STATE");
    if (marker < 0) {
      return null;
    }
    int start = html.indexOf('{', marker);
    int end = html.indexOf("</script>", start);
    if (start < 0 || end <= start) {
      return null;
    }
    String json = html.substring(start, end).trim();
    if (json.endsWith(";")) {
      json = json.substring(0, json.length() - 1);
    }
    try {
      return findPhotoNode(objectMapper.readTree(json));
    } catch (IOException e) {
      Log.w(TAG, "INIT_STATE parse failed: " + e.getMessage());
      return null;
    }
  }

  private JsonNode findPhotoNode(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isObject()) {
      JsonNode photo = node.get("photo");
      if (photo != null && photo.isObject() && photo.has("photoId")) {
        return photo;
      }
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      while (it.hasNext()) {
        JsonNode found = findPhotoNode(it.next().getValue());
        if (found != null) {
          return found;
        }
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        JsonNode found = findPhotoNode(child);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private Optional<String> extractPhotoId(String url) {
    if (url == null || url.isBlank()) {
      return Optional.empty();
    }
    Pattern[] patterns = {SHORT_VIDEO_PATH, FW_PHOTO_PATH, PLAIN_PHOTO_PATH, LIVE_PATH};
    for (Pattern pattern : patterns) {
      Matcher m = pattern.matcher(url);
      if (m.find()) {
        String id = m.group(1).trim();
        if (!id.isEmpty()) {
          return Optional.of(id);
        }
      }
    }
    Matcher q = PHOTO_ID_QUERY.matcher(url);
    if (q.find()) {
      String id = q.group(1).trim();
      if (!id.isEmpty()) {
        return Optional.of(id);
      }
    }
    return Optional.empty();
  }

  private Optional<String> extractPhotoIdFromHtml(String html) {
    if (html == null || html.isEmpty()) {
      return Optional.empty();
    }
    Matcher m = FW_PHOTO_PATH.matcher(html);
    if (m.find()) {
      String id = m.group(1).trim();
      if (!id.isEmpty()) {
        return Optional.of(id);
      }
    }
    return Optional.empty();
  }

  private static Optional<String> extractFirstUrl(String text) {
    Matcher matcher = DouyinDownloader.URL_PATTERN.matcher(text);
    if (!matcher.find()) {
      return Optional.empty();
    }
    String url = stripTrailingPunctuation(matcher.group(1));
    return url.isEmpty() ? Optional.empty() : Optional.of(url);
  }

  private static String stripTrailingPunctuation(String url) {
    int end = url.length();
    while (end > 0) {
      char c = url.charAt(end - 1);
      if (c == '.' || c == ',' || c == ')' || c == ']' || c == '}' || c == '\''
          || c == '"' || c == ';' || c == ':' || c == '!' || c == '*') {
        end--;
      } else {
        break;
      }
    }
    return end < "https://a".length() ? url : url.substring(0, end);
  }

  private static boolean isHostInAllowList(String url, String[] allowList) {
    String host = extractHost(url);
    if (host.isEmpty()) {
      return false;
    }
    String normalized = host.toLowerCase(Locale.ROOT);
    for (String allowed : allowList) {
      if (normalized.equals(allowed) || normalized.endsWith("." + allowed)) {
        return true;
      }
    }
    return false;
  }

  private static String extractHost(String url) {
    if (url == null) {
      return "";
    }
    String trimmed = url.trim();
    int schemeEnd = trimmed.indexOf("://");
    if (schemeEnd < 0) {
      return "";
    }
    int start = schemeEnd + 3;
    if (start >= trimmed.length()) {
      return "";
    }
    int end = trimmed.length();
    for (int i = start; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c == '/' || c == '?' || c == '#' || c == ':') {
        end = i;
        break;
      }
    }
    String host = trimmed.substring(start, end).trim();
    int at = host.lastIndexOf('@');
    if (at >= 0 && at + 1 < host.length()) {
      host = host.substring(at + 1);
    }
    return host;
  }
}
