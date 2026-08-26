package com.xianyu.vediodownload.downloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HttpDownloader {
  private final OkHttpClient client;
  private final PlatformRequestContext requestContext;

  public HttpDownloader(OkHttpClient client, String userAgent, String referer) {
    this(client, userAgent, referer, "");
  }

  public HttpDownloader(OkHttpClient client, String userAgent, String referer, String cookie) {
    this.client = client;
    this.requestContext =
        new PlatformRequestContext(
            userAgent, referer, cookie, java.util.List.of("douyin.com", "iesdouyin.com"));
  }

  public HttpDownloader(OkHttpClient client, PlatformRequestContext requestContext) {
    this.client = client;
    this.requestContext =
        requestContext == null
            ? new PlatformRequestContext("", "", "", java.util.List.of())
            : requestContext;
  }

  private static byte[] readHeader(File file, int length) throws IOException {
    byte[] buffer = new byte[length];
    try (InputStream inputStream = new FileInputStream(file)) {
      int read = inputStream.read(buffer);
      if (read <= 0) {
        return new byte[0];
      }
      if (read == length) {
        return buffer;
      }
      byte[] exact = new byte[read];
      System.arraycopy(buffer, 0, exact, 0, read);
      return exact;
    }
  }

  private static boolean startsWith(byte[] value, byte... prefix) {
    if (value.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (value[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  public File download(String url, File outputFile, ProgressCallback callback) throws IOException {
    return download(url, outputFile, callback, null);
  }

  public File download(
      String url, File outputFile, ProgressCallback callback, ExpectedContent expectedContent)
      throws IOException {
    if (outputFile.getParentFile() != null) {
      outputFile.getParentFile().mkdirs();
    }

    long expectedLength = fetchContentLength(url);

    Request.Builder requestBuilder = new Request.Builder().url(url).get();
    applyHeaders(requestBuilder, url);

    try (Response response = client.newCall(requestBuilder.build()).execute()) {
      if (!response.isSuccessful()) {
        throw new IOException("Download failed: " + response.code());
      }
      if (response.body() == null) {
        throw new IOException("Download failed: empty body");
      }
      validateResponseHeaders(response, expectedContent);

      long totalBytes = response.body().contentLength();
      if (totalBytes <= 0) {
        totalBytes = expectedLength;
      }
      long downloadedBytes = 0;

      File tmpFile = new File(outputFile.getAbsolutePath() + ".part");
      if (tmpFile.exists()) {
        tmpFile.delete();
      }

      if (callback != null && totalBytes > 0) {
        callback.onProgress(0, 0, totalBytes);
      }

      try (InputStream input = response.body().byteStream();
          FileOutputStream output = new FileOutputStream(tmpFile)) {

        byte[] buffer = new byte[8192];
        int bytesRead;

        while ((bytesRead = input.read(buffer)) != -1) {
          output.write(buffer, 0, bytesRead);
          downloadedBytes += bytesRead;

          if (callback != null && totalBytes > 0) {
            int progress = (int) ((downloadedBytes * 100) / totalBytes);
            if (progress <= 0 && downloadedBytes > 0) {
              progress = 1;
            }
            callback.onProgress(progress, downloadedBytes, totalBytes);
          } else if (callback != null && downloadedBytes > 0) {
            callback.onProgress(1, downloadedBytes, totalBytes);
          }
        }
      }
    }

    // Replace output atomically.
    File tmpFile = new File(outputFile.getAbsolutePath() + ".part");
    if (!tmpFile.exists()) {
      throw new IOException("Download failed: temp file missing");
    }
    if (outputFile.exists()) {
      outputFile.delete();
    }
    if (!tmpFile.renameTo(outputFile)) {
      throw new IOException("Download failed: cannot rename temp file");
    }
    validateFileSignature(outputFile, expectedContent);

    if (callback != null) {
      callback.onProgress(100, outputFile.length(), outputFile.length());
    }

    return outputFile;
  }

  private long fetchContentLength(String url) {
    try {
      Request.Builder rb = new Request.Builder().url(url).head();
      applyHeaders(rb, url);
      try (Response response = client.newCall(rb.build()).execute()) {
        if (!response.isSuccessful()) {
          return -1;
        }
        String len = response.header("Content-Length");
        if (len == null) {
          return -1;
        }
        return Long.parseLong(len);
      }
    } catch (Exception ignored) {
      return -1;
    }
  }

  private void applyHeaders(Request.Builder rb, String url) {
    if (!requestContext.userAgent().isEmpty()) {
      rb.header("User-Agent", requestContext.userAgent());
    }
    if (!requestContext.referer().isEmpty()) {
      rb.header("Referer", requestContext.referer());
    }
    if (requestContext.shouldAttachCookie(url)) {
      rb.header("Cookie", requestContext.cookie());
    }
    // Avoid transparent gzip which may hide Content-Length.
    rb.header("Accept-Encoding", "identity");
  }

  private void validateResponseHeaders(Response response, ExpectedContent expectedContent)
      throws IOException {
    if (expectedContent == null) {
      return;
    }
    String contentType = response.header("Content-Type", "");
    if (!expectedContent.isCompatibleContentType(contentType)) {
      throw new IOException("Unexpected content type: " + contentType);
    }
  }

  private void validateFileSignature(File file, ExpectedContent expectedContent)
      throws IOException {
    if (expectedContent == null) {
      return;
    }
    if (!expectedContent.matchesSignature(file)) {
      throw new IOException("Unexpected file signature");
    }
  }

    public enum ExpectedContent {
        AUTO {
            @Override
            boolean isCompatibleContentType(String contentType) {
                return true;
            }

            @Override
            boolean matchesSignature(File file) throws IOException {
                return true;
            }
        },
        IMAGE {
      @Override
      boolean isCompatibleContentType(String contentType) {
        return isBinaryType(contentType, "image/");
      }

      @Override
      boolean matchesSignature(File file) throws IOException {
        byte[] header = readHeader(file, 12);
        return startsWith(header, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF)
            || startsWith(header, (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47)
            || startsWith(header, (byte) 0x47, (byte) 0x49, (byte) 0x46, (byte) 0x38)
            || (startsWith(header, (byte) 0x52, (byte) 0x49, (byte) 0x46, (byte) 0x46)
                && header.length >= 12
                && header[8] == 0x57
                && header[9] == 0x45
                && header[10] == 0x42
                && header[11] == 0x50);
      }
    },
    VIDEO {
      @Override
      boolean isCompatibleContentType(String contentType) {
        return isBinaryType(contentType, "video/");
      }

      @Override
      boolean matchesSignature(File file) throws IOException {
        byte[] header = readHeader(file, 16);
        return header.length >= 8
            && header[4] == 0x66
            && header[5] == 0x74
            && header[6] == 0x79
            && header[7] == 0x70;
      }
    };

    static boolean isBinaryType(String contentType, String expectedPrefix) {
      if (contentType == null || contentType.isBlank()) {
        return true;
      }
      String normalized = contentType.toLowerCase(Locale.ROOT);
      if (normalized.startsWith(expectedPrefix)) {
        return true;
      }
      if (normalized.startsWith("text/")
          || normalized.contains("json")
          || normalized.contains("html")
          || normalized.contains("xml")) {
        return false;
      }
      return normalized.startsWith("application/octet-stream");
    }

    abstract boolean isCompatibleContentType(String contentType);

    abstract boolean matchesSignature(File file) throws IOException;
  }

  public interface ProgressCallback {
    void onProgress(int progress, long downloaded, long total);
  }
}
