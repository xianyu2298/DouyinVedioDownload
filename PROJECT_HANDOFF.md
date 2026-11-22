# 抖净-DouyinDownload 项目交接文档

> **面向读者**：下一个接手本项目的 AI / 开发者
> **文档目标**：从零理解本项目的完整架构、每个功能的实现原理、关键算法细节，以及后续维护要点
> **最后更新**：2026-11-22

---

## 0. 快速接手须知（先读这里）

### 0.1 项目现状

解析、下载、Live 合成、预览、历史、缓存等功能均可用。签名算法已升级至 **bdms.js v1.0.1.19-fix.01**（192 字符 a_bogus + x-secsdk-web-signature），2026-11-22 验证原作者 v0.0.5 未改动签名算法。

### 0.2 与本项目用户协作的三条铁律

1. **改代码前必须先给方案，等用户明确说「改吧 / 可以」后再生效**。用户问「怎么改」时只需给方案，不要落盘。
2. **通用功能必须覆盖所有模块**。例如改输入框要检查 Video / Image / Live / Author 四个页面；交付时明确说明改了哪几个模块、哪些不需要及原因。
3. **不要猜**。诊断问题必须基于真实 Logcat 日志或代码事实。需要真实数据时，直接请用户提供 `DouyinDownloader` 标签的 `BODY=` 日志。

### 0.3 最容易踩的坑

| 坑 | 说明 |
|----|------|
| 报错判定优先级 | 「作品不存在」必须高于「风控」。私密作品案例：端点2 返 `status_self_see`、端点3 返 `403 blocked`，若风控优先会误报。见 `FailReason` 优先级表。 |
| msToken 不是必需 | 它是动态 token，正常登录 Cookie 里本来就没有。Cookie 必需字段只有 `sessionid` + `uifid` + `ttwid`。 |
| URL 正则不能用黑名单 | 必须用 RFC 3986 白名单，否则汉字 / emoji / 全角 / 零宽字符会被吞进 URL，误报「链接格式不正确」。 |
| 签名 UA 必须一致 | `DEFAULT_USER_AGENT`、`buildCommonWebParams` 的 browser_version、`ABogusGenerator` 的 UA 三者必须完全一致，否则签名校验失败。 |

### 0.4 签名失效时的处置

症状：HTTP 403 / `invalid signature` / `uifid not found` / body 为 `blocked`。

处置：去 halcyra/DYDownloader 仓库看最新 commit 是否含 `upgrade signing` / `a_bogus` 字样，若有则移植 `ABogusGenerator.java` + `WebSignGenerator.java` + `CustomBase64.java` 到本项目（注意改包名）。

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术栈与编译环境](#2-技术栈与编译环境)
3. [整体架构](#3-整体架构)
4. [项目目录结构](#4-项目目录结构)
5. [核心流程：解析 → 下载](#5-核心流程解析--下载)
6. [a_bogus 签名算法（最重要）](#6-a_bogus-签名算法最重要)
7. [WebSign 第二道签名](#7-websign-第二道签名)
8. [Cookie 管理与会话](#8-cookie-管理与会话)
9. [下载与存储（MediaStore）](#9-下载与存储mediastore)
10. [Live 实况图合成](#10-live-实况图合成)
11. [UI 层与设计系统](#11-ui-层与设计系统)
12. [视频 / 图片 / Live 预览](#12-视频--图片--live-预览)
13. [作者主页与批量下载](#13-作者主页与批量下载)
14. [下载历史与缓存管理](#14-下载历史与缓存管理)
15. [已知问题与维护要点](#15-已知问题与维护要点)
16. [后续迭代建议](#16-后续迭代建议)
17. [变更记录](#17-变更记录)

---

## 1. 项目概述

**抖净-DouyinDownload** 是一个抖音无水印下载 Android 原生应用，功能包括：

| 功能 | 说明 |
|------|------|
| 视频下载 | 解析抖音分享链接，下载无水印最高码率视频 |
| 图片作品下载 | 下载图文作品的整套原图 |
| Live 实况图下载 | 下载静态封面 + 动态短片 |
| Live 实况图合成 | 将封面 + 短片合成为 Google Motion Photo（.MP.JPG） |
| 作者主页下载 | 解析作者全部作品并批量下载 |
| 批量下载 | 前台服务 + 进度通知 |
| 下载历史 | 查看已下载的视频 / 图片 / Live 三类文件 |

**技术特征**：纯 API 方案，无 WebView 注入、无网络拦截。所有解析逻辑在 Java/Kotlin 本地完成。

**项目渊源**：参考 [halcyra/DYDownloader](https://github.com/halcyra/DYDownloader)（AGPL-3.0）的解析算法，包名 `com.xianyu.vediodownload`。

---

## 2. 技术栈与编译环境

| 项 | 值 |
|----|----|
| 语言 | Java（协议层）+ Kotlin（UI 层） |
| UI 框架 | Jetpack Compose（BOM 2023.10.01，Compiler 1.5.1） |
| 编译 SDK | 34 |
| 最小 SDK | 24（Android 7.0） |
| 目标 SDK | 34 |
| JDK | 17 |
| AGP | 8.1.0（根 build.gradle）/ 8.8.0（libs.versions.toml） |
| 网络 | OkHttp 4.12.0 |
| JSON | Jackson 2.15.2 + Gson 2.10.1 |
| 图片加载 | Coil 2.5.0 |
| 视频播放 | Media3 / ExoPlayer 1.2.1 |
| 视频处理 | FFmpeg Kit（ffmpeg-kit-full-gpl AAR）+ Android MediaCodec |
| Exif 读取 | androidx.exifinterface 1.3.7 |

**版本信息**：`versionCode 4`，`versionName 1.2`。

---

## 3. 整体架构

```
┌─────────────────────────────────────────────────────┐
│                     UI 层（Kotlin）                    │
│  MainActivity → MainScreen → HorizontalPager          │
│   ├── VideoScreen    视频解析下载页                     │
│   ├── ImageScreen    图片作品页                        │
│   ├── LiveScreen     Live 实况图页                     │
│   ├── AuthorScreen   作者主页页                        │
│   └── SettingsScreen 设置页（含历史/合成/清缓存）        │
│         └── designsystem/ 新拟态+玻璃态设计系统          │
│         └── player/      三个预览 Activity              │
└───────────────────────┬─────────────────────────────┘
                        │ ViewModel（DownloadViewModel / AuthorViewModel）
                        ▼
┌─────────────────────────────────────────────────────┐
│                   协议层（Java）                       │
│  DouyinDownloader.java   解析主入口、3 端点 fallback    │
│  ABogusGenerator.java    a_bogus 签名（新版）          │
│  WebSignGenerator.java   x-secsdk-web-signature 签名  │
│  AwemeProfile.java       解析结果数据类                 │
└───────────────────────┬─────────────────────────────┘
                        ▼
┌─────────────────────────────────────────────────────┐
│                   工具层（Java/Kotlin）                │
│  DownloaderBridge.java   OkHttp 下载 + MediaStore 写入 │
│  CookieManager.java      SharedPreferences 存 Cookie   │
│  LivePhotoMaker.kt       Motion Photo 合成             │
│  FFmpegUtils.kt          FFmpeg 抽帧                   │
│  MediaCodecMp4Encoder.kt MP4 remux                    │
│  CacheManager.kt         缓存/下载统计与清理             │
└─────────────────────────────────────────────────────┘
```

**关键设计原则**：
- **单 Activity + Compose**：`MainActivity` 是唯一入口，5 个 Tab 用 `HorizontalPager` 横向滑动。
- **ViewModel 隔离**：每个 Tab 有独立 ViewModel（通过 key 区分），避免状态串扰。
- **协议层纯 Java**：不依赖 Android 框架，理论上可移植。

---

## 4. 项目目录结构

```
app/src/main/java/com/xianyu/vediodownload/
├── douyin/                          # 抖音协议层（Java）
│   ├── DouyinDownloader.java        # 解析主入口、3 端点 fallback、签名链组装
│   ├── ABogusGenerator.java         # a_bogus 签名（SM3+RC4+自定义Base64）
│   ├── WebSignGenerator.java        # x-secsdk-web-signature 签名（MD5 链）
│   ├── AwemeProfile.java            # 解析结果数据类
│   ├── MediaType.java               # 媒体类型枚举（VIDEO/IMAGE/LIVE_VIDEO）
│   ├── DownloadProgress.java        # 进度对象
│   ├── DownloadProgressCallback.java
│   └── exception/                   # 5 个自定义异常
├── model/
│   └── Platform.java                # 平台枚举（DOUYIN）
├── downloader/
│   ├── HttpDownloader.java          # 底层 HTTP 下载
│   └── PlatformRequestContext.java
├── util/
│   ├── DownloaderBridge.java        # 下载桥接（URL→MediaStore）
│   ├── CookieManager.java           # Cookie 存储
│   ├── CustomBase64.java            # 自定义字母表 Base64（签名用）
│   ├── LivePhotoMaker.kt            # Motion Photo 合成
│   ├── FFmpegUtils.kt               # FFmpeg 抽帧
│   ├── MediaCodecMp4Encoder.kt      # MP4 remux
│   ├── CacheManager.kt              # 缓存统计/清理
│   ├── ShareLinkUtils.kt            # 分享链接粗筛
│   └── ForegroundDownloadNotifier.java  # 前台下载通知
├── ui/
│   ├── screens/                     # Compose 页面
│   │   ├── MainScreen.kt            # 主框架 + TabBar
│   │   ├── VideoScreen.kt           # 视频页
│   │   ├── ImageScreen.kt           # 图片页
│   │   ├── LiveScreen.kt            # Live 页
│   │   ├── AuthorScreen.kt          # 作者页
│   │   ├── SettingsScreen.kt        # 设置页（含子路由栈）
│   │   ├── HistoryScreens.kt        # 历史记录
│   │   ├── DownloadViewModel.kt     # 视频/图片/Live 共用 ViewModel
│   │   ├── AuthorViewModel.kt       # 作者页 ViewModel
│   │   ├── LivePhotoComposeScreen.kt / ViewModel.kt  # 手动合成页
│   │   ├── DisclaimerDialog.kt      # 免责声明
│   │   ├── BatchDownloadConfirmDialog.kt
│   │   └── BatchDownloadProgressDialog.kt
│   ├── designsystem/                # 设计系统
│   │   ├── theme/                   # 主题 token（Color/Type/Spacing/GlassTokens）
│   │   └── component/               # GlassCard/NeumorphicButton 等
│   └── player/
│       ├── VideoPlayerActivity.kt   # 视频预览（ExoPlayer）
│       ├── ImagePreviewActivity.kt  # 图片预览（Coil）
│       └── LivePhotoPreviewActivity.kt  # Live 预览
├── MainActivity.kt                  # 入口 Activity
└── DouyinLoginActivity.java         # WebView 登录抓 Cookie
```

---

## 5. 核心流程：解析 → 下载

### 5.1 解析单个作品（`collectWorkInfo`）

```
用户粘贴分享链接
   ↓
resolveShareLink() 提取 URL + 重定向解析最终地址
   ↓
extractAwemeId() 提取 19 位作品 ID
   ↓
fetchAwemeDetail() 依次尝试 3 个端点
   ↓
parseAwemeNode() 解析 JSON 得到 aweme_detail
   ↓
collectAwemeProfileFromNode() 构造 AwemeProfile
```

### 5.2 三个端点的 fallback 顺序

`fetchAwemeDetail()` 里的候选端点列表：

```java
List<String> candidates = Arrays.asList(
    // 1. 公开端点（免登录，但仍需 Cookie）
    "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=" + awemeId,
    // 2. 主接口（需完整签名链 a_bogus + msToken + uifid）
    "https://www.douyin.com/aweme/v1/web/aweme/detail/?" + detailQuery,
    // 3. 备用域名
    "https://www.iesdouyin.com/aweme/v1/web/aweme/detail/?" + detailQuery
);
```

每个端点请求后调用 `parseAwemeNode(body)` 解析，命中即返回，全部失败抛 `AwemeDetailFetchException`。

### 5.3 解析结果抽取（按作品类型）

在 `collectAwemeProfileFromNode()` 中：

- **视频**：`video.bit_rate[].play_addr.url_list` 中挑最高码率，兜底 `play_addr_h264` / `download_addr` / `play_addr.uri`
- **图片**：`images[].url_list` 原图
- **Live**：`images[]` 静态图 + `images[i].video.play_addr` 动态短片（`imageMediaTypes` 列表交替存储 IMAGE/VIDEO）

`AwemeProfile` 关键字段：
- `awemeId()` / `mediaType()` / `desc()` / `authorNickname()` / `authorSecUserId()`
- `downloadUrls()` — 可下载 URL 列表
- `imageMediaTypes()` — 每个 URL 对应的媒体类型（Live 图的关键：偶数索引=封面 IMAGE，奇数索引=短片 VIDEO）

---

## 6. a_bogus 签名算法（最重要）

> ⚠️ **这是整个项目最核心、也最容易失效的部分。接手者必须先完全理解这一节。**

### 6.1 背景

抖音 Web 端从 2024 年 6 月起，把主签名从 X-Bogus 迁移到 **A-Bogus**。当前（2026）线上要求的是 **bdms.js v1.0.1.19-fix.01** 的算法版本，输出 **192 字符**签名。

**本项目在 2026-10-16 完成了算法升级**，从旧版（172 字符、单次 SM3 无盐）升级到新版（192 字符、双重 SM3 加盐）。旧版已经失效。

### 6.2 算法完整结构

```
输入：query（URL 参数字符串）+ User-Agent + 当前时间戳
   ↓
1. 三条哈希链（query / body / user_agent）
   - query 链：SM3(SM3(query + "dhzx"))
   - body 链：SM3(SM3("" + "dhzx"))  （GET 请求 body 恒为空）
   - UA 链：RC4加密(UA) → s3字母表Base64 → SM3
   ↓
2. 构造 50 字段载荷（FIELD_ORDER，L24~L85）
   - 每个字段是 1 字节，共 50 字节
   - 包含时间戳、fortnight 计数、环境标志、三条链各取 3 字节等
   ↓
3. 版本块（8 字节）+ 校验和（异或所有字段）
   ↓
4. 噪声扩展（expandNoise）：每 3 字节扩为 4 字节
   ↓
5. 非标准 RC4 加密（密钥 0xD3）
   ↓
6. 签名头（4 字节，含魔数 3/82）
   ↓
7. s4 字母表 Base64 编码 → 192 字符输出
```

### 6.3 关键常量（ABogusGenerator.java）

```java
// 盐值
SALT = "dhzx"

// 两套字母表
ALPHABET_S3 = "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe"
ALPHABET_S4 = "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe"

// 签名头魔数
HEADER_MAGIC_FIRST = 3, HEADER_MAGIC_SECOND = 82

// 载荷密钥（单字节）
PAYLOAD_KEY = 0xD3

// 纪元：2024-07-24T16:00:00Z（fortnight 计数基准）
FORTNIGHT_EPOCH_MS = 1_721_836_800_000L
FORTNIGHT_MILLIS = 14 天

// 页面标识（必须与请求参数一致）
PAGE_ID = 6241, AID = 6383

// 50 字段排列顺序
FIELD_ORDER = { "L34","L44","L56",...共 50 个 }
```

### 6.4 与旧版算法的关键差异（供参考）

| 维度 | 旧版（已废弃） | 新版（当前） |
|------|--------------|-------------|
| 哈希 | 单次 SM3 无盐 | 双重 SM3 + `dhzx` 盐 |
| 核心结构 | 44 字节 list4 | 50 字段 FIELD_ORDER |
| RC4 | 标准 RC4 | 非标准（S 盒降序 + 乘法调度） |
| 时间戳 | 直接 currentTimeMillis | fortnight 双周计数 |
| 输出 | 172 字符 | 192 字符 |

### 6.5 维护要点（重要）

**签名算法会周期性失效**。当解析失败、Logcat 显示 `HTTP 403` 或 `invalid signature` 时，第一优先级是检查签名算法是否过期。

维护方式（二选一）：
1. **跟进上游**：halcyra/DYDownloader 的 GitHub 仓库，看它最新 commit 的 `ABogusGenerator.java` 是否有更新，移植过来（本项目就是这么做的）。
2. **自行逆向**：逆向 bdms.js 的新版本，更新常量。难度极高（JSVMP 混淆，2.6 万+ 条指令），不建议。

---

## 7. WebSign 第二道签名

> 这是 2026 年抖音新增的一道风控，旧代码里没有，是导致解析失败的第二大原因。

### 7.1 作用

对**受保护接口**（`aweme/detail`、`aweme/post`、`mix/aweme` 等 14 个路径），在 a_bogus 之外还要追加 `timestamp` + `x-secsdk-web-signature`，否则返回 `403 Uifid Not Found`。

### 7.2 签名公式

```
signature = MD5(uifid + "_" + timestamp + "_" + SALT + "_" + 规范化query)

SALT = "A96D855A08C0A9707F8BEF0D9A527E4E"
```

### 7.3 受保护路径清单（SIGNED_PATHS）

```java
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
```

### 7.4 完整签名链组装顺序（buildSignedQuery）

```java
1. encodeQueryParams(params)  →  query（quote_plus 编码）
2. 若受保护接口且含 uifid → WebSignGenerator.normalizeQuery(query)
3. a_bogus = aBogusGenerator.getValue(query)
4. signedQuery = query + "&a_bogus=" + quote(a_bogus)   // s4 含 / 和 =，需百分号编码
5. 若受保护接口 → WebSignGenerator.sign(signedQuery, uifid, timestamp)
```

---

## 8. Cookie 管理与会话

### 8.1 存储（CookieManager.java）

用 `SharedPreferences`（文件名 `douyin_prefs`）存储：
- `dy_cookie`：抖音登录态 Cookie
- `disclaimer_shown`：免责声明是否已确认

### 8.2 登录抓取（DouyinLoginActivity.java）

```
WebView 打开 https://www.douyin.com/（桌面 UA）
   ↓
用户扫码登录
   ↓
每 1.5s 轮询检测 Cookie 是否含 sessionid + uid_tt
   ↓
检测到登录 → android.webkit.CookieManager.getCookie() 抓取全部
   ↓
写入 SharedPreferences
```

**关键**：必须用桌面 UA（`Chrome/126`），否则抖音返回手机版 HTML。`android.webkit.CookieManager` 能抓到 HttpOnly 的 `msToken`/`UIFID`。

### 8.3 Cookie 必填字段

签名链和请求都依赖这几个字段：

| 字段 | 用途 | 缺失后果 |
|------|------|---------|
| `msToken` | 签名链参数 | 签名无效 |
| `uifid` / `UIFID` | WebSign 签名 + 请求参数 | WebSign 403 |
| `sessionid` | 登录态 | 无法解析私有作品 |
| `ttwid` | 设备标识 | 风控 |
| `odin_tt` | 设备标识 | 风控 |

---

## 9. 下载与存储（MediaStore）

### 9.1 下载流程（DownloaderBridge.java）

```
download(profile, urlIndex, cookie, overrideType, ...)
   ↓
doDownload()：
  1. 探测 Content-Type（HEAD 请求，区分真实媒体类型）
  2. 生成文件名（作者名_描述_序号.扩展名）
  3. saveToMediaStore() 写入
```

### 9.2 存储路径

| 媒体类型 | MediaStore 路径 |
|---------|----------------|
| 视频 VIDEO | `Movies/DouyinDownloader/` |
| Live 短片 LIVE_VIDEO | `Movies/DouyinDownloader/LivePicturesMovies/` |
| 图片 IMAGE | `Pictures/DouyinDownloader/Pictures/` |
| 合成 Live 图 | `Pictures/LivePhotos/` |

### 9.3 版本适配

- **Android 10+（API 29+）**：MediaStore `IS_PENDING` 机制（先 insert pending，写完置 0）
- **Android 9-（API 28-）**：cache 目录临时文件 + 复制到公共目录

### 9.4 关键修复记录（注释里标注的 Bug）

| Bug | 问题 | 修复 |
|-----|------|------|
| Bug3 | 静态封面 URL 扩展名是 .mp4 但实际是 JPEG | 下载前 HEAD 探测 Content-Type |
| Bug4 | CDN 返回 403 | 下载请求带 Cookie + Referer |

---

## 10. Live 实况图合成

### 10.1 原理（LivePhotoMaker.kt）

对齐 Google Motion Photo 标准，生成 `.MP.JPG` 文件：

```
1. 提取封面（从视频抽第一帧，或直接用已有封面）
2. 视频转码为 MP4（H.264 + AAC）
3. 生成 XMP 元数据（Camera:MotionPhoto 结构）
4. XMP 注入 JPEG APP1 段（紧跟 SOI 之后）
5. JPEG + MP4 拼接为 .MP.JPG
6. 保存到 Pictures/LivePhotos/
```

### 10.2 XMP 元数据（关键）

```xml
<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF ...>
    <rdf:Description
        Camera:MotionPhoto="1"
        Camera:MotionPhotoVersion="1"
        Camera:MotionPhotoPresentationTimestampUs="1000000">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li>  <!-- 封面：image/jpeg -->
          <rdf:li>  <!-- 视频：video/mp4，Length=视频字节数 -->
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
```

### 10.3 视频转码（FFmpegUtils + MediaCodecMp4Encoder）

由于打包的 ffmpeg-kit 禁用了 mediacodec 和 libx264：
- **抽帧**：走 ffmpeg（`-vf select`），失败回退 `MediaMetadataRetriever`
- **转码**：走 Android 原生 `MediaCodec`/`MediaMuxer`（`MediaCodecMp4Encoder.encode()`），实际是 **remux**（H.264 passthrough，极快无画质损失）

### 10.4 质量预设

| 预设 | CRF | 音频码率 | 说明 |
|------|-----|---------|------|
| COMPACT | 28 | 96k | 高压缩 |
| BALANCED | 23 | 128k | 默认 |
| PRISTINE | 18 | 256k | 高质量 |

### 10.5 合成流水线（DownloadViewModel）

```
下载全部 Live 图：
  Phase 1（DOWNLOADING）：按对顺序下载 cover → video（N*2 文件）
  Phase 2（COMPOSING）：逐对合成，成功删源文件，失败保留
```

**Live 图配对规则**：`imageMediaTypes` 里偶数索引是封面（IMAGE），奇数索引是短片（VIDEO）。

---

## 11. UI 层与设计系统

### 11.1 主框架（MainScreen.kt）

- 5 个 Tab：视频 / 图片 / Live / 作者 / 设置
- `HorizontalPager` 横向滑动，`beyondBoundsPageCount = tabItems.size`（所有页面常驻，切换无卡顿）
- 底部 `GlassTabBar`：玻璃态 + 弹性动画（选中 tab 上跳 + 缩放）
- 每个 Tab 独立 ViewModel（用 key 区分）

### 11.2 设计系统（designsystem/）

**双套设计 token**：
- **新拟态（Neumorphism）**：`NeumorphicButton`、`NeumorphicInputField`、`NeumorphicModifiers`
- **玻璃态（Glassmorphism）**：`GlassCard`、`GlassSurface`、`GlassTopAppBar`、`GlassTokens`

**主题**：`HyperOSTheme`（深色/浅色双套），核心 token 在 `Color.kt` / `Type.kt` / `Spacing.kt` / `GlassTokens.kt`。

### 11.3 弹窗组件

- `DisclaimerDialog`：首次启动免责声明
- `BatchDownloadConfirmDialog`：批量下载确认
- `BatchDownloadProgressDialog`：批量下载进度
- `LiveComposeProgressDialog`：Live 合成进度

---

## 12. 视频 / 图片 / Live 预览

### 12.1 视频预览（VideoPlayerActivity.kt）

> **2026-10-16 重写**：从「OkHttp 全量下载 + VideoView」改为「Media3 ExoPlayer 流式播放」。

**改造前的问题**：
- 全量下载后才播放（大文件体验极差）
- 无进度条
- 画面贴顶不居中
- 临时文件从不清理

**改造后**：
- `ProgressiveMediaSource` 边下边播，秒开
- `PlayerView` 内置可拖动进度条
- `RESIZE_MODE_FIT` 居中等比缩放
- 请求带 Referer + UA（防 CDN 403）

**依赖**：`media3-exoplayer` + `media3-ui`（1.2.1）。

### 12.2 图片预览（ImagePreviewActivity.kt）

- Coil `AsyncImage` 流式加载（边下边显）
- 双指缩放（1x~4x）+ 拖拽
- 左右切换 + 缩略图导航（自动居中跟随）
- 图片 URL 用 `|||` 分隔传入

### 12.3 Live 预览（LivePhotoPreviewActivity.kt）

- 静态封面为主图（Coil）
- 底部「播放」按钮 → 跳转 `VideoPlayerActivity` 播放动态短片
- 缩略图导航 + 双指缩放
- 右上角「LIVE」标签

---

## 13. 作者主页与批量下载

### 13.1 作者解析（AuthorViewModel.parse()）

两阶段解析：
1. **阶段 1**：直接按作者主页链接解析（`collectAccountWorksInfo`）
2. **阶段 2**：若是单作品/合集短链，先解析作品拿到作者 sec_uid，再构造 `/user/{secUid}` 走作者解析

作者主页作品列表走 `/aweme/v1/web/aweme/post/` 端点（分页，每页 18 个）。

### 13.2 批量下载任务展开

`expandWorkToTasks()` 把单个作品展开为任务列表：
- 纯视频 → 1 个任务
- 纯图集 → N 个任务（每张图）
- Live 作品 → 每张 Live 拆成 (cover, video) 两个任务，附 livePairIndex/role

### 13.3 下载模式

| 模式 | 说明 |
|------|------|
| 下载全部 | 所有作品 |
| 仅下载视频 | 只下纯视频（不含 Live） |
| 下载所选 | 选择模式勾选的作品 |

### 13.4 风控缓解

批量下载时每个文件间隔 `delay(800)`（800ms），降低风控触发概率。

---

## 14. 下载历史与缓存管理

### 14.1 历史记录（HistoryScreens.kt）

分类（`HistoryCategory` 枚举）：

| 分类 | 扫描路径 |
|------|---------|
| VIDEO | `Movies/DouyinDownloader/` |
| LIVE_VIDEO | `Movies/DouyinDownloader/LivePicturesMovies/` |
| IMAGE / LIVE_IMAGE | `Pictures/DouyinDownloader/Pictures/` |
| LIVE_COMPOSED_PHOTO | `Pictures/LivePhotos/` |

扫描方式：Android 10+ 走 MediaStore 查询，9- 走文件系统。

### 14.2 缓存管理（CacheManager.kt）

- `computeAppCacheBytes()`：统计 cacheDir + externalCacheDir
- `computeDownloadBytes()`：统计三个下载目录
- `clearAppCache()`：清 cacheDir
- `clearAllDownloads()`：清下载目录（Android 10+ 走 MediaStore delete，9- 走 File.delete）

---

## 15. 已知问题与维护要点

### 15.1 签名算法失效（最高优先级）

**症状**：解析失败，Logcat 显示：
```
fetchAwemeDetail try: ...
  -> HTTP 403, body length=50
```
或 `HTTP 200` 但 body 是 `invalid signature`。

**原因**：抖音更新了 a_bogus 算法。

**修复**：跟进 halcyra/DYDownloader 最新 commit，移植 `ABogusGenerator.java` 和 `WebSignGenerator.java`。

### 15.2 msToken 为空

**症状**：Logcat 请求参数里 `&msToken=&`。

**原因**：Cookie 里没有 msToken 字段，或登录时没抓到。

**修复**：重新登录，确认 Cookie 含 msToken。

### 15.3 uifid 无效

**症状**：`HTTP 403 Uifid Not Found`。

**原因**：WebSign 要求 uifid 能被服务端解码校验，旧 Cookie 的 uifid 可能过期。

**修复**：重新登录获取新的 uifid。

### 15.4 限流风险

抖音对单 IP 频繁请求会触发风控，批量下载可能不全。当前用 800ms 间隔缓解，但仍可能被限流。

---

## 16. 后续迭代建议

### 16.1 短期（提升稳定性）

- [ ] 考虑引入 JS 运行时（Rhino/QuickJS）跑官方 bdms.js，让签名自动跟随抖音更新（减少手动移植成本）
- [ ] 视频预览的 ExoPlayer 缓存策略优化（LRU 缓存，避免重复下载）
- [ ] 批量下载增加断点续传

### 16.2 中期（功能增强）

- [ ] 合集（mix）下载支持
- [ ] 下载历史增加搜索/排序
- [ ] Live 图合成支持更多质量选项（分辨率缩放）

### 16.3 长期（架构优化）

- [ ] 协议层与 UI 层解耦，便于协议更新时不影响 UI
- [ ] 引入依赖注入框架（Hilt）
- [ ] 单元测试覆盖签名算法（用已知输入输出验证）

---

## 17. 变更记录

### 2026-11-22：链接格式误报修复 + 输入框快捷操作

**1. 链接格式误报（方案 B）**

「同一个链接有人成功有人失败」的根因是 `URL_PATTERN` 用黑名单排除，漏掉汉字 / emoji / 全角字符 / 零宽字符。这些字符紧跟 URL 时会被吞进 URL，`URI.create()` 抛异常，最终误报「链接格式不正确」。

改动（均在 `DouyinDownloader.java`）：

| # | 改动 | 说明 |
|---|------|------|
| 1 | `URL_PATTERN` 改 RFC 3986 白名单 | `(https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+)`，天然排除一切非法字符 |
| 2 | `isHostInAllowList` 新增 `extractHost()` | 不再用 `URI.create`（遇非法字符必抛），手动截取 host 段，并剥离 userinfo |
| 3 | `extractFirstUrl` 新增 `stripTrailingPunctuation()` | 剥离 URL 尾部包裹标点，保留至少 `scheme://host` 骨架 |
| 4 | `resolveShareLink` 新增 `looksLikeDouyinDomain()` | 无 scheme 但含 douyin 域名时自动补 `https://`，兼容只复制短链主体的用户 |

**2. 输入框剪贴板导入 + 一键清除**

改动文件：`ui/designsystem/component/NeumorphicInputField.kt`（共用组件）。

覆盖模块：VideoScreen / ImageScreen / LiveScreen / AuthorScreen（四个页面共用组件，一处改动全部生效）。

实现：右侧内嵌两个 36dp IconButton——`ContentPaste`（读 `LocalClipboardManager` 填入，空剪贴板 Toast 提示）、`Close`（清空，仅 value 非空时显示）。新增可选参数 `showActions: Boolean = true`。

### 2026-10-17：报错细分 + Cookie 检查

**1. 失败原因细分（`FailReason`）**

新增 `douyin/exception/FailReason.java`，6 类：`NETWORK` / `RISK_CONTROL` / `SIGNATURE_INVALID` / `WORK_NOT_FOUND` / `COOKIE_MISSING` / `UNKNOWN`。`DouyinDownloaderException` 携带 `reason` 字段。

判定优先级（`priority()`）：**`WORK_NOT_FOUND`(6) > `SIGNATURE_INVALID`/`COOKIE_MISSING`(5) > `RISK_CONTROL`(4) > `NETWORK`(2) > `UNKNOWN`(1)**。

> 关键教训：`403` 语义过宽（签名拒绝 / 风控 / 作品删除都可能 403），不能一刀切判风控。私密作品实测：端点2 返 `filter_reason:"status_self_see"`，端点3 返 `403 blocked`，必须让「作品不存在」覆盖「风控」。

**2. 请求前 Cookie 校验**

`fetchAwemeDetail` / `fetchPagedAwemeList` 开头调用 `checkCookieCompleteness()`，缺 `sessionid` / `uifid` / `ttwid` 直接抛 `COOKIE_MISSING`，避免发起注定 403 的请求被误判成风控。

**3. 网络异常正确传播**

`resolveFinalUrl` 不再静默吞 `IOException`（会导致短链提取不到 ID 被误报「链接格式不正确」），改为抛 `AwemeDetailFetchException(NETWORK)`。

**4. Cookie 检查功能**

`CookieManager.checkCookie()` 静态方法返回 `CookieCheckResult`。设置页新增「Cookie检查」按钮 + `CookieCheckDialog`（复用 CookieDialog 样式）。

> **必需字段只有 `sessionid` + `uifid` + `ttwid`。`msToken` 不是必需**——它是动态 token，登录 Cookie 里本就没有，缺失不影响解析。

### 2026-10-16：签名算法升级 + 视频播放器重写

**1. a_bogus 签名升级（v0.0.4 → bdms.js v1.0.1.19-fix.01）**

新增 `util/CustomBase64.java`、`douyin/WebSignGenerator.java`，整体替换 `douyin/ABogusGenerator.java`。

| 维度 | 旧版（废弃） | 新版（当前） |
|------|------------|-------------|
| 哈希 | 单次 SM3 无盐 | 双重 SM3 + `dhzx` 盐 |
| 核心结构 | 44 字节 list4 | 50 字段 FIELD_ORDER |
| RC4 | 标准 | 非标准（S 盒降序 + 乘法调度） |
| 输出 | 172 字符 | 192 字符 |
| WebSign | 无 | 新增 `x-secsdk-web-signature`（MD5 链） |

**2. 视频播放器重写**

`ui/player/VideoPlayerActivity.kt` 从「OkHttp 全量下载 + VideoView」改为 **Media3 ExoPlayer 流式播放**。

- 解决：大文件必须下载完才能播、无进度条、画面贴顶、临时文件泄漏
- 依赖：`app/build.gradle` 新增 `media3-exoplayer` / `media3-ui`（1.2.1）
- 要点：`ProgressiveMediaSource` + `DefaultHttpDataSource`（带 Referer/UA）；`RESIZE_MODE_FIT` 居中；`PlayerView` 内置可拖动进度条

**3. 图片预览优化**

`ui/player/ImagePreviewActivity.kt`：UA 统一为 Chrome/146 常量；底部缩略图切换时自动居中跟随。

---

## 附录 A：关键文件速查表

| 需求 | 文件 | 关键方法 |
|------|------|---------|
| 解析单个作品 | DouyinDownloader.java | `collectWorkInfo()` |
| 解析作者作品 | DouyinDownloader.java | `collectAccountWorksInfo()` |
| a_bogus 签名 | ABogusGenerator.java | `getValue(query)` |
| WebSign 签名 | WebSignGenerator.java | `sign(query, uifid, ts)` |
| 自定义 Base64 | CustomBase64.java | `encode(data, alphabet)` |
| 失败原因判定 | DouyinDownloader.java | `classifyDetailFailure()` / `priority()` |
| 失败原因枚举 | FailReason.java | `NETWORK` / `RISK_CONTROL` / `SIGNATURE_INVALID` / `WORK_NOT_FOUND` / `COOKIE_MISSING` / `UNKNOWN` |
| 报错文案映射 | ui/screens/ErrorMessages.kt | `resolveErrorMessage()` |
| Cookie 字段检查 | CookieManager.java | `checkCookie(String)` |
| 下载到相册 | DownloaderBridge.java | `download()` |
| Live 合成 | LivePhotoMaker.kt | `createMotionPhoto()` |
| 视频预览 | VideoPlayerActivity.kt | ExoPlayer 流式 |
| 输入框（含粘贴/清除） | NeumorphicInputField.kt | `showActions` 参数 |
| 缓存清理 | CacheManager.kt | `clearAllDownloads()` |

## 附录 B：编译命令

```bash
# 编译 debug
./gradlew assembleDebug

# 强制重新编译（改代码后验证）
./gradlew :app:compileDebugJavaWithJavac --rerun-tasks

# 安装到设备
./gradlew installDebug
```

---

**文档结束**。接手本项目的 AI/开发者，请优先阅读第 6、7 两节（签名算法），这是本项目最容易失效、最需要维护的部分。
