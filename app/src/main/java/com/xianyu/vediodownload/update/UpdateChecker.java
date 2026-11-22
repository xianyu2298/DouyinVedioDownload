package com.xianyu.vediodownload.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 基于 Gitee Releases 的版本检查。
 *
 * <p>数据源：{@code GET https://gitee.com/api/v5/repos/jbl2003/DouyinVedioDownload/releases/latest}
 * ——免 token、免登录，返回 tag_name / name / body / assets[].browser_download_url。
 *
 * <p>设计约束（对应实际使用规模）：
 * <ul>
 *   <li><b>12 小时本地缓存</b>：Gitee 匿名接口按 IP 限流（X-RateLimit-Limit: 60），
 *       缓存把每台设备的请求压到每天最多 2 次，几十个用户分散在不同 IP 上不会触发限流。
 *   <li><b>失败只抛异常、不重试</b>：自动检查由调用方静默处理，避免网络问题时打扰用户。
 *   <li><b>同版本只提示一次</b>：{@link #shouldPrompt(String)} 依据本地记录的已提示版本。
 *   <li>不内置 access_token：APK 可被反编译，token 等同公开，匿名请求足够。
 * </ul>
 */
public final class UpdateChecker {

  private static final String TAG = "UpdateChecker";

  private static final String API_URL =
      "https://gitee.com/api/v5/repos/jbl2003/DouyinVedioDownload/releases/latest";
  private static final String RELEASES_PAGE =
      "https://gitee.com/jbl2003/DouyinVedioDownload/releases";
  private static final String RELEASE_TAG_PAGE =
      "https://gitee.com/jbl2003/DouyinVedioDownload/releases/tag/";

  private static final String PREF_NAME = "app_prefs";
  private static final String KEY_PROMPTED_VERSION = "update_prompted_version";
  private static final String KEY_CACHED_AT = "update_cached_at";
  private static final String KEY_CACHED_VERSION = "update_cached_version";
  private static final String KEY_CACHED_TITLE = "update_cached_title";
  private static final String KEY_CACHED_BODY = "update_cached_body";
  private static final String KEY_CACHED_APK = "update_cached_apk";
  private static final String KEY_CACHED_PAGE = "update_cached_page";
  private static final String KEY_CACHED_DATE = "update_cached_date";

  private static final long CACHE_TTL_MS = 12L * 60L * 60L * 1000L;

  private final Context appContext;
  private final SharedPreferences prefs;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final OkHttpClient httpClient;
  private final String currentVersion;

  public UpdateChecker(Context context) {
    this.appContext = context.getApplicationContext();
    this.prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    this.currentVersion = readVersionName(appContext);
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build();
  }

  public String currentVersion() {
    return currentVersion;
  }

  /**
   * 检查是否有新版本。
   *
   * @param ignoreCache true = 强制联网（设置页手动检查）；false = 优先用 12 小时内的缓存
   * @return 有新版本返回信息；已是最新返回 null
   * @throws IOException 网络失败、响应异常或 JSON 解析失败（由调用方决定是否提示）
   */
  public UpdateInfo check(boolean ignoreCache) throws IOException {
    long now = System.currentTimeMillis();
    long cachedAt = prefs.getLong(KEY_CACHED_AT, 0L);
    if (!ignoreCache && cachedAt > 0 && now - cachedAt < CACHE_TTL_MS) {
      return fromCacheIfNewer();
    }

    JsonNode root = fetchLatestRelease();
    if (root == null) {
      throw new IOException("empty response body");
    }
    if (root.path("prerelease").asBoolean(false)) {
      // 预发布版本不提示，避免用户装到测试包
      writeCache("", "", "", "", "", "");
      return null;
    }

    String version = normalizeVersion(root.path("tag_name").asText(""));
    if (version.isEmpty() || !isNewer(currentVersion, version)) {
      writeCache("", "", "", "", "", "");
      return null;
    }

    String title = root.path("name").asText("").trim();
    String body = root.path("body").asText("");
    String publishedAt = shortDate(root.path("created_at").asText(""));
    String apkUrl = findApkUrl(root.path("assets"));
    String pageUrl = RELEASE_TAG_PAGE + root.path("tag_name").asText("").trim();

    writeCache(version, title, body, apkUrl, pageUrl, publishedAt);
    return new UpdateInfo(version, title, body, apkUrl, pageUrl, publishedAt);
  }

  /** 该版本是否还需要弹窗提示（同一版本只提示一次）。 */
  public boolean shouldPrompt(String version) {
    if (version == null || version.isEmpty()) {
      return false;
    }
    return !version.equals(prefs.getString(KEY_PROMPTED_VERSION, ""));
  }

  /** 记录已提示版本，之后同一版本不再自动弹窗。 */
  public void markPrompted(String version) {
    if (version == null) {
      return;
    }
    prefs.edit().putString(KEY_PROMPTED_VERSION, version).apply();
  }

  private UpdateInfo fromCacheIfNewer() {
    String version = prefs.getString(KEY_CACHED_VERSION, "");
    if (version.isEmpty() || !isNewer(currentVersion, version)) {
      return null;
    }
    return new UpdateInfo(
        version,
        prefs.getString(KEY_CACHED_TITLE, ""),
        prefs.getString(KEY_CACHED_BODY, ""),
        prefs.getString(KEY_CACHED_APK, ""),
        prefs.getString(KEY_CACHED_PAGE, RELEASES_PAGE),
        prefs.getString(KEY_CACHED_DATE, ""));
  }

  private void writeCache(
      String version, String title, String body, String apkUrl, String pageUrl, String date) {
    prefs.edit()
        .putLong(KEY_CACHED_AT, System.currentTimeMillis())
        .putString(KEY_CACHED_VERSION, version == null ? "" : version)
        .putString(KEY_CACHED_TITLE, title == null ? "" : title)
        .putString(KEY_CACHED_BODY, body == null ? "" : body)
        .putString(KEY_CACHED_APK, apkUrl == null ? "" : apkUrl)
        .putString(KEY_CACHED_PAGE, pageUrl == null ? "" : pageUrl)
        .putString(KEY_CACHED_DATE, date == null ? "" : date)
        .apply();
  }

  private JsonNode fetchLatestRelease() throws IOException {
    Request request =
        new Request.Builder()
            .url(API_URL)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "DouyinDownloader-Android")
            .build();
    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new IOException("gitee api HTTP " + response.code());
      }
      ResponseBody body = response.body();
      String text = body != null ? body.string() : "";
      if (text.trim().isEmpty()) {
        return null;
      }
      return objectMapper.readTree(text);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IOException("gitee release json parse failed", e);
    }
  }

  /** 取第一个 .apk 附件；没有则返回空串（调用方回落到下载页）。 */
  private static String findApkUrl(JsonNode assets) {
    if (assets == null || !assets.isArray()) {
      return "";
    }
    for (JsonNode asset : assets) {
      String name = asset.path("name").asText("").toLowerCase(Locale.ROOT);
      String url = asset.path("browser_download_url").asText("").trim();
      if (name.endsWith(".apk") && !url.isEmpty()) {
        return url;
      }
    }
    return "";
  }

  /** {@code "2026-11-22T10:00:00+08:00"} → {@code "2026-11-22"}。 */
  private static String shortDate(String iso) {
    String v = iso == null ? "" : iso.trim();
    return v.length() >= 10 ? v.substring(0, 10) : v;
  }

  /** 去掉前导 v/V、预发布后缀，只保留数字与点。 */
  static String normalizeVersion(String raw) {
    if (raw == null) {
      return "";
    }
    String v = raw.trim();
    if (v.startsWith("v") || v.startsWith("V")) {
      v = v.substring(1);
    }
    int cut = v.length();
    int dash = v.indexOf('-');
    int plus = v.indexOf('+');
    if (dash >= 0) {
      cut = Math.min(cut, dash);
    }
    if (plus >= 0) {
      cut = Math.min(cut, plus);
    }
    return v.substring(0, cut).trim();
  }

  /**
   * 语义化版本比较：{@code remote > local} 返回 true。
   *
   * <p>按 {@code .} 拆段逐段比数字，缺段补 0；任一侧无法解析出有效段则返回 false
   * （宁可漏提示，也不误打扰用户）。
   */
  static boolean isNewer(String local, String remote) {
    int[] a = parseVersion(normalizeVersion(local));
    int[] b = parseVersion(normalizeVersion(remote));
    if (a.length == 0 || b.length == 0) {
      return false;
    }
    int len = Math.max(a.length, b.length);
    for (int i = 0; i < len; i++) {
      int left = i < a.length ? a[i] : 0;
      int right = i < b.length ? b[i] : 0;
      if (right != left) {
        return right > left;
      }
    }
    return false;
  }

  private static int[] parseVersion(String version) {
    if (version.isEmpty()) {
      return new int[0];
    }
    String[] parts = version.split("\\.");
    int[] result = new int[parts.length];
    int count = 0;
    for (String part : parts) {
      String digits = leadingDigits(part);
      if (digits.isEmpty()) {
        break;
      }
      try {
        result[count++] = Integer.parseInt(digits);
      } catch (NumberFormatException e) {
        break;
      }
    }
    if (count == 0) {
      return new int[0];
    }
    int[] trimmed = new int[count];
    System.arraycopy(result, 0, trimmed, 0, count);
    return trimmed;
  }

  private static String leadingDigits(String s) {
    int end = 0;
    while (end < s.length() && Character.isDigit(s.charAt(end))) {
      end++;
    }
    return s.substring(0, end);
  }

  private static String readVersionName(Context context) {
    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      return info.versionName == null ? "" : info.versionName.trim();
    } catch (Exception e) {
      return "";
    }
  }
}
