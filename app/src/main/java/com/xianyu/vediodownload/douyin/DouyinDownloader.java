package com.xianyu.vediodownload.douyin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu.vediodownload.douyin.exception.AwemeDetailFetchException;
import com.xianyu.vediodownload.douyin.exception.DouyinDownloaderException;
import com.xianyu.vediodownload.douyin.exception.FailReason;
import com.xianyu.vediodownload.douyin.exception.FileDownloadException;
import com.xianyu.vediodownload.douyin.exception.WorkListFetchException;
import com.xianyu.vediodownload.util.LinkTextNormalizer;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class DouyinDownloader {
  // 用 RFC 3986 的 URL 合法字符白名单，而非黑名单。
  // 旧的黑名单只排除少量中文标点，漏掉了汉字 / emoji / 全角字符 / 零宽字符，
  // 一旦 URL 后面紧跟这些字符（抖音分享文案常见），它们会被吞进 URL，
  // 导致 URI 解析失败并被误报为「链接格式不正确」。
  public static final Pattern URL_PATTERN =
      Pattern.compile("(https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)");
  private static final Pattern AWEME_ID_PATTERN = Pattern.compile("(?<!\\d)(\\d{19})(?!\\d)");
  private static final Pattern SEC_USER_ID_URL_PATTERN = Pattern.compile("/user/([^/?#]+)");
  private static final Pattern SEC_USER_ID_QUERY_PATTERN =
      Pattern.compile("(?:^|[?&])(?:sec_user_id|secUid)=([^&\\s]+)");
  private static final Pattern SEC_USER_ID_JSON_PATTERN =
      Pattern.compile("\"(?:sec_user_id|secUid)\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern MIX_ID_URL_PATTERN = Pattern.compile("/collection/(\\d{5,25})");
  private static final Pattern MIX_ID_QUERY_PATTERN =
      Pattern.compile("(?:^|[?&])(?:mix_id|mixId|collectionId)=(\\d{5,25})(?:&|$)");
  private static final Pattern MIX_ID_JSON_PATTERN =
      Pattern.compile("\"(?:mix_id|mixId|collectionId)\"\\s*:\\s*\"?(\\d{5,25})\"?");
  private static final int DEFAULT_MAX_PAGES = 200;
  private static final int ACCOUNT_PAGE_SIZE = 18;
  private static final int MIX_PAGE_SIZE = 12;
  private static final String[] TRUSTED_SHARE_HOSTS = {"douyin.com", "iesdouyin.com"};
  private static final String[] TRUSTED_COOKIE_HOSTS = {"douyin.com", "iesdouyin.com"};
  // 必须与 a_bogus 签名使用的 UA 一致（第三条摘要链与其绑定），
  // 且浏览器指纹参数（browser_name/browser_version/engine_version/browser_platform）
  // 必须与之匹配，否则签名校验失败。
  private static final String DEFAULT_USER_AGENT =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";
  private static final String DEFAULT_REFERER = "https://www.douyin.com/?recommend=1";

  /**
   * 移动端 UA，仅用于短链二次尝试。
   *
   * <p>实测：某些短链对桌面 UA / 带登录态 Cookie 的请求不返回 302，而是 200 落地页，
   * 导致拿不到 19 位作品 ID。换移动 UA 且不带 Cookie 再请求一次，通常能拿到正常重定向。
   */
  private static final String MOBILE_USER_AGENT =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
          + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
  // 抖音对以下受保护接口要求附加 x-secsdk-web-signature，否则返回 403 Uifid Not Found
  private static final String[] SIGNED_PATHS = {
    "/aweme/v1/web/aweme/detail/",
    "/aweme/v1/web/aweme/post/",
    "/aweme/v1/web/aweme/favorite/",
    "/aweme/v1/web/aweme/listcollection/",
    "/aweme/v1/web/mix/aweme/",
    "/aweme/v1/web/tab/feed/",
    "/aweme/v1/web/mix/list/",
    "/aweme/v1/web/music/aweme/",
    "/aweme/v1/web/music/list/",
    "/aweme/v1/web/mix/detail/",
    "/aweme/v1/web/mix/listcollection/",
    "/aweme/v1/web/music/detail/",
    "/aweme/v1/web/collects/list/",
    "/aweme/v1/web/collects/video/list/",
  };
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ABogusGenerator aBogusGenerator;
  private final String defaultCookie;

  public DouyinDownloader() {
    this("");
  }

  public DouyinDownloader(String cookie) {
    this(defaultClient(), cookie);
  }

  public DouyinDownloader(String cookie, int ignoredDownloadThreads) {
    this(defaultClient(), cookie);
  }

  public DouyinDownloader(OkHttpClient httpClient, String cookie) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.objectMapper = new ObjectMapper();
    this.aBogusGenerator = new ABogusGenerator(DEFAULT_USER_AGENT);
    this.defaultCookie = normalizeCookie(cookie);
  }

  public DouyinDownloader(OkHttpClient httpClient, String cookie, int ignoredDownloadThreads) {
    this(httpClient, cookie);
  }

  public static boolean containsDouyinLink(String text) {
    return extractSupportedShareUrl(text).isPresent();
  }

  public static boolean isTrustedShareUrl(String url) {
    return isHostInAllowList(url, TRUSTED_SHARE_HOSTS);
  }

  public static boolean shouldAttachCookie(String url) {
    return isHostInAllowList(url, TRUSTED_COOKIE_HOSTS);
  }

  public static boolean isAccountLink(String text) {
    if (text == null) return false;
    return text.contains("/user/") || text.contains("sec_user_id");
  }

  public static boolean isMixLink(String text) {
    if (text == null) return false;
    return text.contains("/collection/") || text.contains("mix_id");
  }

  private static OkHttpClient defaultClient() {
    return new OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // 让客户端明确支持 TLS 1.2 / 1.3，避免某些 CDN 节点 SSL 握手失败
        .connectionSpecs(
            java.util.Arrays.asList(
                okhttp3.ConnectionSpec.MODERN_TLS,
                okhttp3.ConnectionSpec.COMPATIBLE_TLS,
                okhttp3.ConnectionSpec.CLEARTEXT))
        .build();
  }

  private static Map<String, String> buildAccountListParams(String secUserId, long cursor) {
    Map<String, String> extraParams = new LinkedHashMap<>();
    extraParams.put("sec_user_id", secUserId);
    extraParams.put("max_cursor", String.valueOf(cursor));
    extraParams.put("locate_query", "false");
    extraParams.put("show_live_replay_strategy", "1");
    extraParams.put("need_time_list", "1");
    extraParams.put("time_list_query", "0");
    extraParams.put("whale_cut_token", "");
    extraParams.put("cut_version", "1");
    extraParams.put("count", String.valueOf(ACCOUNT_PAGE_SIZE));
    extraParams.put("publish_video_strategy_type", "2");
    return extraParams;
  }

  private static Optional<String> extractSupportedShareUrl(String text) {
    for (String url : extractAllUrls(LinkTextNormalizer.normalize(text))) {
      if (isTrustedShareUrl(url)) {
        return Optional.of(url);
      }
    }
    return Optional.empty();
  }

  private static boolean isHostInAllowList(String url, String[] allowList) {
    if (url == null || url.isBlank()) {
      return false;
    }
    String host = extractHost(url.trim());
    if (host.isEmpty()) {
      return false;
    }
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    for (String allowedHost : allowList) {
      if (normalizedHost.equals(allowedHost) || normalizedHost.endsWith("." + allowedHost)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 手动从 URL 中截取 host（scheme:// 之后，到第一个 / ? # : 之前）。
   *
   * <p>不使用 {@code URI.create()}：它对残留的中文 / emoji / 全角字符会直接抛异常，
   * 那样即使域名本身正确也会被判成不信任链接。手动截取只看 host 段，鲁棒性更好。
   */
  private static String extractHost(String url) {
    int schemeEnd = url.indexOf("://");
    if (schemeEnd < 0) {
      return "";
    }
    int start = schemeEnd + 3;
    if (start >= url.length()) {
      return "";
    }
    int end = url.length();
    for (int i = start; i < url.length(); i++) {
      char c = url.charAt(i);
      // ':' 用于截掉端口；'/' '?' '#' 用于截掉路径与查询
      if (c == '/' || c == '?' || c == '#' || c == ':') {
        end = i;
        break;
      }
    }
    String host = url.substring(start, end).trim();
    // 去掉可能的 userinfo（user@host 形式）
    int at = host.lastIndexOf('@');
    if (at >= 0 && at + 1 < host.length()) {
      host = host.substring(at + 1);
    }
    return host;
  }

  /** 文本中是否出现抖音域名（用于给缺 scheme 的短链自动补 https://）。 */
  private static boolean looksLikeDouyinDomain(String text) {
    if (text == null || text.isEmpty()) {
      return false;
    }
    String lower = text.toLowerCase(Locale.ROOT);
    return lower.contains("douyin.com") || lower.contains("iesdouyin.com");
  }

  /** 文本里没有 scheme 时，从域名出现处截出 "host/path" 片段（抖音域名版）。 */
  private static String findBareDouyinUrl(String text) {
    return findBareDomainUrl(text, new String[] {"douyin.com", "iesdouyin.com"});
  }

  /** 通用实现：在文本中定位第一个出现的域名，截到下一个空白为止。 */
  private static String findBareDomainUrl(String text, String[] domains) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    String lower = text.toLowerCase(Locale.ROOT);
    int index = -1;
    for (String needle : domains) {
      int pos = lower.indexOf(needle);
      if (pos >= 0 && (index < 0 || pos < index)) {
        index = pos;
      }
    }
    if (index < 0) {
      return "";
    }
    // 向左扩展子域名（v. / www. / v.m.），否则 "v.douyin.com/x" 会被截成 "douyin.com/x"
    int start = index;
    while (start > 0) {
      char c = text.charAt(start - 1);
      if (Character.isLetterOrDigit(c) || c == '.' || c == '-') {
        start--;
      } else {
        break;
      }
    }
    String tail = text.substring(start);
    int end = 0;
    while (end < tail.length() && !Character.isWhitespace(tail.charAt(end))) {
      end++;
    }
    return stripTrailingPunctuation(tail.substring(0, end));
  }

  private static Optional<String> extractFirstUrl(String text) {
    if (text == null || text.trim().isEmpty()) {
      return Optional.empty();
    }
    Matcher matcher = URL_PATTERN.matcher(text);
    if (matcher.find()) {
      String url = stripTrailingPunctuation(matcher.group(1));
      return url.isEmpty() ? Optional.empty() : Optional.of(url);
    }
    return Optional.empty();
  }

  /**
   * 去掉 URL 尾部常见的包裹标点。
   *
   * <p>分享文本中 URL 常被标点包裹，如 {@code (https://v.douyin.com/xxx/)} 或
   * {@code https://v.douyin.com/xxx/.}。URL 合法字符白名单包含这些标点，
   * 因此需要额外剥离，避免把标点带进真机请求。
   */
  private static String stripTrailingPunctuation(String url) {
    if (url == null || url.isEmpty()) {
      return "";
    }
    int end = url.length();
    while (end > 0) {
      char c = url.charAt(end - 1);
      if (c == '.' || c == ',' || c == ')' || c == ']' || c == '}'
          || c == '\'' || c == '"' || c == ';' || c == ':' || c == '!' || c == '*') {
        end--;
      } else {
        break;
      }
    }
    // 保护：截断后至少保留 "scheme://host" 的骨架，避免把 https:// 本身截坏
    if (end < "https://a".length()) {
      return url;
    }
    return url.substring(0, end);
  }

  public AwemeProfile collectWorkInfo(String shareLink) throws DouyinDownloaderException {
    return collectWorkInfo(shareLink, this.defaultCookie);
  }

  public AwemeProfile collectWorkInfo(String shareLink, String cookie)
      throws DouyinDownloaderException {
    SingleAwemeContext context = resolveSingleAwemeContext(shareLink, cookie);
    return collectAwemeProfileFromNode(context.aweme, context.awemeId);
  }

  public List<AwemeProfile> collectAccountWorksInfo(String accountShareLink)
      throws DouyinDownloaderException {
    return collectAccountWorksInfo(accountShareLink, this.defaultCookie, null);
  }

  public List<AwemeProfile> collectAccountWorksInfo(
      String accountShareLink, DownloadProgressCallback progressCallback)
      throws DouyinDownloaderException {
    return collectAccountWorksInfo(accountShareLink, this.defaultCookie, progressCallback);
  }

  public List<AwemeProfile> collectAccountWorksInfo(String accountShareLink, String cookie)
      throws DouyinDownloaderException {
    return collectAccountWorksInfo(accountShareLink, cookie, null);
  }

  public List<AwemeProfile> collectAccountWorksInfo(
      String accountShareLink, String cookie, DownloadProgressCallback progressCallback)
      throws DouyinDownloaderException {
    WorkListContext context = resolveAccountWorkListContext(accountShareLink, cookie);
    return collectAwemeProfileList(context.awemeList, "account_info", progressCallback);
  }

  public List<AwemeProfile> collectMixWorksInfo(String mixShareLink)
      throws DouyinDownloaderException {
    return collectMixWorksInfo(mixShareLink, this.defaultCookie, null);
  }

  public List<AwemeProfile> collectMixWorksInfo(
      String mixShareLink, DownloadProgressCallback progressCallback)
      throws DouyinDownloaderException {
    return collectMixWorksInfo(mixShareLink, this.defaultCookie, progressCallback);
  }

  public List<AwemeProfile> collectMixWorksInfo(String mixShareLink, String cookie)
      throws DouyinDownloaderException {
    return collectMixWorksInfo(mixShareLink, cookie, null);
  }

  public List<AwemeProfile> collectMixWorksInfo(
      String mixShareLink, String cookie, DownloadProgressCallback progressCallback)
      throws DouyinDownloaderException {
    WorkListContext context = resolveMixWorkListContext(mixShareLink, cookie);
    return collectAwemeProfileList(context.awemeList, "mix_info", progressCallback);
  }

  private WorkListContext resolveAccountWorkListContext(String accountShareLink, String cookie)
      throws DouyinDownloaderException {
    ResolvedShareLink resolved = resolveShareLink(accountShareLink, cookie, false);
    String secUserId =
        resolveSecUserId(accountShareLink, resolved.resolvedUrl, resolved.normalizedCookie);
    List<JsonNode> awemeList = fetchAccountAwemeList(secUserId, resolved.normalizedCookie);
    return new WorkListContext(resolved.normalizedCookie, awemeList);
  }

  private WorkListContext resolveMixWorkListContext(String mixShareLink, String cookie)
      throws DouyinDownloaderException {
    ResolvedShareLink resolved = resolveShareLink(mixShareLink, cookie, false);
    String mixId = resolveMixId(mixShareLink, resolved.resolvedUrl, resolved.normalizedCookie);
    List<JsonNode> awemeList = fetchMixAwemeList(mixId, resolved.normalizedCookie);
    return new WorkListContext(resolved.normalizedCookie, awemeList);
  }

  private SingleAwemeContext resolveSingleAwemeContext(String shareLink, String cookie)
      throws DouyinDownloaderException {
    if (shareLink == null || shareLink.trim().isEmpty()) {
      throw new IllegalArgumentException("shareLink is blank");
    }

    String text = LinkTextNormalizer.normalize(shareLink);
    ResolvedShareLink resolved = resolveShareLink(text, cookie, true);

    Optional<String> idFromResolved = extractAwemeId(resolved.resolvedUrl);
    if (idFromResolved.isEmpty()) {
      idFromResolved = extractAwemeId(text);
    }
    if (idFromResolved.isEmpty()) {
      // 短链未做 302（服务端直接返回落地页）时，从页面 HTML 里兜底提取
      idFromResolved = extractAwemeIdFromHtml(resolved.body);
    }
    if (idFromResolved.isEmpty() && !resolved.inputUrl.isEmpty()) {
      // 二次尝试：移动端 UA + 不带 Cookie，规避短链服务对桌面/登录态请求返回落地页
      try {
        HttpProbe retry = probeShortLink(resolved.inputUrl, resolved.normalizedCookie, true, true);
        Optional<String> retryId = extractAwemeId(retry.finalUrl);
        if (retryId.isEmpty()) {
          retryId = extractAwemeIdFromHtml(retry.body);
        }
        idFromResolved = retryId;
      } catch (DouyinDownloaderException ignored) {
        // 二次尝试失败不覆盖首次结果，最终错误信息仍以首次探测为准
      }
    }
    if (idFromResolved.isEmpty()) {
      throw new AwemeDetailFetchException(
          "cannot extract aweme_id, status="
              + resolved.status
              + ", final="
              + resolved.resolvedUrl
              + ", bodyLen="
              + resolved.body.length(),
          FailReason.LINK_INCOMPLETE);
    }
    String awemeId = idFromResolved.get();
    JsonNode aweme = fetchAwemeDetail(awemeId, resolved.normalizedCookie);
    return new SingleAwemeContext(resolved.normalizedCookie, awemeId, aweme);
  }

  /**
   * 从分享文本中挑出可用的分享链接并解析重定向。
   *
   * <p>与旧实现的关键差异：
   * <ul>
   *   <li>先做字符归一化（全角转半角、去零宽），避免输入法/转发改形导致匹配失败；
   *   <li>扫描文本中**所有** URL，取第一个域名属于本平台的，而不是只看第一个 URL——
   *       用户剪贴板里常残留别的内容，旧逻辑会因此误报「链接格式不正确」；
   *   <li>找不到链接、解析不出 ID、重定向到非本平台域名分别抛不同的 {@link FailReason}，
   *       让 UI 能给出针对性提示。
   * </ul>
   */
  private ResolvedShareLink resolveShareLink(String shareLink, String cookie, boolean captureBody)
      throws DouyinDownloaderException {
    String normalizedCookie = normalizeCookie(cookie);
    String text = LinkTextNormalizer.normalize(shareLink);

    List<String> urls = extractAllUrls(text);
    String picked = null;
    for (String candidate : urls) {
      if (isTrustedShareUrl(candidate)) {
        picked = candidate;
        break;
      }
    }

    if (picked == null) {
      // 兼容用户只复制了 "v.douyin.com/xxx" 主体（没有 https:// 前缀）
      String bare = findBareDouyinUrl(text);
      if (bare.isEmpty()) {
        throw new AwemeDetailFetchException(
            "no douyin link found, urls=" + urls, FailReason.NO_LINK_FOUND);
      }
      picked = "https://" + bare;
    }

    HttpProbe probe = probeShortLink(picked, normalizedCookie, false, captureBody);
    if (extractFirstUrl(probe.finalUrl).isPresent() && !isTrustedShareUrl(probe.finalUrl)) {
      throw new AwemeDetailFetchException(
          "resolved to untrusted host: " + probe.finalUrl, FailReason.LINK_INCOMPLETE);
    }
    return new ResolvedShareLink(
        normalizedCookie, probe.finalUrl, picked, probe.status, probe.body);
  }

  /**
   * 提取文本中所有 URL（已剥离尾部包裹标点），供域名筛选与诊断使用。
   */
  private static List<String> extractAllUrls(String text) {
    List<String> urls = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return urls;
    }
    Matcher matcher = URL_PATTERN.matcher(text);
    while (matcher.find()) {
      String url = stripTrailingPunctuation(matcher.group(1));
      if (!url.isEmpty()) {
        urls.add(url);
      }
    }
    return urls;
  }

  /**
   * 供「复制诊断信息」使用的链接识别结果：归一化后取前 3 个 URL。
   * 用户反馈解析异常时，这几个值能直接说明正则到底抓到了什么。
   */
  public static List<String> describeLinks(String text) {
    List<String> urls = extractAllUrls(LinkTextNormalizer.normalize(text));
    return urls.size() > 3 ? new ArrayList<>(urls.subList(0, 3)) : urls;
  }

  private List<AwemeProfile> collectAwemeProfileList(
      List<JsonNode> awemeList, String scope, DownloadProgressCallback progressCallback)
      throws DouyinDownloaderException {
    int total = awemeList.size();
    notifyProgress(progressCallback, new DownloadProgress(scope, 0, total, "", true, "started"));

    if (awemeList.isEmpty()) {
      return Collections.emptyList();
    }

    List<AwemeProfile> results = new ArrayList<>(awemeList.size());
    for (int i = 0; i < awemeList.size(); i++) {
      JsonNode aweme = awemeList.get(i);
      String awemeId = aweme.path("aweme_id").asText("").trim();
      int completed = i + 1;
      try {
        AwemeProfile profile = collectAwemeProfileFromNode(aweme, awemeId);
        results.add(profile);
        notifyProgress(
            progressCallback,
            new DownloadProgress(scope, completed, total, profile.awemeId(), true, "completed"));
      } catch (DouyinDownloaderException e) {
        notifyProgress(
            progressCallback,
            new DownloadProgress(scope, completed, total, awemeId, false, e.getMessage()));
        throw e;
      }
    }
    return Collections.unmodifiableList(results);
  }

  private AwemeProfile collectAwemeProfileFromNode(JsonNode aweme, String fallbackAwemeId)
      throws DouyinDownloaderException {
    String awemeId = aweme.path("aweme_id").asText("").trim();
    if (awemeId.isEmpty()) {
      awemeId = fallbackAwemeId == null ? "" : fallbackAwemeId.trim();
    }
    if (awemeId.isEmpty()) {
      throw new FileDownloadException("aweme_id is missing in aweme payload");
    }

    MediaType mediaType = isImagePost(aweme) ? MediaType.IMAGE : MediaType.VIDEO;
    String desc = aweme.path("desc").asText("");
    long createTime = aweme.path("create_time").asLong(0L);
    String authorNickname = aweme.path("author").path("nickname").asText("");
    String authorSecUserId = aweme.path("author").path("sec_uid").asText("");
    String authorAvatarUrl = extractAvatarUrl(aweme.path("author"));
    String collectionTitle = aweme.path("mix_info").path("mix_name").asText("").trim();
    List<String> thumbnailUrls;
    List<String> downloadUrls;
    List<MediaType> imageMediaTypes;
    String qualityLabel = "";

    if (mediaType == MediaType.IMAGE) {
      ImageAssetCollection imageAssets = collectImageAssets(aweme);
      thumbnailUrls = imageAssets.thumbnailUrls;
      downloadUrls = imageAssets.downloadUrls;
      imageMediaTypes = imageAssets.mediaTypes;
      if (thumbnailUrls.isEmpty()) {
        thumbnailUrls = collectThumbnailUrls(aweme);
      }
    } else {
      thumbnailUrls = collectThumbnailUrls(aweme);
      downloadUrls = collectVideoUrlCandidates(aweme);
      imageMediaTypes = List.of();
      // 与下载候选同源同序：显示哪一档，实际就下载哪一档
      qualityLabel = buildVideoQualityLabel(aweme);
    }
    String thumbnailUrl = thumbnailUrls.isEmpty() ? "" : thumbnailUrls.get(0);

    return new AwemeProfile(
        com.xianyu.vediodownload.model.Platform.DOUYIN,
        awemeId,
        mediaType,
        desc,
        createTime,
        authorNickname,
        authorSecUserId,
        authorAvatarUrl,
        collectionTitle,
        thumbnailUrl,
        thumbnailUrls,
        downloadUrls,
        imageMediaTypes,
        qualityLabel);
  }

  private String resolveSecUserId(String sourceText, String resolvedUrl, String cookie)
      throws WorkListFetchException {
    Optional<String> secUserId = extractSecUserId(resolvedUrl);
    if (secUserId.isEmpty()) secUserId = extractSecUserId(sourceText);
    // Only attempt to scrape sec_user_id from HTML when the link itself looks like an account link.
    // Otherwise (e.g. aweme/mix links), logged-in cookies may cause us to capture the *current
    // user* secUid.
    boolean isSelfUserPage =
        resolvedUrl != null
            && (resolvedUrl.contains("/user/self") || resolvedUrl.contains("/user/me"));
    if (secUserId.isEmpty()
        && !isSelfUserPage
        && (isAccountLink(resolvedUrl) || isAccountLink(sourceText))) {
      secUserId = fetchSecUserIdFromPage(resolvedUrl, cookie);
    }

    return secUserId
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .orElseThrow(
            () -> new WorkListFetchException("Cannot extract sec_user_id from account share link"));
  }

  private String resolveMixId(String sourceText, String resolvedUrl, String cookie)
      throws DouyinDownloaderException {
    Optional<String> mixId = extractMixId(resolvedUrl);
    if (mixId.isEmpty()) mixId = extractMixId(sourceText);
    if (mixId.isEmpty()) mixId = fetchMixIdFromPage(resolvedUrl, cookie);

    if (mixId.isPresent() && !mixId.get().isEmpty()) {
      return mixId.get();
    }

    Optional<String> awemeId = extractAwemeId(resolvedUrl);
    if (awemeId.isEmpty()) awemeId = extractAwemeId(sourceText);
    if (awemeId.isPresent()) {
      JsonNode aweme = fetchAwemeDetail(awemeId.get(), cookie);
      String resolvedMixId = aweme.path("mix_info").path("mix_id").asText("").trim();
      if (!resolvedMixId.isEmpty()) {
        return resolvedMixId;
      }
    }

    throw new WorkListFetchException("Cannot extract mix_id from mix share link");
  }

  private List<JsonNode> fetchAccountAwemeList(String secUserId, String cookie)
      throws WorkListFetchException {
    long cursor = 0;
    boolean hasMore = true;
    int pageCount = 0;
    long lastCursor = Long.MIN_VALUE;
    List<JsonNode> awemeList = new ArrayList<>();
    LinkedHashSet<String> seenAwemeIds = new LinkedHashSet<>();

    while (hasMore && pageCount < DEFAULT_MAX_PAGES) {
      Map<String, String> extraParams = buildAccountListParams(secUserId, cursor);

      String query = buildWebListQuery(extraParams, cookie, "https://www.douyin.com/aweme/v1/web/aweme/post/");
      JsonNode pageData =
          fetchPagedAwemeList(
              Arrays.asList(
                  "https://www.douyin.com/aweme/v1/web/aweme/post/?" + query,
                  "https://www.iesdouyin.com/aweme/v1/web/aweme/post/?" + query),
              cookie,
              "account");

      JsonNode items = pageData.path("aweme_list");
      if (!items.isArray()) {
        throw new WorkListFetchException("aweme_list is missing in account list response");
      }
      int newItemCount = 0;
      for (JsonNode item : items) {
        if (item.isObject()) {
          String awemeId = item.path("aweme_id").asText("").trim();
          if (!awemeId.isEmpty() && !seenAwemeIds.add(awemeId)) {
            continue;
          }
          awemeList.add(item);
          newItemCount++;
        }
      }

      hasMore = parseHasMore(pageData.path("has_more"));
      long nextCursor = pageData.path("max_cursor").asLong(cursor);
      pageCount++;

      if (hasMore
          && (newItemCount == 0
              || nextCursor == cursor
              || (lastCursor != Long.MIN_VALUE && nextCursor == lastCursor))) {
        break;
      }
      lastCursor = cursor;
      cursor = nextCursor;
    }

    return awemeList;
  }

  private List<JsonNode> fetchMixAwemeList(String mixId, String cookie)
      throws WorkListFetchException {
    long cursor = 0;
    boolean hasMore = true;
    int pageCount = 0;
    long lastCursor = Long.MIN_VALUE;
    List<JsonNode> awemeList = new ArrayList<>();
    LinkedHashSet<String> seenAwemeIds = new LinkedHashSet<>();

    while (hasMore && pageCount < DEFAULT_MAX_PAGES) {
      Map<String, String> extraParams = new LinkedHashMap<>();
      extraParams.put("mix_id", mixId);
      extraParams.put("cursor", String.valueOf(cursor));
      extraParams.put("count", String.valueOf(MIX_PAGE_SIZE));

      String query = buildWebListQuery(extraParams, cookie, "https://www.douyin.com/aweme/v1/web/mix/aweme/");
      JsonNode pageData =
          fetchPagedAwemeList(
              Arrays.asList(
                  "https://www.douyin.com/aweme/v1/web/mix/aweme/?" + query,
                  "https://www.iesdouyin.com/aweme/v1/web/mix/aweme/?" + query),
              cookie,
              "mix");

      JsonNode items = pageData.path("aweme_list");
      if (!items.isArray()) {
        throw new WorkListFetchException("aweme_list is missing in mix list response");
      }
      int newItemCount = 0;
      for (JsonNode item : items) {
        if (item.isObject()) {
          String awemeId = item.path("aweme_id").asText("").trim();
          if (!awemeId.isEmpty() && !seenAwemeIds.add(awemeId)) {
            continue;
          }
          awemeList.add(item);
          newItemCount++;
        }
      }

      hasMore = parseHasMore(pageData.path("has_more"));
      long nextCursor = pageData.path("cursor").asLong(cursor);
      pageCount++;

      if (hasMore
          && (newItemCount == 0
              || nextCursor == cursor
              || (lastCursor != Long.MIN_VALUE && nextCursor == lastCursor))) {
        break;
      }
      lastCursor = cursor;
      cursor = nextCursor;
    }

    return awemeList;
  }

  private JsonNode fetchPagedAwemeList(List<String> apiUrls, String cookie, String listType)
      throws WorkListFetchException {
    // 请求前校验 Cookie 关键字段（作者列表同样依赖 uifid/ttwid）。
    CookieIssue cookieIssue = checkCookieCompleteness(cookie);
    if (cookieIssue != CookieIssue.OK) {
      throw new WorkListFetchException(
          "Cookie missing required field: " + cookieIssue, FailReason.COOKIE_MISSING);
    }

    List<String> attempts = new ArrayList<>();
    FailReason reason = FailReason.UNKNOWN;

    for (String apiUrl : apiUrls) {
      try {
        Request request =
            requestBuilder(apiUrl, cookie)
                .get()
                .header("Accept", "application/json, text/plain, */*")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
          ResponseBody bodyObj = response.body();
          String body = bodyObj != null ? bodyObj.string() : "";
          body = body.trim();
          if (body.isEmpty()) {
            reason = pickMoreSpecific(reason, FailReason.RISK_CONTROL);
            attempts.add("HTTP=" + response.code() + ", URL=" + apiUrl + ", BODY=<empty>");
            continue;
          }

          JsonNode root = objectMapper.readTree(body);
          if (root.isObject()) {
            return root;
          }
          reason = pickMoreSpecific(reason, classifyDetailFailure(response.code(), body));
          attempts.add("HTTP=" + response.code() + ", URL=" + apiUrl + ", BODY=" + shortBody(body));
        }
      } catch (IOException | RuntimeException e) {
        reason = pickMoreSpecific(reason, FailReason.NETWORK);
        attempts.add(
            "ERR="
                + e.getClass().getSimpleName()
                + ", URL="
                + apiUrl
                + ", MSG="
                + shortBody(e.getMessage()));
      }
    }

    throw new WorkListFetchException(
        "Cannot fetch "
            + listType
            + " work list. Tried "
            + apiUrls.size()
            + " endpoints. "
            + String.join(" | ", attempts),
        reason);
  }

  private String resolveFinalUrl(String url, String cookie) throws DouyinDownloaderException {
    return probeShortLink(url, cookie, false, false).finalUrl;
  }

  /**
   * 请求短链并返回最终地址、状态码与正文片段。
   *
   * <p>两种模式：
   * <ul>
   *   <li>默认：沿用分享链接请求头（桌面 UA + 可选 Cookie）；
   *   <li>mobile=true：移动端 UA 且不带 Cookie——短链服务对桌面/登录态请求偶发返回落地页
   *       而不是 302，此时换一次身份往往就能拿到真正的重定向。
   * </ul>
   *
   * @param captureBody 是否保留 HTML 正文（仅在需要从落地页兜底提取作品 ID 时为 true）
   */
  private HttpProbe probeShortLink(String url, String cookie, boolean mobile, boolean captureBody)
      throws DouyinDownloaderException {
    Request.Builder builder;
    try {
      builder =
          mobile
              ? new Request.Builder()
                  .url(url)
                  .header("User-Agent", MOBILE_USER_AGENT)
                  .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
              : requestBuilder(url, cookie)
                  .get()
                  .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
    } catch (Exception e) {
      // 地址非法或请求头含非法字符：不再静默返回原地址（那会把问题伪装成「解析不出 ID」）
      throw new AwemeDetailFetchException(
          "cannot build request for " + url + ": " + e.getMessage(), FailReason.LINK_INCOMPLETE);
    }

    // 兜底要从正文里抓 ID，必须禁用压缩：requestBuilder 里设的是 Accept-Encoding: */*
    // （会让 OkHttp 不做透明解压），不覆盖的话读到的可能是 gzip 二进制。
    builder.header("Accept-Encoding", "identity");

    try (Response response = httpClient.newCall(builder.build()).execute()) {
      String finalUrl = response.request().url().toString();
      // 最终地址里已有 19 位作品 ID 时无需再读正文，省流量
      boolean needBody = captureBody && !finalUrl.matches(".*\\d{19}.*");
      String body = needBody ? readHtmlBody(response.body()) : "";
      return new HttpProbe(finalUrl, response.code(), body);
    } catch (IOException e) {
      throw new AwemeDetailFetchException(
          "Network error while resolving share link: " + e.getMessage(), FailReason.NETWORK);
    }
  }

  /**
   * 只读取 HTML/文本正文，最多 200KB。
   *
   * <p>两个健壮性处理：
   * <ul>
   *   <li>Content-Type 缺失时也尝试读取（有些拦截页不带类型）；
   *   <li>若响应仍是 gzip（服务端忽略了 identity），先解压再返回，否则正则会匹配到二进制。
   * </ul>
   */
  private static String readHtmlBody(ResponseBody body) {
    if (body == null) {
      return "";
    }
    String contentType = body.contentType() == null ? "" : body.contentType().toString();
    if (!contentType.isEmpty()
        && !contentType.contains("html")
        && !contentType.contains("text")) {
      return "";
    }
    byte[] bytes;
    try (java.io.InputStream in = body.byteStream()) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      int total = 0;
      while ((read = in.read(buffer)) != -1 && total < 200 * 1024) {
        out.write(buffer, 0, read);
        total += read;
      }
      bytes = out.toByteArray();
    } catch (IOException e) {
      return "";
    }

    if (bytes.length > 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
      try (java.util.zip.GZIPInputStream gzip =
          new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = gzip.read(buffer)) != -1 && total < 200 * 1024) {
          out.write(buffer, 0, read);
          total += read;
        }
        return out.toString(StandardCharsets.UTF_8.name());
      } catch (IOException e) {
        return "";
      }
    }
    try {
      return new String(bytes, StandardCharsets.UTF_8.name());
    } catch (java.io.UnsupportedEncodingException e) {
      return "";
    }
  }

  /**
   * 从落地页 HTML 里兜底提取作品 ID（分享页未做 302 时唯一可行的手段）。
   *
   * <p>抖音落地页的 ID 可能以多种形式出现：{@code video/7708...}、{@code modal_id":"7708..."}、
   * {@code modal_id=7708...}、{@code item_ids=...}、{@code "aweme_id":"7708..."}。
   * 用 {@code \D{0,4}} 吃掉键名与数字之间的引号/冒号/等号，避免为每种形式各写一条正则。
   */
  private static Optional<String> extractAwemeIdFromHtml(String html) {
    if (html == null || html.isEmpty()) {
      return Optional.empty();
    }
    // 先按「指向当前作品」的高置信形式匹配；这些键通常只描述当前分享的作品
    String[] specific = {
      "share/video/(\\d{19})",
      "modal_id\\D{0,4}(\\d{19})",
      "aweme_id\\D{0,4}(\\d{19})",
      "item_ids?\\D{0,4}(\\d{19})"
    };
    for (String regex : specific) {
      Matcher matcher = Pattern.compile(regex).matcher(html);
      if (matcher.find()) {
        return Optional.of(matcher.group(1));
      }
    }
    // 兜底：通用 video/{id}。仅在页面里唯一时才采用——落地页常带推荐列表，
    // 多个 video/{id} 时无法判断哪个是目标作品，宁可判失败也不下错视频。
    Matcher generic = Pattern.compile("video/(\\d{19})").matcher(html);
    String unique = null;
    int count = 0;
    while (generic.find()) {
      count++;
      if (count > 1) {
        return Optional.empty();
      }
      unique = generic.group(1);
    }
    return unique == null ? Optional.empty() : Optional.of(unique);
  }

  private JsonNode fetchAwemeDetail(String awemeId, String cookie)
      throws AwemeDetailFetchException {
    // 请求前先校验 Cookie 关键字段：缺失时直接报 COOKIE_MISSING，避免发起注定
    // 被 403 拒绝的请求（缺 uifid/ttwid 会被 Argus 拦截，容易被误判为风控）。
    CookieIssue cookieIssue = checkCookieCompleteness(cookie);
    if (cookieIssue != CookieIssue.OK) {
      throw new AwemeDetailFetchException(
          "Cookie missing required field: " + cookieIssue, FailReason.COOKIE_MISSING);
    }

    String detailQuery = buildWebDetailQuery(awemeId, cookie);
    List<String> candidates =
        Arrays.asList(
            "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=" + urlEncode(awemeId),
            "https://www.douyin.com/aweme/v1/web/aweme/detail/?" + detailQuery,
            "https://www.iesdouyin.com/aweme/v1/web/aweme/detail/?" + detailQuery);

    List<String> attempts = new ArrayList<>();
    FailReason reason = FailReason.UNKNOWN;
    for (String apiUrl : candidates) {
      try {
        android.util.Log.d("DouyinDownloader", "fetchAwemeDetail try: " + apiUrl);
        Request request =
            requestBuilder(apiUrl, cookie)
                .get()
                .header("Accept", "application/json, text/plain, */*")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
          int code = response.code();
          ResponseBody bodyObj = response.body();
          String body = bodyObj != null ? bodyObj.string() : "";
          body = body.trim();
          android.util.Log.d("DouyinDownloader", "  -> HTTP " + code + ", body length=" + body.length());
          // 完整打印 body，便于定位风控/签名/作品不存在的真实响应结构
          android.util.Log.d("DouyinDownloader", "  -> BODY=" + (body.length() > 2000 ? body.substring(0, 2000) : body));

          Optional<JsonNode> aweme = parseAwemeNode(body);
          if (aweme.isPresent()) {
            android.util.Log.d("DouyinDownloader", "  -> parsed OK");
            return aweme.get();
          }

          // 根据本轮 HTTP code + body 更新失败原因（取更具体的一档）
          reason = pickMoreSpecific(reason, classifyDetailFailure(code, body));
          attempts.add("HTTP=" + code + ", URL=" + apiUrl + ", BODY=" + shortBody(body));
        }
      } catch (IOException | RuntimeException e) {
        android.util.Log.e("DouyinDownloader", "  -> exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        // 网络层异常优先判定为 NETWORK，除非已有更具体的风控/签名结论
        reason = pickMoreSpecific(reason, FailReason.NETWORK);
        attempts.add(
            "ERR="
                + e.getClass().getSimpleName()
                + ", URL="
                + apiUrl
                + ", MSG="
                + shortBody(e.getMessage()));
      }
    }

    throw new AwemeDetailFetchException(
        "Cannot parse aweme detail for aweme_id="
            + awemeId
            + ". Tried "
            + candidates.size()
            + " endpoints. "
            + String.join(" | ", attempts)
            + ". Suggestion: ensure Cookie is injected and include signature chain (a_bogus/msToken/uifid).",
        reason);
  }

  /**
   * 根据单次请求的 HTTP 状态码与响应体内容判定失败原因。
   *
   * <p>判定规则对齐抖音 Argus 风控的实际行为（参考社区实测）：
   * <ol>
   *   <li>签名/参数拒绝：body 含 "uifid not found" / "signature not found" /
   *       "sign invalid" / "sign expired" / "blocked by argus" —— 这是签名或
   *       uifid 缺失，归为 SIGNATURE_INVALID（可操作提示是检查 uifid / 更新算法）。</li>
   *   <li>风控挑战：body 含 verify_center / captcha / secsdk / byted_acrawler /
   *       slide_verify —— 归为 RISK_CONTROL。</li>
   *   <li>作品不可用：body 含 filter_reason（core_dep=已删除、status_self_see=私密）
   *       或 not exist / not found —— 归为 WORK_NOT_FOUND。</li>
   * </ol>
   * 注意：403 状态码语义过宽（签名拒绝、风控、作品删除都可能 403），
   * 因此不单独依据 403 判定，必须结合 body 内容。
   */
  private static FailReason classifyDetailFailure(int code, String body) {
    String lower = body == null ? "" : body.toLowerCase();

    // 1. 签名 / 参数拒绝（优先级最高，最可操作）
    if (lower.contains("uifid not found")
        || lower.contains("signature not found")
        || lower.contains("sign invalid")
        || lower.contains("sign expired")
        || lower.contains("blocked by argus")
        || lower.contains("invalid signature")) {
      return FailReason.SIGNATURE_INVALID;
    }

    // 2. 风控挑战（验证码 / 滑动验证 / acrawler）
    if (lower.contains("verify_center")
        || lower.contains("verify_page")
        || lower.contains("captcha")
        || lower.contains("secsdk")
        || lower.contains("byted_acrawler")
        || lower.contains("slide_verify")) {
      return FailReason.RISK_CONTROL;
    }

    // 3. 作品不可用（filter_detail 里 filter_reason 或明确 not found）
    if (lower.contains("filter_reason")
        || lower.contains("core_dep")
        || lower.contains("status_self_see")
        || lower.contains("not exist")
        || lower.contains("not found")
        || lower.contains("已删除")
        || lower.contains("private")
        || lower.contains("私密")) {
      return FailReason.WORK_NOT_FOUND;
    }

    // 4. 纯 403/412 且 body 无法识别：更可能是风控拦截（无 body 的裸拒绝）
    if (code == 403 || code == 412 || code == 407 || code == 429) {
      return FailReason.RISK_CONTROL;
    }

    return FailReason.UNKNOWN;
  }

  /** 从两个候选原因中取更具体（更具可操作性）的一个。 */
  private static FailReason pickMoreSpecific(FailReason current, FailReason candidate) {
    if (candidate == null || candidate == FailReason.UNKNOWN) {
      return current;
    }
    if (current == null || current == FailReason.UNKNOWN) {
      return candidate;
    }
    // 取优先级更高者。关键：确定性结论（作品不存在，有 filter_reason 背书）
    // 必须高于模糊信号（403 blocked，可能是风控也可能是备用域名拦截）。
    return priority(candidate) >= priority(current) ? candidate : current;
  }

  private static int priority(FailReason r) {
    switch (r) {
      // 作品不存在：body 有明确的 filter_reason=status_self_see/core_dep，是最确定结论
      case WORK_NOT_FOUND:
        return 6;
      // 签名失效：body 有明确的 sign invalid / uifid not found
      case SIGNATURE_INVALID:
        return 5;
      // Cookie 缺失：请求前校验，确定性高
      case COOKIE_MISSING:
        return 5;
      // 风控：403 blocked 等模糊信号，可能只是备用域名拦截，优先级最低的可操作结论
      case RISK_CONTROL:
        return 4;
      case NETWORK:
        return 2;
      default:
        return 1;
    }
  }

  /** Cookie 关键字段的缺失情况。 */
  private enum CookieIssue {
    OK,
    EMPTY,
    MISSING_SESSIONID,
    MISSING_UIFID,
    MISSING_TTWID,
  }

  /**
   * 判断 Cookie 是否包含解析所需的关键字段（sessionid / uifid / ttwid）。
   * 返回缺失的具体字段，全部齐全返回 {@link CookieIssue#OK}。
   */
  private CookieIssue checkCookieCompleteness(String cookie) {
    if (cookie == null || cookie.trim().isEmpty()) {
      return CookieIssue.EMPTY;
    }
    boolean hasUifid =
        extractCookieValue(cookie, "uifid") != null || extractCookieValue(cookie, "UIFID") != null;
    boolean hasSession =
        extractCookieValue(cookie, "sessionid") != null
            || extractCookieValue(cookie, "sessionid_ss") != null;
    boolean hasTtwid = extractCookieValue(cookie, "ttwid") != null;

    if (!hasSession) {
      return CookieIssue.MISSING_SESSIONID;
    }
    if (!hasUifid) {
      return CookieIssue.MISSING_UIFID;
    }
    if (!hasTtwid) {
      return CookieIssue.MISSING_TTWID;
    }
    return CookieIssue.OK;
  }

  private Optional<JsonNode> parseAwemeNode(String body) {
    if (body == null || body.trim().isEmpty()) {
      return Optional.empty();
    }

    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode itemList = root.path("item_list");
      if (itemList.isArray() && !itemList.isEmpty()) {
        return Optional.of(itemList.get(0));
      }

      JsonNode awemeDetail = root.path("aweme_detail");
      if (awemeDetail.isObject() && !awemeDetail.isEmpty()) {
        return Optional.of(awemeDetail);
      }

      JsonNode nestedAwemeDetail = root.path("data").path("aweme_detail");
      if (nestedAwemeDetail.isObject() && !nestedAwemeDetail.isEmpty()) {
        return Optional.of(nestedAwemeDetail);
      }

      JsonNode awemeList = root.path("aweme_list");
      if (awemeList.isArray() && !awemeList.isEmpty()) {
        return Optional.of(awemeList.get(0));
      }

      return Optional.empty();
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private String buildWebDetailQuery(String awemeId, String cookie) {
    Map<String, String> params = buildCommonWebParams(cookie, "190500", "19.5.0");
    params.put("aweme_id", awemeId);
    return buildSignedQuery("https://www.douyin.com/aweme/v1/web/aweme/detail/", params);
  }

  private String buildWebListQuery(Map<String, String> extraParams, String cookie, String apiUrl) {
    Map<String, String> params = buildCommonWebParams(cookie, "170400", "17.4.0");
    if (extraParams != null) {
      params.putAll(extraParams);
    }
    return buildSignedQuery(apiUrl, params);
  }

  private Map<String, String> buildCommonWebParams(
      String cookie, String versionCode, String versionName) {
    Map<String, String> params = new LinkedHashMap<>();
    String uifid = extractCookieValue(cookie, "UIFID");
    if (uifid == null) uifid = extractCookieValue(cookie, "uifid");
    if (uifid == null) uifid = "";

    String msToken = extractCookieValue(cookie, "msToken");
    if (msToken == null) msToken = "";

    params.put("device_platform", "webapp");
    params.put("aid", "6383");
    params.put("channel", "channel_pc_web");
    params.put("update_version_code", "170400");
    params.put("pc_client_type", "1");
    params.put("pc_libra_divert", "Mac");
    params.put("support_h265", "1");
    params.put("support_dash", "1");
    params.put("version_code", versionCode);
    params.put("version_name", versionName);
    params.put("cookie_enabled", "true");
    params.put("screen_width", "1536");
    params.put("screen_height", "864");
    params.put("browser_language", "zh-CN");
    params.put("browser_platform", "MacIntel");
    params.put("browser_name", "Chrome");
    params.put("browser_version", "146.0.0.0");
    params.put("browser_online", "true");
    params.put("engine_name", "Blink");
    params.put("engine_version", "146.0.0.0");
    params.put("os_name", "Mac OS");
    params.put("os_version", "10.15.7");
    params.put("cpu_core_num", "16");
    params.put("device_memory", "8");
    params.put("platform", "PC");
    params.put("downlink", "10");
    params.put("effective_type", "4g");
    params.put("round_trip_time", "200");
    params.put("uifid", uifid);
    params.put("msToken", msToken);
    return params;
  }

  /**
   * 构造最终签名的 query：a_bogus 始终附加；受保护接口且 query 含 uifid 时，
   * 先按 WebSign 规范化 query 再计算 a_bogus，最后追加 timestamp 与
   * x-secsdk-web-signature。
   */
  private String buildSignedQuery(String apiUrl, Map<String, String> params) {
    String query = encodeQueryParams(params);
    String uifid = isSignProtected(apiUrl) ? extractQueryValue(query, "uifid") : "";
    // 受保护接口且携带 uifid 时才走 WebSign
    boolean webSign = !uifid.isEmpty();
    if (webSign) {
      query = WebSignGenerator.normalizeQuery(query);
    }
    String aBogus = aBogusGenerator.getValue(query);
    // s4 字母表包含 "/" 与 "=" 填充，发送前需百分号编码
    String signedQuery = query + "&a_bogus=" + WebSignGenerator.quote(aBogus, "");
    if (webSign) {
      signedQuery = WebSignGenerator.sign(signedQuery, uifid, System.currentTimeMillis() / 1000L);
    }
    return signedQuery;
  }

  private static boolean isSignProtected(String apiUrl) {
    String path;
    try {
      path = URI.create(apiUrl).getPath();
    } catch (Exception e) {
      return false;
    }
    if (path == null) {
      path = "";
    }
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    if (!path.endsWith("/")) {
      path += "/";
    }
    for (String candidate : SIGNED_PATHS) {
      if (path.equals(candidate)) {
        return true;
      }
    }
    return false;
  }

  /** 从已编码的 query 中取值并解码（unquote 而非 unquote_plus）。 */
  private static String extractQueryValue(String query, String name) {
    for (String part : query.split("&")) {
      if (part.isEmpty()) {
        continue;
      }
      int separator = part.indexOf('=');
      String key = separator >= 0 ? part.substring(0, separator) : part;
      if (name.equals(WebSignGenerator.unquote(key))) {
        String value = separator >= 0 ? part.substring(separator + 1) : "";
        return WebSignGenerator.unquote(value);
      }
    }
    return "";
  }

  private String encodeQueryParams(Map<String, String> params) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, String> entry : params.entrySet()) {
      if (!first) {
        sb.append("&");
      }
      first = false;
      sb.append(quotePlus(entry.getKey())).append("=").append(quotePlus(entry.getValue()));
    }
    return sb.toString();
  }

  private static String quotePlus(String value) {
    String input = value == null ? "" : value;
    StringBuilder builder = new StringBuilder(input.length());
    byte[] encoded = input.getBytes(StandardCharsets.UTF_8);
    for (byte item : encoded) {
      int unsigned = item & 0xFF;
      if ((unsigned >= 'a' && unsigned <= 'z')
          || (unsigned >= 'A' && unsigned <= 'Z')
          || (unsigned >= '0' && unsigned <= '9')
          || unsigned == '_'
          || unsigned == '.'
          || unsigned == '-'
          || unsigned == '~') {
        builder.append((char) unsigned);
      } else if (unsigned == ' ') {
        builder.append('+');
      } else {
        builder.append('%');
        builder.append(Character.toUpperCase(Character.forDigit((unsigned >> 4) & 0xF, 16)));
        builder.append(Character.toUpperCase(Character.forDigit(unsigned & 0xF, 16)));
      }
    }
    return builder.toString();
  }

  private String extractCookieValue(String cookie, String name) {
    String normalizedCookie = normalizeCookie(cookie);
    if (normalizedCookie.trim().isEmpty()) {
      return null;
    }

    Pattern pattern = Pattern.compile("(?:^|;\\s*)" + Pattern.quote(name) + "=([^;]*)");
    Matcher matcher = pattern.matcher(normalizedCookie);
    if (matcher.find()) {
      String val = matcher.group(1);
      return val != null ? val.trim() : null;
    }
    return null;
  }

  private Optional<String> extractSecUserId(String text) {
    if (text == null || text.trim().isEmpty()) {
      return Optional.empty();
    }

    String trimmed = text.trim();
    if (trimmed.matches("MS4w[0-9A-Za-z._-]+")) {
      return Optional.of(trimmed);
    }

    Matcher pathMatcher = SEC_USER_ID_URL_PATTERN.matcher(trimmed);
    if (pathMatcher.find()) {
      String candidate = decodeUrlValue(pathMatcher.group(1));
      if (candidate != null) {
        candidate = candidate.trim();
        if (candidate.matches("MS4w[0-9A-Za-z._-]+")) {
          return Optional.of(candidate);
        }
      }
    }

    Matcher queryMatcher = SEC_USER_ID_QUERY_PATTERN.matcher(trimmed);
    if (queryMatcher.find()) {
      String candidate = decodeUrlValue(queryMatcher.group(1));
      if (candidate != null) {
        candidate = candidate.trim();
        if (candidate.matches("MS4w[0-9A-Za-z._-]+")) {
          return Optional.of(candidate);
        }
      }
    }

    Matcher jsonMatcher = SEC_USER_ID_JSON_PATTERN.matcher(trimmed);
    if (jsonMatcher.find()) {
      String candidate = decodeUrlValue(jsonMatcher.group(1));
      if (candidate != null) {
        candidate = candidate.trim();
        if (candidate.matches("MS4w[0-9A-Za-z._-]+")) {
          return Optional.of(candidate);
        }
      }
    }

    return Optional.empty();
  }

  private Optional<String> extractMixId(String text) {
    if (text == null || text.trim().isEmpty()) {
      return Optional.empty();
    }

    Matcher pathMatcher = MIX_ID_URL_PATTERN.matcher(text);
    if (pathMatcher.find()) {
      return Optional.ofNullable(pathMatcher.group(1));
    }

    Matcher queryMatcher = MIX_ID_QUERY_PATTERN.matcher(text);
    if (queryMatcher.find()) {
      return Optional.ofNullable(queryMatcher.group(1));
    }

    Matcher jsonMatcher = MIX_ID_JSON_PATTERN.matcher(text);
    if (jsonMatcher.find()) {
      return Optional.ofNullable(jsonMatcher.group(1));
    }

    return Optional.empty();
  }

  private Optional<String> fetchSecUserIdFromPage(String url, String cookie) {
    Optional<String> page = fetchPageContent(url, cookie);
    if (page.isEmpty()) {
      return Optional.empty();
    }
    return extractSecUserIdFromPage(page.get());
  }

  private Optional<String> extractSecUserIdFromPage(String html) {
    if (html == null || html.trim().isEmpty()) {
      return Optional.empty();
    }

    // Prefer URL-shaped occurrences; avoid JSON "secUid" which may be the logged-in user.
    Matcher pathMatcher = SEC_USER_ID_URL_PATTERN.matcher(html);
    while (pathMatcher.find()) {
      String candidate = decodeUrlValue(pathMatcher.group(1));
      if (candidate != null) {
        candidate = candidate.trim();
        if (candidate.startsWith("MS4w")) {
          return Optional.of(candidate);
        }
      }
    }

    Matcher queryMatcher = SEC_USER_ID_QUERY_PATTERN.matcher(html);
    while (queryMatcher.find()) {
      String candidate = decodeUrlValue(queryMatcher.group(1));
      if (candidate != null) {
        candidate = candidate.trim();
        if (candidate.startsWith("MS4w")) {
          return Optional.of(candidate);
        }
      }
    }

    return Optional.empty();
  }

  private Optional<String> fetchMixIdFromPage(String url, String cookie) {
    Optional<String> page = fetchPageContent(url, cookie);
    if (page.isEmpty()) {
      return Optional.empty();
    }
    return extractMixId(page.get());
  }

  private Optional<String> fetchPageContent(String url, String cookie) {
    try {
      Request request =
          requestBuilder(url, cookie)
              .get()
              .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
              .build();
      try (Response response = httpClient.newCall(request).execute()) {
        ResponseBody bodyObj = response.body();
        String body = bodyObj != null ? bodyObj.string() : "";
        if (body.trim().isEmpty()) {
          return Optional.empty();
        }
        return Optional.of(body);
      }
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private boolean isImagePost(JsonNode aweme) {
    JsonNode images = aweme.path("images");
    if (images.isArray() && !images.isEmpty()) {
      return true;
    }
    JsonNode imagePostImages = aweme.path("image_post_info").path("images");
    return imagePostImages.isArray() && !imagePostImages.isEmpty();
  }

  private List<String> collectImageUrls(JsonNode aweme) {
    return collectImageAssets(aweme).downloadUrls;
  }

  private ImageAssetCollection collectImageAssets(JsonNode aweme) {
    List<ImageAsset> assets = new ArrayList<>();

    JsonNode images = aweme.path("images");
    if (images.isArray() && !images.isEmpty()) {
      appendImageAssets(assets, images);
    }

    JsonNode imagePostImages = aweme.path("image_post_info").path("images");
    if (assets.isEmpty() && imagePostImages.isArray() && !imagePostImages.isEmpty()) {
      appendImageAssets(assets, imagePostImages);
    }

    return ImageAssetCollection.from(assets);
  }

  private void appendImageAssets(List<ImageAsset> assets, JsonNode images) {
    if (assets == null || !images.isArray()) {
      return;
    }
    for (JsonNode image : images) {
      if (image == null || image.isNull()) {
        continue;
      }
      // 判断是否 LivePhoto：有 video 字段且非空
      JsonNode videoNode = image.path("video");
      boolean isLivePhoto = videoNode.isObject() && !videoNode.isNull()
              && (videoNode.path("play_addr").path("url_list").isArray()
                  || videoNode.path("download_addr").path("url_list").isArray());

      // 静态封面图：优先 display_image
      Optional<String> staticUrl = pickLastUrl(image.path("display_image").path("url_list"));
      if (staticUrl.isEmpty()) {
        staticUrl = pickImageDownloadUrl(image);
      }

      if (isLivePhoto) {
        // LivePhoto：静态封面 + 动态视频分开存
        Optional<String> videoUrl = pickLastUrl(videoNode.path("play_addr").path("url_list"));
        if (videoUrl.isEmpty()) {
          videoUrl = pickLastUrl(videoNode.path("play_addr_h264").path("url_list"));
        }
        if (videoUrl.isEmpty()) {
          videoUrl = pickLastUrl(videoNode.path("download_addr").path("url_list"));
        }
        if (staticUrl.isPresent()) {
          assets.add(new ImageAsset(staticUrl.get(), staticUrl.get(), MediaType.IMAGE));
        }
        if (videoUrl.isPresent()) {
          assets.add(new ImageAsset(staticUrl.orElse(""), videoUrl.get(), MediaType.VIDEO));
        }
      } else {
        // 普通静态图
        if (staticUrl.isPresent()) {
          assets.add(new ImageAsset(staticUrl.get(), staticUrl.get(), MediaType.IMAGE));
        }
      }
    }
  }

  private Optional<String> pickImageDownloadUrl(JsonNode image) {
    Optional<String> url = pickLastUrl(image.path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    url = pickLastUrl(image.path("display_image").path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    url = pickLastUrl(image.path("owner_watermark_image").path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    return pickLastUrl(image.path("download_url_list"));
  }

  private Optional<String> pickLivePhotoVideoUrl(JsonNode image) {
    Optional<String> url = pickLastUrl(image.path("video").path("play_addr").path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    url = pickLastUrl(image.path("video").path("play_addr_h264").path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    url = pickLastUrl(image.path("video").path("download_addr").path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    url = pickLastUrl(image.path("live_photo").path("video").path("play_addr").path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    url =
        pickLastUrl(image.path("live_photo").path("video").path("download_addr").path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    url = pickLastUrl(image.path("motion_photo").path("video").path("play_addr").path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    url =
        pickLastUrl(
            image.path("motion_photo").path("video").path("download_addr").path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    return pickLastUrl(image.path("video").path("url_list"));
  }

  private String extractAvatarUrl(JsonNode author) {
    if (!author.isObject()) return "";

    // 优先取高清头像（横版大图，用于 PC 端展示）
    Optional<String> avatarLarger = pickLastUrl(author.path("avatar_larger").path("url_list"));
    if (avatarLarger.isPresent() && !avatarLarger.get().isEmpty()) {
      return avatarLarger.get();
    }

    // 其次取中等头像
    Optional<String> avatarMedium = pickLastUrl(author.path("avatar_medium").path("url_list"));
    if (avatarMedium.isPresent() && !avatarMedium.get().isEmpty()) {
      return avatarMedium.get();
    }

    // 再其次取默认头像
    Optional<String> avatarThumb = pickLastUrl(author.path("avatar_thumb").path("url_list"));
    if (avatarThumb.isPresent() && !avatarThumb.get().isEmpty()) {
      return avatarThumb.get();
    }

    // 兜底：直接取 url_list（部分接口直接把头像放这里）
    Optional<String> directUrl = pickLastUrl(author.path("url_list"));
    return directUrl.orElse("");
  }

  private List<String> collectThumbnailUrls(JsonNode aweme) {
    LinkedHashSet<String> urls = new LinkedHashSet<>();

    if (isImagePost(aweme)) {
      appendImageThumbnailUrls(urls, aweme.path("images"));
      appendImageThumbnailUrls(urls, aweme.path("image_post_info").path("images"));
      if (!urls.isEmpty()) {
        return List.copyOf(urls);
      }
    }

    appendUrlListCandidates(urls, aweme.path("video").path("cover").path("url_list"));
    appendUrlListCandidates(urls, aweme.path("video").path("origin_cover").path("url_list"));
    appendUrlListCandidates(urls, aweme.path("video").path("dynamic_cover").path("url_list"));

    return List.copyOf(urls);
  }

  private void appendImageThumbnailUrls(LinkedHashSet<String> urls, JsonNode images) {
    if (!images.isArray()) {
      return;
    }
    for (JsonNode image : images) {
      Optional<String> url = pickImageThumbnailUrl(image);
      url.ifPresent(urls::add);
    }
  }

  private Optional<String> pickImageThumbnailUrl(JsonNode image) {
    Optional<String> url = pickLastUrl(image.path("display_image").path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    url = pickLastUrl(image.path("owner_watermark_image").path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    url = pickLastUrl(image.path("url_list"));
    if (url.isPresent()) {
      return url;
    }
    return pickLastUrl(image.path("download_url_list"));
  }

  private List<String> collectVideoUrlCandidates(JsonNode aweme) {
    LinkedHashSet<String> urls = new LinkedHashSet<>();
    LinkedHashSet<String> playUris = new LinkedHashSet<>();

    for (VideoCandidate candidate : collectVideoCandidates(aweme)) {
      urls.add(candidate.url);
      if (!candidate.playUri.isEmpty()) {
        playUris.add(candidate.playUri);
      }
    }

    appendUrlListCandidates(urls, aweme.path("video").path("play_addr").path("url_list"));
    appendUrlListCandidates(urls, aweme.path("video").path("play_addr_h264").path("url_list"));
    appendUrlListCandidates(urls, aweme.path("video").path("download_addr").path("url_list"));

    playUris.add(aweme.path("video").path("play_addr").path("uri").asText("").trim());
    playUris.add(aweme.path("video").path("play_addr_h264").path("uri").asText("").trim());
    for (String playUri : playUris) {
      appendPlayApiCandidates(urls, playUri);
    }

    return new ArrayList<>(urls);
  }

  /**
   * 收集 {@code video.bit_rate[]} 的可下载候选，并按画质排序：
   * 分辨率 → 帧率 → 码率 → 体积（与快手 {@link com.xianyu.vediodownload.kuaishou.KuaishouDownloader}
   * 的排序规则保持一致）。
   *
   * <p>抽成独立方法是为了让「候选链」和「清晰度标注」共用同一套筛选与排序逻辑，
   * 避免两处规则漂移导致「显示 1080p 却下了 720p」这类不一致。
   */
  private List<VideoCandidate> collectVideoCandidates(JsonNode aweme) {
    List<VideoCandidate> candidates = new ArrayList<>();
    JsonNode bitRates = aweme.path("video").path("bit_rate");
    if (bitRates.isArray()) {
      for (JsonNode bitRate : bitRates) {
        JsonNode playAddr = bitRate.path("play_addr");
        Optional<String> url = pickLastUrl(playAddr.path("url_list"));
        if (url.isEmpty()) {
          continue;
        }
        candidates.add(
            new VideoCandidate(
                playAddr.path("height").asInt(-1),
                playAddr.path("width").asInt(-1),
                bitRate.path("FPS").asInt(-1),
                bitRate.path("bit_rate").asInt(-1),
                playAddr.path("data_size").asLong(-1),
                url.get(),
                playAddr.path("uri").asText("").trim()));
      }
    }
    candidates.sort(VIDEO_CANDIDATE_ORDER);
    return candidates;
  }

  /**
   * 生成清晰度标注，如 {@code "1080p · 60fps"}。
   *
   * <p>分辨率取「短边」命名（1080×1920 记作 1080p），与业界惯例及快手侧质量档命名一致。
   * 无任何尺寸信息时返回空串，不做猜测。
   */
  private String buildVideoQualityLabel(JsonNode aweme) {
    List<VideoCandidate> candidates = collectVideoCandidates(aweme);
    int width;
    int height;
    int fps;
    if (!candidates.isEmpty()) {
      VideoCandidate best = candidates.get(0);
      width = best.width;
      height = best.height;
      fps = best.fps;
    } else {
      JsonNode playAddr = aweme.path("video").path("play_addr");
      width = playAddr.path("width").asInt(0);
      height = playAddr.path("height").asInt(0);
      fps = 0;
    }
    int shortSide = Math.min(width, height);
    if (shortSide <= 0) {
      // bit_rate 里偶发缺失尺寸字段，回落到 video.play_addr / video 顶层尺寸
      JsonNode video = aweme.path("video");
      shortSide =
          Math.min(video.path("play_addr").path("width").asInt(0),
              video.path("play_addr").path("height").asInt(0));
      if (shortSide <= 0) {
        shortSide = Math.min(video.path("width").asInt(0), video.path("height").asInt(0));
      }
    }
    if (shortSide <= 0) {
      return fps > 0 ? (fps + "fps") : "";
    }
    String resolution = shortSide + "p";
    return fps > 0 ? (resolution + " · " + fps + "fps") : resolution;
  }

  private static final java.util.Comparator<VideoCandidate> VIDEO_CANDIDATE_ORDER =
      (a, b) -> {
        int cmp = Integer.compare(Math.max(b.height, b.width), Math.max(a.height, a.width));
        if (cmp != 0) {
          return cmp;
        }
        cmp = Integer.compare(b.fps, a.fps);
        if (cmp != 0) {
          return cmp;
        }
        cmp = Integer.compare(b.bitRate, a.bitRate);
        if (cmp != 0) {
          return cmp;
        }
        return Long.compare(b.dataSize, a.dataSize);
      };

  private void appendPlayApiCandidates(LinkedHashSet<String> urls, String playUri) {
    if (playUri == null || playUri.trim().isEmpty()) {
      return;
    }
    String encodedUri = urlEncode(playUri);
    urls.add(
        "https://aweme.snssdk.com/aweme/v1/play/?video_id=" + encodedUri + "&line=0&ratio=1080p");
    urls.add(
        "https://aweme.snssdk.com/aweme/v1/play/?video_id=" + encodedUri + "&line=0&ratio=720p");
    urls.add("https://aweme.snssdk.com/aweme/v1/play/?video_id=" + encodedUri + "&line=0");
  }

  private void appendUrlListCandidates(LinkedHashSet<String> urls, JsonNode urlList) {
    if (!urlList.isArray() || urlList.isEmpty()) {
      return;
    }
    for (int i = urlList.size() - 1; i >= 0; i--) {
      String url = urlList.get(i).asText("").trim();
      if (!url.isEmpty()) {
        urls.add(url);
      }
    }
  }

  private Request.Builder requestBuilder(String url, String cookie) {
    Request.Builder builder =
        new Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Referer", DEFAULT_REFERER)
            .header("Accept-Encoding", "*/*");

    String normalizedCookie = normalizeCookie(cookie);
    if (!normalizedCookie.trim().isEmpty() && shouldAttachCookie(url)) {
      builder.header("Cookie", normalizedCookie);
    }
    return builder;
  }

  private String normalizeCookie(String cookie) {
    if (cookie == null) {
      return "";
    }
    return cookie.trim();
  }

  private String shortBody(String body) {
    if (body == null || body.trim().isEmpty()) {
      return "<empty>";
    }
    String compact = body.replaceAll("\\s+", " ").trim();
    int limit = 180;
    return compact.length() <= limit ? compact : compact.substring(0, limit) + "...";
  }

  private String urlEncode(String value) {
    try {
      return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name())
          .replace("+", "%20");
    } catch (Exception e) {
      return value == null ? "" : value;
    }
  }

  private String decodeUrlValue(String value) {
    if (value == null || value.trim().isEmpty()) {
      return "";
    }
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    } catch (Exception ignored) {
      return value;
    }
  }

  private boolean parseHasMore(JsonNode hasMoreNode) {
    if (hasMoreNode == null || hasMoreNode.isMissingNode() || hasMoreNode.isNull()) {
      return false;
    }
    if (hasMoreNode.isBoolean()) {
      return hasMoreNode.asBoolean();
    }
    if (hasMoreNode.isNumber()) {
      return hasMoreNode.asInt(0) != 0;
    }
    String text = hasMoreNode.asText("").trim();
    return "1".equals(text) || "true".equalsIgnoreCase(text);
  }

  private void notifyProgress(
      DownloadProgressCallback progressCallback, DownloadProgress progress) {
    if (progressCallback == null) {
      return;
    }
    try {
      progressCallback.onProgress(progress);
    } catch (RuntimeException ignored) {
      // Ignore callback exception to avoid interrupting download process.
    }
  }

  private Optional<String> pickLastUrl(JsonNode urlList) {
    if (!urlList.isArray() || urlList.isEmpty()) {
      return Optional.empty();
    }
    for (int i = urlList.size() - 1; i >= 0; i--) {
      String url = urlList.get(i).asText("").trim();
      if (!url.isEmpty()) {
        return Optional.of(url);
      }
    }
    return Optional.empty();
  }

  private Optional<String> extractAwemeId(String text) {
    if (text == null || text.trim().isEmpty()) {
      return Optional.empty();
    }
    Matcher matcher = AWEME_ID_PATTERN.matcher(text);
    if (matcher.find()) {
      return Optional.ofNullable(matcher.group(1));
    }
    return Optional.empty();
  }

  private static final class ResolvedShareLink {
    final String normalizedCookie;
    final String resolvedUrl;
    /** 原始（未重定向）地址，用于二次尝试。 */
    final String inputUrl;
    /** 最后一次响应状态码，0 表示未拿到响应。 */
    final int status;
    /** 重定向后页面正文（仅 HTML，最多 200KB），用于从落地页里兜底提取作品 ID。 */
    final String body;

    ResolvedShareLink(
        String normalizedCookie, String resolvedUrl, String inputUrl, int status, String body) {
      this.normalizedCookie = normalizedCookie;
      this.resolvedUrl = resolvedUrl;
      this.inputUrl = inputUrl == null ? "" : inputUrl;
      this.status = status;
      this.body = body == null ? "" : body;
    }
  }

  /** 一次短链探测的结果：最终地址 + 状态码 + 正文片段。 */
  private static final class HttpProbe {
    final String finalUrl;
    final int status;
    final String body;

    HttpProbe(String finalUrl, int status, String body) {
      this.finalUrl = finalUrl;
      this.status = status;
      this.body = body == null ? "" : body;
    }
  }

  private static final class WorkListContext {
    final String normalizedCookie;
    final List<JsonNode> awemeList;
    WorkListContext(String normalizedCookie, List<JsonNode> awemeList) {
      this.normalizedCookie = normalizedCookie;
      this.awemeList = awemeList;
    }
  }

  private static final class SingleAwemeContext {
    final String normalizedCookie;
    final String awemeId;
    final JsonNode aweme;
    SingleAwemeContext(String normalizedCookie, String awemeId, JsonNode aweme) {
      this.normalizedCookie = normalizedCookie;
      this.awemeId = awemeId;
      this.aweme = aweme;
    }
  }

  private static final class ImageAsset {
    final String thumbnailUrl;
    final String downloadUrl;
    final MediaType mediaType;
    ImageAsset(String thumbnailUrl, String downloadUrl, MediaType mediaType) {
      this.thumbnailUrl = thumbnailUrl;
      this.downloadUrl = downloadUrl;
      this.mediaType = mediaType;
    }
  }

  private static final class ImageAssetCollection {
    final List<String> thumbnailUrls;
    final List<String> downloadUrls;
    final List<MediaType> mediaTypes;
    ImageAssetCollection(List<String> thumbnailUrls, List<String> downloadUrls, List<MediaType> mediaTypes) {
      this.thumbnailUrls = thumbnailUrls;
      this.downloadUrls = downloadUrls;
      this.mediaTypes = mediaTypes;
    }
    static ImageAssetCollection from(List<ImageAsset> assets) {
      if (assets == null || assets.isEmpty()) {
        return new ImageAssetCollection(List.of(), List.of(), List.of());
      }
      List<String> thumbnails = new ArrayList<>(assets.size());
      List<String> downloads = new ArrayList<>(assets.size());
      List<MediaType> types = new ArrayList<>(assets.size());
      for (ImageAsset asset : assets) {
        if (asset == null) {
          continue;
        }
        String downloadUrl = asset.downloadUrl == null ? "" : asset.downloadUrl.trim();
        if (downloadUrl.isEmpty()) {
          continue;
        }
        String thumbnailUrl = asset.thumbnailUrl == null ? "" : asset.thumbnailUrl.trim();
        thumbnails.add(thumbnailUrl);
        downloads.add(downloadUrl);
        types.add(asset.mediaType == null ? MediaType.IMAGE : asset.mediaType);
      }
      return new ImageAssetCollection(
          Collections.unmodifiableList(thumbnails),
          Collections.unmodifiableList(downloads),
          Collections.unmodifiableList(types));
    }
  }

  private static final class VideoCandidate {
    final int height;
    final int width;
    final int fps;
    final int bitRate;
    final long dataSize;
    final String url;
    final String playUri;
    VideoCandidate(
        int height, int width, int fps, int bitRate, long dataSize, String url, String playUri) {
      this.height = height;
      this.width = width;
      this.fps = fps;
      this.bitRate = bitRate;
      this.dataSize = dataSize;
      this.url = url;
      this.playUri = playUri == null ? "" : playUri;
    }
  }
}
