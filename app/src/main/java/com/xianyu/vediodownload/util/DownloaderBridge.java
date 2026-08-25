package com.xianyu.vediodownload.util;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import com.xianyu.vediodownload.downloader.HttpDownloader;
import com.xianyu.vediodownload.douyin.AwemeProfile;
import com.xianyu.vediodownload.douyin.MediaType;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

/**
 * 下载桥接：
 * - 把 AwemeProfile 中得到的 URL 列表，配合 HttpDownloader 下载到本地
 * - 按媒体类型保存到 MediaStore（Movies / Pictures / Music）
 * - 子线程执行，回调主线程
 *
 * ⚠️ 仅供个人技术学习使用，禁止商用
 */
public final class DownloaderBridge {

    private static final String TAG = "DouyinDownloader";
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";
    private static final String DEFAULT_REFERER = "https://www.douyin.com/?recommend=1";

    public interface ProgressListener {
        void onProgress(int percent, long downloaded, long total);
    }

    public interface CompleteListener {
        void onSuccess(String savedPath);
        void onError(Throwable error);
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient okHttp;

    public DownloaderBridge(Context context) {
        this.appContext = context.getApplicationContext();
        this.okHttp = new OkHttpClient.Builder()
                .followRedirects(true)
                .build();
    }

    public void download(AwemeProfile profile,
                         int urlIndex,
                         ProgressListener progressListener,
                         CompleteListener completeListener) {
        download(profile, urlIndex, "", null, progressListener, completeListener);
    }

    public void download(AwemeProfile profile,
                         int urlIndex,
                         String cookie,
                         ProgressListener progressListener,
                         CompleteListener completeListener) {
        download(profile, urlIndex, cookie, null, progressListener, completeListener);
    }

    /**
     * @param overrideType 非空时强制使用该类型（用于区分普通 VIDEO 与 LIVE_VIDEO），
     *                     传 null 则按 profile.mediaTypeAt(urlIndex) 推断。
     */
    public void download(AwemeProfile profile,
                         int urlIndex,
                         String cookie,
                         MediaType overrideType,
                         ProgressListener progressListener,
                         CompleteListener completeListener) {
        executor.execute(() -> doDownload(profile, urlIndex, cookie, overrideType, progressListener, completeListener));
    }

    private void doDownload(AwemeProfile profile,
                            int urlIndex,
                            String cookie,
                            MediaType overrideType,
                            ProgressListener progressListener,
                            CompleteListener completeListener) {
        if (profile == null) {
            completeListener.onError(new IllegalArgumentException("profile is null"));
            return;
        }
        if (urlIndex < 0 || urlIndex >= profile.downloadUrls().size()) {
            completeListener.onError(new IndexOutOfBoundsException(
                    "urlIndex=" + urlIndex + " out of range [0, " + profile.downloadUrls().size() + ")"));
            return;
        }

        MediaType hintType = overrideType != null ? overrideType : profile.mediaTypeAt(urlIndex);

        String url = profile.downloadUrls().get(urlIndex);
        String authorName = profile.authorNickname();
        String authorPart = (authorName != null && !authorName.isEmpty())
                ? (sanitizeFileName(authorName) + "_")
                : "";
        String descPart = profile.desc().isEmpty()
                ? ("douyin_" + profile.awemeId())
                : profile.desc();
        String baseName = authorPart + descPart;

        // 如果有多个文件，添加索引区分
        int totalUrls = profile.downloadUrls().size();
        if (totalUrls > 1) {
            baseName = baseName + "_" + (urlIndex + 1);
        }

        // extension 先猜一个，最后根据 Content-Type 覆盖
        String ext = inferExtensionGuess(url);
        String filename = baseName + ext;

        try {
            // 下载前先探一次 Content-Type，决定真实类型
            MediaType realType = probeContentType(url, cookie, hintType);
            ext = (realType == MediaType.VIDEO || realType == MediaType.LIVE_VIDEO)
                    ? ".mp4"
                    : (ext.contains("png") ? ".png" : ".jpg");
            filename = baseName + ext;
            String savedPath = saveToMediaStore(url, filename, realType, cookie, progressListener);
            Log.d(TAG, "saved " + realType + " to " + savedPath);
            completeListener.onSuccess(savedPath);
        } catch (Throwable t) {
            Log.e(TAG, "download failed: " + t.getMessage(), t);
            completeListener.onError(t);
        }
    }

    /**
     * 媒体类型推断 + 文件扩展名推断（根据 URL 猜）
     */
    private String inferExtensionGuess(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".mp4") || lower.contains("video")) return ".mp4";
        if (lower.contains(".mov")) return ".mov";
        if (lower.contains(".webp")) return ".webp";
        if (lower.contains(".png")) return ".png";
        return ".jpg";
    }

    /**
     * HEAD 请求探测 Content-Type，决定真实媒体类型。
     * 这是修复 Bug3（静态封面 URL 实际是 JPEG 但扩展名是 .mp4）的关键。
     * 当 hintType 为 LIVE_VIDEO 时，Content-Type 通常是 video/*（mp4），
     * 我们不能把它降级成普通 VIDEO，必须保留 LIVE_VIDEO 标识以保证写入
     * LivePicturesMovies/ 而不是 Movies/。
     */
    private MediaType probeContentType(String url, String cookie, MediaType hintType) {
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .method("HEAD", null)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Referer", DEFAULT_REFERER)
                .header("Accept-Encoding", "identity")
                .header("Cookie", cookie)
                .build();
        try (okhttp3.Response response = okHttp.newCall(request).execute()) {
            String ct = response.header("Content-Type", "").toLowerCase();
            Log.d(TAG, "probeContentType url=" + url + " ct=" + ct);
            if (ct.startsWith("video/")) {
                return hintType == MediaType.LIVE_VIDEO ? MediaType.LIVE_VIDEO : MediaType.VIDEO;
            }
            if (ct.startsWith("image/")) return MediaType.IMAGE;
        } catch (Exception e) {
            Log.w(TAG, "probe failed: " + e.getMessage());
        }
        // 兜底：用提示类型
        return hintType;
    }

    /**
     * 保存到 MediaStore，Android 10+ 用 MediaStore，<10 用传统 file API。
     * 关键：下载请求带 Referer，避免 CDN 返回 403。
     */
    private String saveToMediaStore(String url,
                                    String filename,
                                    MediaType mediaType,
                                    String cookie,
                                    ProgressListener progressListener) throws Exception {
        HttpDownloader httpDownloader = new HttpDownloader(okHttp, DEFAULT_USER_AGENT, DEFAULT_REFERER, cookie);
        File tmp;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: 直接写到 MediaStore 输出流
            return writeToMediaStoreStream(url, filename, mediaType, cookie, progressListener);
        } else {
            // Android 9-: 写到 cache 目录再复制
            tmp = new File(appContext.getCacheDir(), filename);
            httpDownloader.download(url, tmp,
                    (progress, downloaded, total) -> {
                        if (progressListener != null) progressListener.onProgress(progress, downloaded, total);
                    }, (mediaType == MediaType.VIDEO || mediaType == MediaType.LIVE_VIDEO)
                        ? HttpDownloader.ExpectedContent.VIDEO
                        : HttpDownloader.ExpectedContent.IMAGE);
            String savedPath = copyToPublicDir(tmp, filename, mediaType);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return savedPath;
        }
    }

    private String writeToMediaStoreStream(String url,
                                          String filename,
                                          MediaType mediaType,
                                          String cookie,
                                          ProgressListener progressListener) throws Exception {
        Uri itemUri = null;
        boolean downloadSuccess = false;
        String relativePath;

        // 视频统一放 Movies/，图片统一放 Pictures/。
        // 注：MediaStore.Video.Media collection 会强制把 RELATIVE_PATH 顶层规范成 Movies/，
        //     即便指定 Pictures/... 也会被改写到 Movies/...，所以视频类直接写 Movies/ 才一致。
        if (mediaType == MediaType.VIDEO || mediaType == MediaType.LIVE_VIDEO) {
            relativePath = mediaType == MediaType.VIDEO
                    ? Environment.DIRECTORY_MOVIES + "/DouyinDownloader"
                    : Environment.DIRECTORY_MOVIES + "/DouyinDownloader/LivePicturesMovies";
        } else {
            relativePath = Environment.DIRECTORY_PICTURES + "/DouyinDownloader/Pictures";
        }

        // 确定 ContentResolver collection
        Uri collection;
        if (mediaType == MediaType.VIDEO || mediaType == MediaType.LIVE_VIDEO) {
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }

        try {
            // 创建初始的 ContentValues
            ContentValues initialValues = new ContentValues();
            initialValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            initialValues.put(MediaStore.MediaColumns.MIME_TYPE, inferMimeType(filename, mediaType));
            initialValues.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            initialValues.put(MediaStore.MediaColumns.IS_PENDING, 1);

            itemUri = appContext.getContentResolver().insert(collection, initialValues);
            if (itemUri == null) throw new IllegalStateException("MediaStore insert failed");
            Log.d(TAG, "Created pending entry: " + itemUri + " for file: " + filename);

            // 下载请求必须带 Cookie（Bug4 修复），否则 CDN 返回 403
            okhttp3.Request.Builder reqBuilder = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Referer", DEFAULT_REFERER)
                    .header("Accept-Encoding", "identity");
            if (!TextUtils.isEmpty(cookie)) {
                reqBuilder.header("Cookie", cookie);
            }

            long total = -1;
            long downloaded = 0;
            byte[] buf = new byte[8192];

            try (okhttp3.Response response = okHttp.newCall(reqBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new java.io.IOException("HTTP " + response.code() + " - Cookie may be missing or expired");
                }
                total = response.body().contentLength();
                try (OutputStream out = appContext.getContentResolver().openOutputStream(itemUri);
                     java.io.InputStream in = response.body().byteStream()) {
                    if (out == null) {
                        throw new IllegalStateException("openOutputStream returned null");
                    }
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (progressListener != null && total > 0) {
                            int pct = (int) ((downloaded * 100L) / total);
                            progressListener.onProgress(pct, downloaded, total);
                        }
                    }
                    out.flush();
                }
            }

            downloadSuccess = true;
            Log.d(TAG, "Download completed: " + downloaded + " bytes");

            // 输出 MediaStore 实际存储的 RELATIVE_PATH，定位"读不到下载记录"的问题
            try (android.database.Cursor c = appContext.getContentResolver().query(
                    itemUri,
                    new String[]{MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int rpIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
                    int dnIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                    Log.d(TAG, "MediaStore ACTUAL relative_path=[" + c.getString(rpIdx)
                            + "] display_name=[" + c.getString(dnIdx) + "]");
                }
            } catch (Throwable t2) {
                Log.w(TAG, "Failed to read back RELATIVE_PATH: " + t2.getMessage());
            }
        } catch (Throwable t) {
            Log.e(TAG, "Download failed: " + t.getMessage());
            throw t;
        } finally {
            // 确保 IS_PENDING 状态被正确处理
            if (itemUri != null) {
                try {
                    // 创建新的 ContentValues 用于更新
                    ContentValues finalValues = new ContentValues();
                    if (downloadSuccess) {
                        finalValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        int updated = appContext.getContentResolver().update(itemUri, finalValues, null, null);
                        if (updated > 0) {
                            Log.d(TAG, "IS_PENDING -> 0, rows updated: " + updated);
                        } else {
                            Log.w(TAG, "IS_PENDING update returned 0 rows, entry may have been deleted or not found");
                        }
                    } else {
                        // 下载失败，删除 pending 条目
                        int deleted = appContext.getContentResolver().delete(itemUri, null, null);
                        Log.d(TAG, "Deleted pending entry, rows deleted: " + deleted);
                    }
                } catch (Throwable cleanupError) {
                    Log.e(TAG, "Cleanup failed: " + cleanupError.getMessage());
                    if (!downloadSuccess) {
                        throw new IllegalStateException("Failed to cleanup pending entry", cleanupError);
                    }
                }
            }
        }
        return itemUri != null ? itemUri.toString() : null;
    }

    private String copyToPublicDir(File src, String filename, MediaType mediaType) throws Exception {
        // 视频统一放到 Movies/DouyinDownloader/ 下（与 MediaStore.Video.Media 一致，
        // 因为系统对 Video 类型会强制把 RELATIVE_PATH 顶层规范成 Movies/）；
        // 图片 / Live静态封面放到 Pictures/DouyinDownloader/Pictures/。
        File publicDir;
        if (mediaType == MediaType.VIDEO) {
            publicDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "DouyinDownloader");
        } else if (mediaType == MediaType.LIVE_VIDEO) {
            publicDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "DouyinDownloader/LivePicturesMovies");
        } else {
            publicDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "DouyinDownloader/Pictures");
        }
        if (!publicDir.exists() && !publicDir.mkdirs()) {
            throw new IllegalStateException("mkdirs failed: " + publicDir);
        }
        File dst = new File(publicDir, filename);
        try (FileInputStream in = new FileInputStream(src);
             OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        }
        return dst.getAbsolutePath();
    }

    private String inferMimeType(String filename, MediaType mediaType) {
        String lower = filename.toLowerCase();
        if (mediaType == MediaType.VIDEO || mediaType == MediaType.LIVE_VIDEO) {
            return lower.endsWith(".mov") ? "video/quicktime" : "video/mp4";
        } else {
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".webp")) return "image/webp";
            return "image/jpeg";
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "douyin";
        String n = name.trim();
        if (n.isEmpty()) return "douyin";
        // 替换非法文件名字符
        n = n.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        // 截断到 80 字符
        if (n.length() > 80) n = n.substring(0, 80);
        return n;
    }
}