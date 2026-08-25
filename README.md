# DouyinDownloader（抖音无水印下载 Android App）

抖音无水印视频 / 图片 / Live 实况图下载 Android 原生应用（Java 后端 + Jetpack Compose 前端）
本项目参考了 [halcyra/DYDownloader](https://github.com/halcyra/DYDownloader) 的算法而来，借助大佬的力量进行开发

**⚠️ 仅供个人技术学习使用，禁止商用、禁止违法行为 ⚠️**

## 🎯 项目说明

Android 原生应用，纯 API 方案解析抖音作品，下载无水印视频到本地相册。
**没有 WebView、没有 JS 注入、没有网络拦截**——所有解析逻辑都在 `DouyinDownloader.java`（Java）和 Compose UI 层（Kotlin）里完成。

## 🔧 技术原理

```
1. 用户粘贴抖音分享链接（v.douyin.com/xxx 或完整短链）
2. 用户在设置页填入登录态 Cookie（必填）
3. 本地解析器依次尝试以下三个端点（必传 Cookie + a_bogus 签名链）：
   a. GET https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=<id>
      —— 公开作品免登录，命中即返回
   b. GET https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=<id>&a_bogus=...
      —— 主接口，需要完整签名链 a_bogus / msToken / uifid
   c. GET https://www.iesdouyin.com/aweme/v1/web/aweme/detail/?...
      —— 同上，备用域名
4. 解析 JSON，按作品类型（视频 / 图片 / Live）抽取无水印直链：
   - 视频：bit_rate[].play_addr.url_list 中挑最高码率，
           兜底用 play_addr_h264 / download_addr / play_addr.uri 拼 /aweme/v1/play/?video_id=...
   - 图片：images[].url_list 原图
   - Live：images[] 中静态图 + images[i].video.play_addr 动态短片
5. DownloaderBridge（OkHttp）下载到 MediaStore：
   - 视频 → Movies/DouyinDownloader/
   - 图片 → Pictures/DouyinDownloader/
   - 批量下载时通过前台服务 + 进度通知展示
```

### 涉及到的核心代码（精简）

```java
// 1. 解析分享链接（不强制要 cookie）
DouyinDownloader downloader = new DouyinDownloader(cookie);
AwemeProfile profile = downloader.collectWorkInfo(shareLink);

// 2. profile 里已经有 desc / author / downloadUrls() / mediaType
List<String> urls = profile.downloadUrls();

// 3. 单条 / 批量下载到 MediaStore
downloaderBridge.download(profile, index, cookie, overrideType,
    progressListener, completeListener);
```

### a_bogus 签名

本地纯 Java 实现：SM3 + RC4 + 自定义 Base64。算法来自 halcyra/DYDownloader，遵循 AGPL-3.0。

## ✨ 主要功能

| 功能 | 说明 |
|------|------|
| 视频下载 | 单视频解析下载，最高码率无水印直链 |
| 图片作品下载 | 一次性下载原图集合，保存到 `Pictures/DouyinDownloader/` |
| Live 实况图下载 | 静态封面 + 动态短片分别下载 |
| 批量下载 | 图片集 / Live 集触发"须知"确认弹窗后并发下载，进度前台通知 |
| 下载历史记录 | 设置页可查看本地已下载的 视频 / 图片 / Live 三类文件 |
| 新拟态 / 玻璃态 UI | 全面 Compose 实现，深色 / 浅色双套设计 token |

## 🆚 方案演进（背景信息，仅供参考）

| 版本 | 方案 | 状态 |
|------|------|------|
| V1 | WebView JS 注入读 `_ROUTER_DATA` | ❌ 抖音改了变量名就失效 |
| V2 | WebView `shouldInterceptRequest` 拦截 mp4 源 | ❌ 抖音改为 WebCodecs + FLV 流，不再下 mp4 |
| **V3**（当前） | 直接调官方 API + 本地 `a_bogus` 签名 | ✅ 当前可用 |

## ⚠️ 注意事项

- **Cookie 必填**：抖音现已对三个端点全部加入风控，必须在设置页填入登录态 Cookie（含 `ttwid` / `sessionid` 等关键字段）才能正常解析。未设 Cookie 时 `iteminfo/` 也无法返回有效 JSON，会直接报错
- **限流风险**：抖音对单 IP 频繁请求会触发风控，短时间批量下载可能不全
- **尊重版权**：下载内容请仅供个人学习欣赏

## 🛠 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17（`compileOptions` / `kotlinOptions.jvmTarget = '17'`）
- Android SDK 34（`compileSdk 34 / targetSdk 34`）
- `minSdk 24`（Android 7.0）
- Jetpack Compose BOM `2023.10.01`，Compose Compiler `1.5.1`

## 🚀 使用方法

1. 复制抖音分享链接（如 `https://v.douyin.com/AbCdEfG/` 或带口令的 `2.94 复制打开抖音 ...` 文本）
2. 在对应页面（视频 / 图片 / Live）粘贴到输入框
3. **进入设置页 → 填入抖音 Web 端登录 Cookie**（必填，否则解析会失败）
4. 点击"解析"，等待 2-5 秒
5. 点击"下载"（图片 / Live 集合会自动弹"须知"确认）→ 文件保存到 `Movies/DouyinDownloader/` 或 `Pictures/DouyinDownloader/`

## 📦 主要依赖

| 库 | 版本 | 用途 |
|----|------|------|
| OkHttp | 4.12.0 | HTTP 请求、签名链构造 |
| Jackson | 2.15.2 | JSON 解析（沿用 halcyra 项目） |
| Gson | 2.10.1 | 备用 JSON 解析 |
| Compose BOM | 2023.10.01 | UI 框架 |
| ExifInterface | androidx.exifinterface | Live 图 EXIF 元数据读取 |

## 📁 项目结构

```
app/src/main/java/com/xianyu/vediodownload/
├── douyin/                    # 抖音协议层（Java）
│   ├── DouyinDownloader.java  # 解析主入口、三个端点 fallback、URL 候选拼接
│   ├── AwemeProfile.java      # 解析结果数据类
│   └── ...                    # ABogus 签名 / Cookie 管理
├── ui/
│   ├── screens/               # Compose 页面（视频 / 图片 / Live / 历史 / 设置 / 主页）
│   ├── designsystem/          # 设计系统：新拟态 / 玻璃态 token & 组件
│   └── player/                # 内置预览播放器
├── util/
│   ├── DownloaderBridge.java  # OkHttp 下载 + MediaStore 写入
│   ├── CacheManager.kt        # 本地历史文件扫描
│   └── ...
├── MainActivity.kt            # Compose 单 Activity 入口
└── ...
```

## 📄 免责声明

本项目仅演示 Android OkHttp / `a_bogus` 签名算法的使用方法。请勿用于：
- 商业化
- 批量爬取
- 侵犯他人版权
- 违反抖音 ToS 的任何用途

下载的内容版权归原作者所有。

本项目使用的 `a_bogus` 签名算法来自 halcyra/DYDownloader，遵循 AGPL-3.0
（完整条款见根目录 `LICENSE` 与 `NOTICE` 文件）。

本项目仅供本人学习研究，**严禁用于任何商业用途**。

作者：懒得翻身的咸鱼 xianyu2298（基于 halcyra/DYDownloader 修改）。

用户使用本软件产生的一切行为，全部由使用者本人承担全部法律责任。
因违规使用本软件造成的版权侵权、民事赔偿、行政处罚等一切后果，项目开发者不承担任何连带责任。