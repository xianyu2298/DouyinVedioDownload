package com.xianyu.vediodownload.kuaishou;

import com.fasterxml.jackson.databind.JsonNode;
import com.xianyu.vediodownload.douyin.AwemeProfile;
import com.xianyu.vediodownload.douyin.MediaType;
import com.xianyu.vediodownload.douyin.exception.FileDownloadException;
import com.xianyu.vediodownload.model.Platform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 把快手 H5 分享页 {@code window.INIT_STATE} 里的 {@code photo} 节点映射为 {@link AwemeProfile}。
 *
 * <p>三种作品形态的处理方式：
 * <ul>
 *   <li>{@code VIDEO}：取 {@code manifest.adaptationSet[].representation[]}，按
 *       分辨率 → 帧率 → 码率 排序，最高档放首位，其余作为兜底；{@code mainMvUrls} 最后兜底。
 *   <li>{@code HORIZONTAL_ATLAS} / {@code VERTICAL_ATLAS}：{@code ext_params.atlas.cdn[0]} +
 *       {@code atlas.list[i]} 拼接，得到整组原图（webp）。
 *   <li>{@code SINGLE_PICTURE}：取 {@code coverUrls[0]}。
 * </ul>
 *
 * <p>注意：快手 Web 端（含登录态）只下发 720p 一档，这是服务端策略，客户端无法突破。
 * 解析层如实返回 qualityLabel，由 UI 展示，不做任何"伪原画"包装。
 */
final class KuaishouPhotoParser {

  private KuaishouPhotoParser() {}

  static AwemeProfile parse(JsonNode photo, String photoIdToken) throws FileDownloadException {
    if (photo == null || !photo.isObject()) {
      throw new FileDownloadException("kuaishou photo node is missing");
    }
    String photoType = photo.path("photoType").asText("").trim().toUpperCase(Locale.ROOT);
    String caption = photo.path("caption").asText("");
    String authorName = photo.path("userName").asText("");
    String avatar = photo.path("headUrl").asText("");
    String authorId = photo.path("userEid").asText("");
    if (authorId.isEmpty()) {
      authorId = photo.path("userId").asText("");
    }
    long createTimeMs = photo.path("timestamp").asLong(0L);
    long createTime = createTimeMs > 0 ? createTimeMs / 1000L : 0L;

    List<String> coverUrls = collectCoverUrls(photo);
    String thumbnail = coverUrls.isEmpty() ? "" : coverUrls.get(0);

    if ("VIDEO".equals(photoType)) {
      VideoQuality best = pickBestVideo(photo);
      if (best == null || best.urls.isEmpty()) {
        throw new FileDownloadException("kuaishou video url is empty");
      }
      return new AwemeProfile(
          Platform.KUAISHOU,
          photoIdToken,
          MediaType.VIDEO,
          caption,
          createTime,
          authorName,
          authorId,
          avatar,
          "",
          thumbnail,
          coverUrls,
          best.urls,
          Collections.emptyList(),
          best.label);
    }

    // 图集 / 单图（photoType 缺失时也走这里，按 static image 处理）
    List<String> imageUrls = collectImageUrls(photo, photoType);
    if (imageUrls.isEmpty()) {
      throw new FileDownloadException("kuaishou image url is empty");
    }
    List<MediaType> types = new ArrayList<>(imageUrls.size());
    for (int i = 0; i < imageUrls.size(); i++) {
      types.add(MediaType.IMAGE);
    }
    String label = imageUrls.size() > 1 ? (imageUrls.size() + " 张图") : "";
    return new AwemeProfile(
        Platform.KUAISHOU,
        photoIdToken,
        MediaType.IMAGE,
        caption,
        createTime,
        authorName,
        authorId,
        avatar,
        "",
        thumbnail,
        coverUrls,
        imageUrls,
        types,
        label);
  }

  /** 视频候选（内部排序用）。 */
  private static final class VideoQuality {
    final int width;
    final int height;
    final int fps;
    final int bitRate;
    final String label;
    final List<String> urls;

    VideoQuality(int width, int height, int fps, int bitRate, String label, List<String> urls) {
      this.width = width;
      this.height = height;
      this.fps = fps;
      this.bitRate = bitRate;
      this.label = label;
      this.urls = urls;
    }
  }

  private static VideoQuality pickBestVideo(JsonNode photo) {
    List<VideoQuality> candidates = new ArrayList<>();
    JsonNode adaptationSets = photo.path("manifest").path("adaptationSet");
    if (adaptationSets.isArray()) {
      for (JsonNode set : adaptationSets) {
        JsonNode reps = set.path("representation");
        if (!reps.isArray()) {
          continue;
        }
        for (JsonNode rep : reps) {
          LinkedHashSet<String> urls = new LinkedHashSet<>();
          String url = rep.path("url").asText("").trim();
          if (!url.isEmpty()) {
            urls.add(url);
          }
          JsonNode backups = rep.path("backupUrl");
          if (backups.isArray()) {
            for (JsonNode b : backups) {
              String bu = b.asText("").trim();
              if (!bu.isEmpty()) {
                urls.add(bu);
              }
            }
          }
          if (urls.isEmpty()) {
            continue;
          }
          int fps = (int) Math.round(rep.path("frameRate").asDouble(0));
          candidates.add(
              new VideoQuality(
                  rep.path("width").asInt(0),
                  rep.path("height").asInt(0),
                  fps,
                  rep.path("avgBitrate").asInt(0),
                  buildQualityLabel(rep.path("qualityType").asText(""), fps),
                  new ArrayList<>(urls)));
        }
      }
    }

    if (!candidates.isEmpty()) {
      candidates.sort(
          (a, b) -> {
            int cmp = Integer.compare(Math.max(b.width, b.height), Math.max(a.width, a.height));
            if (cmp != 0) {
              return cmp;
            }
            cmp = Integer.compare(b.fps, a.fps);
            if (cmp != 0) {
              return cmp;
            }
            return Integer.compare(b.bitRate, a.bitRate);
          });

      // 把最高档排第一，其余档位按顺序追加，作为下载失败时的兜底链
      LinkedHashSet<String> ordered = new LinkedHashSet<>();
      for (VideoQuality q : candidates) {
        ordered.addAll(q.urls);
      }
      VideoQuality best = candidates.get(0);
      return new VideoQuality(
          best.width, best.height, best.fps, best.bitRate, best.label, new ArrayList<>(ordered));
    }

    // 兜底：mainMvUrls（低码率流，通常也能播）
    LinkedHashSet<String> fallback = new LinkedHashSet<>();
    JsonNode mainMvUrls = photo.path("mainMvUrls");
    if (mainMvUrls.isArray()) {
      for (JsonNode item : mainMvUrls) {
        String u = item.path("url").asText("").trim();
        if (!u.isEmpty()) {
          fallback.add(u);
        }
      }
    }
    if (fallback.isEmpty()) {
      return null;
    }
    return new VideoQuality(0, 0, 0, 0, "", new ArrayList<>(fallback));
  }

  private static String buildQualityLabel(String qualityType, int fps) {
    String quality = qualityType == null ? "" : qualityType.trim();
    if (quality.isEmpty() && fps <= 0) {
      return "";
    }
    if (fps <= 0) {
      return quality;
    }
    if (quality.isEmpty()) {
      return fps + "fps";
    }
    return quality + " · " + fps + "fps";
  }

  private static List<String> collectImageUrls(JsonNode photo, String photoType) {
    boolean atlas = photoType.contains("ATLAS");
    if (atlas) {
      List<String> built = buildAtlasUrls(photo);
      if (!built.isEmpty()) {
        return built;
      }
    }
    // 单图 / 图集兜底：coverUrls（原图或封面大图）
    List<String> covers = collectCoverUrls(photo);
    if (!covers.isEmpty()) {
      return new ArrayList<>(covers.subList(0, 1));
    }
    return Collections.emptyList();
  }

  private static List<String> buildAtlasUrls(JsonNode photo) {
    JsonNode atlas = photo.path("ext_params").path("atlas");
    if (!atlas.isObject()) {
      return Collections.emptyList();
    }
    String cdn = "";
    JsonNode cdnNode = atlas.path("cdn");
    if (cdnNode.isArray() && !cdnNode.isEmpty()) {
      cdn = cdnNode.get(0).asText("").trim();
    }
    if (cdn.isEmpty()) {
      JsonNode cdnList = atlas.path("cdnList");
      if (cdnList.isArray() && !cdnList.isEmpty()) {
        cdn = cdnList.get(0).path("cdn").asText("").trim();
      }
    }
    JsonNode list = atlas.path("list");
    if (cdn.isEmpty() || !list.isArray()) {
      return Collections.emptyList();
    }
    List<String> urls = new ArrayList<>(list.size());
    for (JsonNode path : list) {
      String p = path.asText("").trim();
      if (p.isEmpty()) {
        continue;
      }
      String url = p.startsWith("http") ? p : ("https://" + cdn + (p.startsWith("/") ? p : "/" + p));
      urls.add(url);
    }
    return urls;
  }

  private static List<String> collectCoverUrls(JsonNode photo) {
    LinkedHashSet<String> urls = new LinkedHashSet<>();
    JsonNode covers = photo.path("coverUrls");
    if (covers.isArray()) {
      for (JsonNode c : covers) {
        String u = c.path("url").asText("").trim();
        if (!u.isEmpty()) {
          urls.add(u);
        }
      }
    }
    JsonNode webpCovers = photo.path("webpCoverUrls");
    if (webpCovers.isArray()) {
      for (JsonNode c : webpCovers) {
        String u = c.path("url").asText("").trim();
        if (!u.isEmpty()) {
          urls.add(u);
        }
      }
    }
    return new ArrayList<>(urls);
  }
}
