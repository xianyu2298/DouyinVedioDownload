# 抖净-DouyinDownload 项目交接文档

> **面向读者**：下一个接手本项目的 AI / 开发者
> **文档目标**：从零理解本项目的完整架构、每个功能的实现原理、关键算法细节，以及后续维护要点
> **最后更新**：2026-11-28

---

## 0. 快速接手须知（先读这里）

### 0.1 项目现状（2026-11-28）

当前版本 **v1.6（versionCode 8）**，最新提交 `e490014`，已推送 GitHub；Gitee 已发布 `v1.6`。

解析、下载、Live 合成、预览、历史、缓存、检查更新、音频下载均可用。签名算法为 **bdms.js v1.0.1.19-fix.01**（192 字符 a_bogus + x-secsdk-web-signature）。

- **快手支持**（2026-11-22）：设置页切换「抖音 / 快手」，单作品解析（视频 / 图集 / 单图），网页端最高 720p，详见第 18 节。
- **检查更新**（2026-11-22）：Gitee Releases + 12 小时缓存，详见第 19 节。
- **音频下载**（2026-11-28）：背景音乐 / 纯原声 / 原声+背景音乐三选项，无损直通导出，见第 17 节「补 7」。
- **Live 图三星识别**（2026-11-28）：XMP 补写旧标准 `MicroVideo` 标签，修复「谷歌相册能识别、三星相册无动态角标」，见 10.2 与第 17 节。
- **release 体积**（2026-11-28）：开 R8 后 58 MB → 39 MB。

### 0.1.1 当前待办（接手先看）

| 事项 | 状态 | 说明 |
|------|------|------|
| v1.6 真机冒烟 | ⏳ 未做 | 重点验证 R8 后：抖音解析、快手解析、音频导出、Live 合成 |
| Live 图三星角标复测 | ⏳ 未做 | 已补 `MicroVideo` 旧标签，需**重新合成**后确认三星相册出现「动态」角标；若仍失败按 10.2 末尾三条差异依次排查 |
| 签名自动化 | ⏳ 未配置 | 在根目录建 `keystore.properties`（已在 .gitignore），即可用 `assembleRelease` 出签名包；目前仍靠 Studio 向导 |
| Gitee 下载被加 `.zip` 后缀 | 🐞 待修 | Gitee 对 APK 返回 `Content-Type: application/zip`，部分浏览器据此改名。方案 B（应用内下载 + 唤起安装器，需 FileProvider + REQUEST_INSTALL_PACKAGES）已评估未实施 |
| ABI 拆分 | 💤 未做 | 通用包 39 MB；仅 arm64 27 MB、仅 v7a 16.6 MB。用户仅 40 人且非技术用户，暂不拆 |
| 「仅原声」音源分离 | ❌ 不做 | 抖音只下发单条混合音轨，分离需 AI 模型，成本高收益低 |

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
| 不能只取第一个 URL | 分享文案里可能混入其他链接（剪贴板残留），必须扫描所有 URL 取第一个域名匹配的。 |
| 特殊字符先归一化 | 输入法/转发会把 `//` 变全角、塞入零宽字符；`LinkTextNormalizer` 必须先跑，再做正则匹配。 |
| 签名 UA 必须一致 | `DEFAULT_USER_AGENT`、`buildCommonWebParams` 的 browser_version、`ABogusGenerator` 的 UA 三者必须完全一致，否则签名校验失败。 |
| Live 图要写两套 XMP | 只写新标准 `Camera:MotionPhoto` + `Container:Directory` 时，谷歌相册正常、**三星相册只显示静态图（无「动态」角标）**。必须同时写旧标准 `Camera:MicroVideo` 四标签，见 10.2。 |

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
18. [快手解析（Kuaishou）](#18-快手解析kuaishou)
19. [检查更新（Gitee Releases）](#19-检查更新gitee-releases)

---

## 1. 项目概述

**抖净-DouyinDownload** 是一个抖音无水印下载 Android 原生应用，功能包括：

| 功能 | 说明 |
|------|------|
| 视频下载 | 解析抖音分享链接，下载无水印最高码率视频 |
| 快手下载 | 设置页切换「快手模式」后，解析快手视频 / 图集 / 单图（网页端上限 720p） |
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

**必须同时写两套 Google 规范**，只写一套会出现「谷歌相册能识别、三星相册只认静态图」的分裂现象（2026-11-28 实测定位，见 17 节）：

- **新标准 MotionPhoto + Container:Directory** → Google Photos / Pixel 读这套
- **旧标准 MicroVideo** → 三星图库（Galaxy）按这套判定「动态」角标

```xml
<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:Camera="http://ns.google.com/photos/1.0/camera/" ...>
    <rdf:Description
        Camera:MicroVideo="1"
        Camera:MicroVideoVersion="1"
        Camera:MicroVideoOffset="视频字节数"
        Camera:MicroVideoPresentationTimestampUs="1000000"
        Camera:MotionPhoto="1"
        Camera:MotionPhotoVersion="1"
        Camera:MotionPhotoPresentationTimestampUs="1000000">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li>  <!-- 封面：image/jpeg，Semantic=Primary -->
          <rdf:li>  <!-- 视频：video/mp4，Semantic=MotionPhoto，Length=视频字节数 -->
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
```

要点：

- `MicroVideoOffset` 与 `Item:Length` 同值，都是**从文件末尾算起的视频字节数**（本实现中视频原样追加在 JPEG 之后，两者天然相等）。
- 两套标准共用同一个 `http://ns.google.com/photos/1.0/camera/` 命名空间，故前缀名（`Camera:` / `GCamera:`）不影响 XML 解析；但若要逐字节对齐参考实现，应统一为 `GCamera:`。
- 时间戳固定 `1000000`（1 秒），与参考实现 live-photo-conv 一致；三星原机文件用 `-1`，两者都能识别，无需改动。
- **仍未对齐参考实现的差异**（用户要求暂缓，若三星仍不识别按此顺序排查）：主图 `Container:Item` 多了 `Item:Length="0"` / `Item:Padding="0"`；XMP APP1 段插在 APP0(JFIF) 之前且无 `<?xpacket?>` 包裹；内嵌 MP4 非 faststart（moov 在尾部，参考在头部）。

### 10.3 视频重封装（FFmpegUtils + MediaCodecMp4Encoder）

由于打包的 ffmpeg-kit 禁用了 mediacodec 和 libx264：
- **抽帧**：走 ffmpeg（`-vf select`），失败回退 `MediaMetadataRetriever`
- **重封装**：走 Android 原生 `MediaExtractor`/`MediaMuxer`（`MediaCodecMp4Encoder.encode()`），**原编码直通**，极快无画质损失
- **直通白名单**：`video/avc`、`video/hevc`、`video/mp4v-es`。早期版本只放行 H.264，导致 HEVC 源在合成阶段直接失败（2026-11-28 修复）
- **音频**：不再按编码过滤，原音轨直通；只有 MediaMuxer 明确拒绝该编码时才降级为无音轨（`Log.w` 留痕）
- 非白名单编码（VP9 / AV1）无法处理：本工程没有可用的编码器，不做重编码兜底

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

### 15.5 「链接格式不正确」怎么定位

用户不会抓 Logcat，因此错误卡片带「复制诊断信息」按钮，点一下得到：

```
【抖净诊断】
版本: 1.5 (7)
模式: 抖音
时间: 2026-11-22 16:20:31
原文: 4.15 复制打开抖音，看看【小哥胡修车的作品】…
识别到的链接: https://v.douyin.com/HVp0RJfSN34/
异常: AwemeDetailFetchException: no douyin link found, urls=[]
```

判定方法：

| 现象 | 结论 |
|------|------|
| `识别到的链接` 为空 | 文本里确实没有可识别链接，或被改形（对比原文是否有全角/换行/零宽） |
| `识别到的链接` 是别的平台域名 | 用户在错误的解析模式下粘贴（看「模式」那一行） |
| 链接正常但 `异常` 是 `NO_LINK_FOUND` | 域名不在白名单（可能是陌生短链域名，需补白名单） |
| `异常` 是 `LINK_INCOMPLETE` | 看 `status` / `bodyLen`：`status=200` + `bodyLen>0` = 短链未 302 返回了落地页（已由兜底逻辑处理）；`final` 与输入相同也说明没发生重定向 |
| 版本为 1.4 及更早 | 老包没有今天的归一化/多 URL 修复，先升级再判定 |

> 只在诊断需要时读取 `describeLinks()`，不要把它当作解析入口——正式解析仍走 `resolveShareLink`。

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

### 2026-11-28（补）：Live 图支持 HEVC 等非 H.264 源

**症状**：部分作品合成 Live 图失败——源视频是 HEVC 时整对直接失败。

**根因**：`MediaCodecMp4Encoder.encode()` 里 `!videoMime.contains("avc")` 即 `return false`，只放行 H.264。这是当初因 ffmpeg-kit 无 libx264 而加的保护，白名单过严。对照参考实现：live-photo-conv **完全不做转码**，把视频字节原样追加到 JPEG 后（ffmpeg 只用来抽封面帧），所以「什么编码都能合成」。

**改动**：改为白名单直通 `video/avc` / `video/hevc` / `video/mp4v-es`；仍用 MediaMuxer 重封装而非字节追加，保证容器规范（避免“能合成但播不了”）。VP9 / AV1 仍不支持——本工程无可用编码器。

**注意（易漏点）**：编码判断有两处，只改一处会「日志已放行、UI 仍报非 H.264」：

1. `MediaCodecMp4Encoder.encode()` —— 真正干活的拦截
2. `LivePhotoComposeViewModel.checkVideoCodec()` —— 合成页的 UI 预检，原来独立写着 `!mime.contains("avc")` 并弹出「该视频非 H.264 编码」

现已统一为 `MediaCodecMp4Encoder.isCodecSupported(mime)` 单一来源。**以后改编码支持范围，只改白名单一处。**

**同时修掉**：原音轨不再按 AAC 过滤，非 AAC（如 Opus）也走直通；MediaMuxer 拒绝时才降级为无音轨。

**验证**：编译通过；需真机验证 HEVC 源合成后在三星 / 谷歌相册可正常播放。

### 2026-11-28：Live 图三星相册识别修复（补 MicroVideo 旧标准）

**症状**：合成出的 `.MP.JPG` 在谷歌相册识别为 Live 图，三星相册只显示静态照片、没有「动态」角标。

**定位方法（可复用）**：把参考实现（[wszqkzqk/live-photo-conv](https://github.com/wszqkzqk/live-photo-conv)）生成的同一张图与我们的输出作为两个原始文件，用脚本解析 JPEG 段表 + XMP + 内嵌 MP4 偏移做比对。**注意：截图、剪贴板粘贴会把文件转码成纯静态 JPEG，XMP 与视频全部丢失，必须用原始文件。**

| 项 | 参考实现（三星可识别） | 本项目（不可识别） |
|----|----------------------|-------------------|
| 旧标准 MicroVideo 四标签 | 有 | **无 ← 主因** |
| 前缀 | `GCamera:` | `Camera:`（URI 相同） |
| 主图 Container:Item | 仅 Mime + Semantic | 多 `Length="0"`、`Padding="0"` |
| XMP 段位置 / 包裹 | APP0 之后，带 `<?xpacket?>` | APP0 之前，裸 XML |
| 内嵌 MP4 | faststart（moov 前置） | moov 在尾部 |

**改动**：`LivePhotoMaker.buildXmp()` 增补 `Camera:MicroVideo` / `MicroVideoVersion` / `MicroVideoOffset`（= 视频字节数）/ `MicroVideoPresentationTimestampUs`，其余差异按用户要求暂缓（详见 10.2 末尾待排查清单）。

**验证**：`:app:compileDebugKotlin --offline` 通过；已合成的旧文件不会变，需重新合成才生效。

### 2026-11-28（v1.6 发版）：音频下载 + 体积优化

v1.6（versionCode 8）打包内容：

- 音频下载（三种来源选项，见 17 节「音频下载」）
- ffmpeg 无 lame → 改为无损直通导出
- 输入框恢复固定高度
- Live 作品仅允许背景音乐
- release 开启 R8 + 资源压缩，58 MB → 39 MB
- 链接解析健壮性 + 一键诊断（v1.5 已包含，见下方）

### 2026-11-28：release 体积优化（58 MB → 38.3 MB）

**根因**：`release` 一直是 `minifyEnabled false` 且无签名配置，实际分发的是 debug 包；同时引入了 `material-icons-extended`（33 MB aar），但只用 8 个图标。

| 改动 | 说明 |
|------|------|
| `release` 开 R8 + 资源压缩 | `minifyEnabled true` / `shrinkResources true`，dex 从 63 MB 降到 **5.9 MB**（未使用图标被整体裁掉） |
| 删除无用依赖 | `gson`（零引用，JSON 全走 Jackson）、`constraintlayout`（零引用） |
| 保留 `material` | 主题 `Theme.Material3.Light.NoActionBar` 来自该库，删掉会直接 aapt 链接失败 |
| 去重依赖声明 | `ffmpeg-kit-full-gpl`、`smart-exception-java` 各重复一次 |
| 重写 `proguard-rules.pro` | 删掉 Gson 规则；保留 Jackson（databind/core/annotation）、ffmpeg-kit（JNI 回调按类名查找）、OkHttp |
| 条件式签名 | 根目录 `keystore.properties` 存在时才启用 `signingConfigs.release`；该文件已在 `.gitignore`（第 47 行） |

**实测体积**（同一份代码）：

| 产物 | 体积 |
|------|------|
| debug（原分发包） | 58 MB |
| release 通用（arm64 + v7a） | **38.3 MB** |
| release 仅 arm64 | 27.0 MB |
| release 仅 armeabi-v7a | 16.6 MB |

剩余体积几乎全是原生库：arm64 21.76 MB + v7a 11.34 MB（ffmpeg full-gpl）。再往下只能精简 ffmpeg 构建或做 ABI 拆分。

**待办**：release 包目前未签名（无 `keystore.properties`），需补签名后才能分发；R8 后需真机冒烟（解析 / 下载 / 音频导出 / Live 合成）。


### 2026-11-22（补 7）：音频下载（背景音乐 / 原声可选，统一 mp3）

视频页在「下载无水印视频」下方新增「下载音频」，点击后弹窗选择来源；图文页与 Live 页同样提供入口。

| 选项 | 判定条件 | 数据来源 |
|------|---------|---------|
| 背景音乐 | `music.play_url` 非空且非原声作品 | 曲库 BGM 直链（纯 BGM，无人声） |
| 原声（纯原声） | `music.is_original = true` | 视频音轨——此类作品未添加 BGM，音轨本身即纯原声 |
| 原声 + 背景音乐 | 非原声作品且有视频流 | 视频音轨——人声与 BGM 已在服务端混合，无法拆分 |

弹窗按作品实际情况动态生成选项（`AudioDialogState.options`），不是固定两行：原声作品只出现「原声（纯原声）」，曲库作品出现「背景音乐 + 原声 + 背景音乐」两条。

**为什么没有"从混合音轨里分离出纯原声"**：抖音下发的视频只有一条 AAC 音轨，博主录音与 BGM 已混合，接口不提供可分离的第二条流。唯一独立的音频资产是 `music.play_url`（纯 BGM）。要做真正的原声分离，只能引入 AI 声源分离模型（ONNX，数十至上百 MB），成本与效果都不划算，暂不做。

不可用的来源在弹窗中置灰并注明原因（原声作品无 BGM / 纯图片无音轨），不做自动选择——同一作品可能两者并存且内容不同。

| 文件 | 改动 |
|------|------|
| `AwemeProfile.java` | 新增 `musicTitle` / `musicAuthor` / `musicUrl` 与 `hasBackgroundMusic()` |
| `DouyinDownloader.java` | 解析 `music.title` / `music.author` / `play_url`（含 `play_url_h264` 兜底） |
| `FFmpegUtils.kt` | 新增 `extractAudioToMp3()`：`-vn -c:a libmp3lame -q:a 2 -map_metadata -1`，输出 <10KB 视为失败 |
| `DownloaderBridge.java` | `downloadUrlToFile()`（按平台选 UA/Referer/Cookie）+ `saveAudioToMediaStore()`（MediaStore.Audio → `Music/DouyinDownloader`，Q 以下走 MediaScanner） |
| `DownloadViewModel.kt` | `AudioSource` 枚举、`AudioDialogState`、`requestAudioDownload()` / `downloadAudio()` / 转码管线（下载占 0–85% 进度，转码 90%） |
| `AudioSourceDialog.kt`（新增） | 来源选择弹窗 |
| `PreviewCards.kt` | 视频卡显示「🎵 歌名 · 歌手」 |
| `VideoScreen` / `ImageScreen` / `LiveScreen` | 按钮 + 弹窗接入 |
| `HistoryScreens.kt` | 新增 `AUDIO` 分类（枚举 / 首页入口 / 图标 / MediaStore 与文件系统两条扫描分支） |
| `SettingsScreen.kt` | `SubPage.HISTORY_AUDIO` 及跳转、返回键分支 |
| `CacheManager.kt` | 统计与「清空下载」覆盖 `Music/DouyinDownloader`（Audio 是独立集合，必须单独删；壳目录下直接放文件，原逻辑只扫子目录会漏） |

**验证**：用合成 `aweme` JSON 反射调用 `collectAwemeProfileFromNode`，三种情况均正确——
有 BGM（title/author/url 齐全）、原声（url 为空、author 有值）、图文（IMAGE + BGM 可用）；
`assembleDebug` BUILD SUCCESSFUL。

### 2026-11-28 修正：ffmpeg 无 libmp3lame，改为无损直通

真机日志暴露两个事实：

1. 设备跑的是 **arm64 ffmpeg**（来自 aar 的 `jni/arm64-v8a/*.so`），而该构建的 configuration 里**没有**
   `--enable-libmp3lame`，执行 `-c:a libmp3lame` 直接报 `Unknown encoder 'libmp3lame'`；
2. 工程里 `jniLibs/armeabi-v7a/libmp3lame.so` 是另一套 32 位库，`grep libffmpeg.so` 命中的那个字符串
   属于它，**不能代表 arm64 可用**——之前据此判断「lame 可用」是错的。

改为 `FFmpegUtils.exportAudio()`：

| 源 | 处理 | 产物 |
|----|------|------|
| mp3（曲库直链常见，日志实测 `Audio: mp3 ... 128 kb/s`） | `-c:a copy` | `.mp3`，无损瞬时 |
| AAC（视频音轨） | `-c:a copy` | `.m4a`，无损瞬时 |
| 复制失败 | `-c:a aac -b:a 192k` | `.m4a` |

容器由文件头判定（`ID3` / `0xFFEx` → mp3，否则按 mp4 处理），入库 MIME 随扩展名变化
（`audio/mpeg` / `audio/mp4`）。**结论：本工程无法输出 mp3 编码，只能保留源 mp3 或输出 AAC。**

已知遗留：aar 内 .so 未做 16 KB 页对齐，Android 15+ 的 16 KB 设备会弹「应用兼容性」提示；
需要重编 ffmpeg（NDK r28+）才能消除。

**已知限制**：Live 图不提供原声下载（只给 BGM，多段短片音轨拼接会重复）；原声作品需先下载整段视频；工程同时打包两套 ABI 的 ffmpeg，以 arm64 为准。

### 2026-11-22（补 6）：短链不 302 的兜底

**现场证据**（用户 v1.5 诊断）：`resolved=https://v.douyin.com/QYXvVGm5Zxs/`，即**最终地址与输入完全相同**，说明 302 根本没发生。同一链接在本机用 curl / JVM 请求均正常 302 到 `www.iesdouyin.com/share/video/7708635826690788618/`。结论：部分网络环境下短链服务返回 200 落地页而非 302。

| 改动 | 说明 |
|------|------|
| `probeShortLink(url, cookie, mobile, captureBody)` | 取代原 `resolveFinalUrl`，返回最终地址 + 状态码 + 正文片段（HTML 最多 200KB） |
| 落地页兜底 | 最终地址取不到 ID 时，从正文提取：`share/video/{id}` → `modal_id` → `aweme_id` → `item_ids` → 唯一的 `video/{id}` |
| 二次尝试 | 仍失败则换**移动端 UA 且不带 Cookie** 重试一次（短链服务对桌面/登录态请求更容易返回落地页） |
| 失败信息增强 | 异常里带上 `status` / `final` / `bodyLen`，下次诊断直接可判 |
| 取消静默吞异常 | 原 `catch (Exception ignored) { return url; }` 会把「请求构造失败」伪装成「解析不出 ID」，现改为明确报错 |

**避免下错视频**：通用 `video/{id}` 仅在页面内**唯一**时才采用；出现多个（推荐列表）直接判失败，宁可报错也不下载错的作品。

**验证**：用户失败链接 + 两条历史样本均正常进入 API 阶段；HTML 提取用例（`video/`、`modal_id":"`、无 ID 页面）全部符合预期。

### 2026-11-22（补 5）：链接解析健壮性 + 一键诊断

**背景**：用户反馈「我解析正常，别人报链接格式不正确」。用 JVM 直调解析器复现后确认三个根因：只取文本中第一个 URL、特殊字符（全角/零宽）切断 URL、跨平台链接落到同一句错误文案。

| 文件 | 改动 |
|------|------|
| `util/LinkTextNormalizer.java`（新增） | 全角 ASCII → 半角、去零宽字符（U+200B/200C/200D/FEFF）、全角空格转普通空格 |
| `DouyinDownloader.java` | `resolveShareLink` 改为**扫描所有 URL 取第一个域名匹配的**；无 scheme 时从域名处向左扩展子域名再补 https；新增 `describeLinks()` 供诊断；链接类失败改抛 `NO_LINK_FOUND` / `LINK_INCOMPLETE` |
| `KuaishouDownloader.java` | 同上；页面无 `INIT_STATE` 判 `RISK_CONTROL`，有 `INIT_STATE` 无 `photo` 才判 `WORK_NOT_FOUND` |
| `FailReason.java` | 新增 `NO_LINK_FOUND`、`LINK_INCOMPLETE` |
| `ErrorMessages.kt` | 两类新原因对应针对性文案，不再一律「格式不正确」 |
| `ErrorMessageCard.kt`（新增） | 统一错误卡片 + 「复制诊断信息」入口（写剪贴板 + Toast） |
| `DownloadViewModel` / `AuthorViewModel` | 状态增 `diagnostics`；失败统一走 `failParse` / `failAuthor` 生成诊断文本 |
| 5 个页面 | 错误卡替换为 `ErrorMessageCard` |

**诊断文本内容**：版本号、当前模式、时间、原文前 200 字、正则识别到的链接、异常类与 message。不含 Cookie / 账号信息。用户点一下就能粘贴发给开发者，替代 Logcat。

**回归结果**（JVM 直调真实编译产物，9 个用例全过）：

```
用户样本 T1 / T2            → 通过链接阶段（仅缺 Cookie）
剪贴板混入其他链接           → 取到抖音链接 ✓
全角斜杠 https:／／          → 归一化后识别 ✓
链接中插入零宽字符           → 归一化后识别 ✓
无 scheme（v.douyin.com/x）  → 补全并识别 ✓（修复子域名被截断）
抖音模式贴快手链接           → NO_LINK_FOUND
快手模式贴抖音链接           → NO_LINK_FOUND
无效短链                    → LINK_INCOMPLETE
```

### 2026-11-22（补 4）：Gitee 检查更新

新增基于 Gitee Releases 的版本检查，详见第 19 节。

| 文件 | 改动 |
|------|------|
| `update/UpdateInfo.java`（新增） | 版本信息数据类 |
| `update/UpdateChecker.java`（新增） | API 请求、JSON 解析、版本比较、12h 缓存、已提示版本记录 |
| `ui/screens/UpdateDialog.kt`（新增） | 更新弹窗 + 外部链接跳转 |
| `MainScreen.kt` | 冷启动检查（延迟 1.5s、免责声明未确认则跳过、失败静默） |
| `SettingsScreen.kt` | 版本号下方「检查更新」入口（忽略缓存） |
| `strings.xml` | 新增 8 条更新相关文案 |

**验证**：版本比较 12 项用例全部通过（用编译产物直接跑 `isNewer`/`normalizeVersion`）；`assembleDebug` BUILD SUCCESSFUL。网络请求与弹窗需真机验证。

### 2026-11-22（补 3）：文案平台无关化

风控与通用提示不再写死「抖音」，改为「平台」，避免快手模式下文案自相矛盾。

| 文件 | 改动 |
|------|------|
| `BatchDownloadConfirmDialog.kt` | 「由于抖音风控…」→「由于平台风控…」（图片页 / Live 页共用） |
| `AuthorScreen.kt` | 三处批量下载提示「抖音风控」→「平台风控」 |
| `ErrorMessages.kt` | `COOKIE_MISSING` 字段列表修正为 `sessionid / uifid / ttwid`（原文写 msToken，与实现不符） |

**刻意保留「抖音」字样的位置**（这些功能本身只服务抖音，写平台名更准确）：
Cookie 设置/检查弹窗、`hint_input_url`（抖音模式专用）、作者页输入提示、`抖音号：`字段、模式切换控件与「快手模式」提示文案。

### 2026-11-22（补 2）：切换解析模式清空输入框

切换「抖音 / 快手」时，除预览外**输入框内容也一并清空**，避免抖音链接残留在快手模式下导致「模式与链接不匹配」。

| 文件 | 改动 |
|------|------|
| `DownloadViewModel` | `clearPreview()` → `resetForPlatformSwitch()`，追加 `url = ""`、`isLoading = false`、`loadingMessage`、`cachedProfile` 重置 |
| `AuthorViewModel` | 新增 `resetForPlatformSwitch()`，清空 url / author / works / 选择态 / 确认弹窗 |
| `MainScreen` | 4 个 ViewModel（video / image / live / author）全部调用新方法 |

**刻意保留** `batch` / `compose` 状态：切模式不能打断正在后台跑的下载与合成任务，否则进度回调会因 `batch == null` 静默失效。

### 2026-11-22（补）：抖音预览卡显示清晰度 / 帧率

预览卡新增「清晰度 1080p · 60fps」，与快手侧展示一致。

实现要点（`DouyinDownloader.java`）：

| 改动 | 说明 |
|------|------|
| 抽出 `collectVideoCandidates(aweme)` | 把 `bit_rate[]` 的收集 + 排序从 `collectVideoUrlCandidates` 中提出来 |
| 新增 `VIDEO_CANDIDATE_ORDER` | 排序规则：分辨率 → 帧率 → 码率 → 体积（与快手同规则） |
| 新增 `buildVideoQualityLabel(aweme)` | 取排序后第一个候选的短边作为 `Np`，追加 `· Nfps` |
| `VideoCandidate` 增 `playUri` | 子方法负责收集 URI，主方法不再重复遍历 |

**关键设计**：候选链与标注共用同一套筛选/排序，避免「显示 1080p 却下 720p」。分辨率按**短边**命名（1080×1920 记 1080p），与业界惯例及快手 `qualityType` 一致。尺寸字段全缺失时只显示帧率或空串，不做猜测。

### 2026-11-22（下午）：快手解析 + 解析模式切换

**1. 新增快手单作品解析**

新增 `kuaishou/KuaishouDownloader.java`（链接识别、photoId 提取、短链 302、页面抓取、错误分类）与 `kuaishou/KuaishouPhotoParser.java`（`INIT_STATE` → `AwemeProfile`）。支持视频 / 图集 / 单图，详见第 18 节。

**2. 全局解析模式**

新增 `util/PlatformModeManager.java`；`model/Platform.java` 增加 `KUAISHOU`；`MainScreen` 持有模式状态并下发到 5 个页面；设置页新增「解析模式」分段控件（默认抖音），快手模式下 Cookie 区块禁用。

**3. 下载与预览适配**

- `DownloaderBridge`：按 `profile.platform()` 选择 UA / Referer / 是否附 Cookie；文件名兜底 `kuaishou_<id>`；图片扩展名补 `.webp`（此前 webp 会被改名成 .jpg）。
- `AwemeProfile`：新增 `qualityLabel` 字段；`MediaPreview` 同名字段；`VideoPreviewCard` 如实展示「清晰度 720p · 30fps」。
- `VideoPlayerActivity`：Referer 改由 intent 传入，快手用移动页地址。
- `LiveScreen` / `AuthorScreen`：快手模式渲染 `KuaishouUnsupportedNotice` 占位提示，保留 Tab 避免 Pager 索引变化。

**4. 结论性事实（供后续判断，勿重复调研）**

快手网页端（含登录 Cookie）最高仅 720p；`/rest/wd/photo/info` 对任意 `__NS_sig3` 均返回 `2001 antispam need captcha`；`live_api` 已下线。开源项目（KS-Downloader、videodl）同为 720p，res-downloader 靠 MITM 抓包，不属解析路线。

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

## 18. 快手解析（Kuaishou）

### 18.1 定位与边界

2026-11-22 接入，作为「解析模式」的第二平台（设置页切换，默认抖音）。**只支持单作品**：

| 能力 | 支持 | 说明 |
|------|------|------|
| 视频 | ✅ | 无水印、原帧率、零转码 |
| 图集 / 单图 | ✅ | 图集为 webp 原图 |
| Live 实况图 | ❌ | 快手无「封面 + 短片」结构，快手模式显示占位提示 |
| 作者主页 / 合集 | ❌ | 依赖登录态 GraphQL + 请求签名，未接入 |

### 18.2 数据源（实测结论，别再重复踩坑）

| 项目 | 结论 |
|------|------|
| 页面 | `https://v.m.chenzhongtech.com/fw/photo/{photoId}` 的 `window.INIT_STATE` |
| Cookie | **不需要**，匿名可解析 |
| UA | **必须移动端 UA**；桌面 UA 会被 302 到 www，且页面不含作品数据 |
| GraphQL `visionVideoDetail` | 匿名返回 `result:400002` + 滑块验证；带登录 Cookie 可通，但阶梯仍只有 720p |
| `/rest/wd/photo/info`（App 端） | 任意 `__NS_sig3` 都返回 `2001 antispam need captcha`，纯签名无法攻克 |
| `live_api/profile/feedbyid` | 已下线（302 → /404），KS-Downloader 里也是死代码 |

**画质上限**：网页端（含登录态）`manifest.adaptationSet` 只下发一档 720p（h264 + hevc 各一），帧率保留源帧率（实测 25 / 24.9 / 30 / 44 / 60fps）。这是服务端策略，客户端无法突破；UI 用 `qualityLabel` 如实标注，不做「伪原画」。

### 18.3 解析流程

```
分享文案 → 抽 URL（复用 DouyinDownloader.URL_PATTERN）
   ↓
域名白名单：kuaishou.com / kuaishou.cn / chenzhongtech.com / chenzhongtech.cn
   ↓
抽 photoId：/short-video/{id} | /fw/photo/{id} | /photo/{id} | /u/*/{id} | ?photoId=
   ↓（短链 v.kuaishou.com/xxx、kuaishou.com/f/xxx 走 302 跟随）
GET https://v.m.chenzhongtech.com/fw/photo/{id}（移动 UA）
   ↓
window.INIT_STATE → Jackson 解析 → 递归找含 photoId 的 photo 节点
   ↓（找不到 → WORK_NOT_FOUND，不会误判风控）
KuaishouPhotoParser → AwemeProfile(Platform.KUAISHOU)
```

### 18.4 字段映射

| 作品类型 | 取值方式 |
|---------|---------|
| `VIDEO` | `manifest.adaptationSet[].representation[]`，按 分辨率 → 帧率 → 码率 排序，最高档首位；`mainMvUrls` 兜底 |
| `HORIZONTAL_ATLAS` / `VERTICAL_ATLAS` | `https://{ext_params.atlas.cdn[0]}{atlas.list[i]}` |
| `SINGLE_PICTURE` | `coverUrls[0]` |

其它：`caption` → desc，`userName` → 作者，`headUrl` → 头像，`timestamp/1000` → createTime。

### 18.5 模式切换设计

- 持久化：`util/PlatformModeManager.java`（`app_prefs` / `app_mode`），默认 DOUYIN
- 状态：`MainScreen` 持有 `appMode`，下发到 5 个页面；切换时调用各页 `resetForPlatformSwitch()`（清空输入框、预览、选择态，但保留进行中的批量任务）
- 解析分流：`DownloadViewModel.parse()` 按模式选解析器；模式与链接不匹配时明确提示，不静默自动切换
- Cookie 校验：仅抖音模式需要；快手模式设置页 Cookie 区块禁用
- 存储：沿用 `Movies|Pictures/DouyinDownloader`，文件名兜底 `kuaishou_<id>`，历史/缓存模块无需改动

### 18.6 维护要点

1. `INIT_STATE` 的**顶层键名做过凯撒 +1 移位**（如 `tusjoh.0sftu...`），值仍是标准 JSON。解析按值递归查找，不要依赖键名。
2. 若页面结构改版：症状是「找不到 photo 节点」→ 先人工抓一次页面确认 `photo` 与 `photoId` 是否还在。
3. 若解析突然大面积失败：先检查是否被下发验证码页（HTML 无 `INIT_STATE`），再考虑补 Cookie（`KuaishouDownloader(cookie)` 已预留入口）。
4. 不要为「1080p」去实现 App 端签名：Frida 需 root、MITM 需装证书，均不适合本 App 形态。

---

## 19. 检查更新（Gitee Releases）

### 19.1 数据源

```
GET https://gitee.com/api/v5/repos/jbl2003/DouyinVedioDownload/releases/latest
```

免 token、免登录。实测可用字段：

| 字段 | 用途 |
|------|------|
| `tag_name`（`v1.4`） | 版本比较（去 v 前缀后按 `.` 分段比数字） |
| `name`（`抖净v1.4`） | 弹窗副标题 |
| `body` | 弹窗正文，纯文本原样展示（保留换行） |
| `created_at` | 弹窗副标题的日期（取前 10 位） |
| `assets[].browser_download_url` | 取第一个 `.apk` 作为「立即更新」跳转地址 |
| `prerelease` | 为 true 时直接视为无更新，避免用户装到测试包 |

### 19.2 关键实现

| 文件 | 职责 |
|------|------|
| `update/UpdateInfo.java` | 数据类 |
| `update/UpdateChecker.java` | 请求 / 解析 / 版本比较 / 12 小时缓存 / 已提示版本记录 |
| `ui/screens/UpdateDialog.kt` | 更新弹窗 + `openExternalUrl()` |
| `MainScreen.kt` | 冷启动自动检查 |
| `SettingsScreen.kt` | 版本号下方「检查更新」入口（忽略缓存，强制联网） |

**版本比较规则**（`isNewer`）：去 `v` 前缀 → 截断 `-`/`+` 后缀 → 按 `.` 拆段 → 逐段比数字，缺段补 0。
任一侧解析不出有效段则返回 false（宁可漏提示，不误打扰）。已覆盖 12 个用例，含 `1.10 vs 1.9`（不能按字符串比较）、`1.4 vs 1.4.0`（视为相同）、`release-1.5`（非法 tag 不误报）。

**触发与节流**：

| 场景 | 行为 |
|------|------|
| 冷启动 | 延迟 1.5s 后台检查；免责声明未确认则跳过本次 |
| 缓存 | 12 小时内不重复请求（Gitee 匿名接口按 IP 限流，`X-RateLimit-Limit: 60`） |
| 同版本 | `update_prompted_version` 记录已提示版本，只弹一次 |
| 自动检查失败 | 完全静默 |
| 手动检查 | 忽略缓存；失败 Toast「检查失败」，无更新 Toast「已是最新版本」 |

**不做的事**：不内置 access_token（APK 可反编译）、不做静默下载安装（需 `REQUEST_INSTALL_PACKAGES` 且与签名绑定），只跳浏览器打开 APK 直链；无 APK 附件时回落到 releases 页面。

### 19.3 发版流程

1. Gitee 建 release，tag 固定 `vX.Y` 格式（解析依赖该格式）
2. 上传 APK 附件（文件名以 `.apk` 结尾即可）
3. release 正文用纯文本（`✅` 列表），弹窗会原样展示；写了 Markdown 语法会看到原始符号
4. 应用侧版本号同步改 `versionName`，否则不会触发提示

### 19.4 遇到限流怎么办

症状：自动检查长期无提示、手动检查报「检查失败」。处置顺序：

1. 确认 `X-RateLimit-Remaining`（Gitee 按 IP 计）
2. 若频繁为 0：改用仓库内 `version.json` 静态文件（走 raw CDN，不受 API 限流），只需替换 `UpdateChecker` 的 URL 与字段映射，UI 与缓存逻辑不动
3. 不要为了绕限流而内置 token

---

## 附录 A：关键文件速查表

| 需求 | 文件 | 关键方法 |
|------|------|---------|
| 解析快手作品 | kuaishou/KuaishouDownloader.java | `collectWorkInfo()` |
| 快手字段映射 | kuaishou/KuaishouPhotoParser.java | `parse(photo, photoIdToken)` |
| 解析模式读写 | util/PlatformModeManager.java | `getMode()` / `setMode()` |
| 检查更新 | update/UpdateChecker.java | `check(ignoreCache)` / `isNewer()` |
| 音频转码 | util/FFmpegUtils.kt | `extractAudioToMp3()` |
| 音频入库 | util/DownloaderBridge.java | `saveAudioToMediaStore()` |
| 更新弹窗 | ui/screens/UpdateDialog.kt | `UpdateDialog()` |
| 解析单个作品 | DouyinDownloader.java | `collectWorkInfo()` |
| 抖音清晰度标注 | DouyinDownloader.java | `buildVideoQualityLabel()` |
| 分享文本归一化 | util/LinkTextNormalizer.java | `normalize(text)` |
| 链接识别（诊断用） | DouyinDownloader.java | `describeLinks(text)` |
| 错误卡片 + 复制诊断 | ui/screens/ErrorMessageCard.kt | `ErrorMessageCard(message, diagnostics)` |
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
