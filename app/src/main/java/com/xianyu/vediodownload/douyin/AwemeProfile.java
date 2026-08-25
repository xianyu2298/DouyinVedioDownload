package com.xianyu.vediodownload.douyin;

import com.xianyu.vediodownload.model.Platform;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

public class AwemeProfile implements Serializable {
  private final Platform platform;
  private final String awemeId;
  private final MediaType mediaType;
  private final String desc;
  private final long createTime;
  private final String authorNickname;
  private final String authorSecUserId;
  private final String authorAvatarUrl;
  private final String collectionTitle;
  private final String thumbnailUrl;
  private final List<String> thumbnailUrls;
  private final List<String> downloadUrls;
  private final List<MediaType> imageMediaTypes;

  public AwemeProfile(
      String awemeId,
      MediaType mediaType,
      String desc,
      long createTime,
      String authorNickname,
      String authorSecUserId,
      String thumbnailUrl,
      List<String> thumbnailUrls) {
    this(
        Platform.DOUYIN,
        awemeId,
        mediaType,
        desc,
        createTime,
        authorNickname,
        authorSecUserId,
        "",          // authorAvatarUrl
        "",          // collectionTitle
        thumbnailUrl,
        thumbnailUrls,
        Collections.emptyList(),
        Collections.emptyList());
  }

  public AwemeProfile(
      String awemeId,
      MediaType mediaType,
      String desc,
      long createTime,
      String authorNickname,
      String authorSecUserId,
      String thumbnailUrl,
      List<String> thumbnailUrls,
      List<String> downloadUrls) {
    this(
        Platform.DOUYIN,
        awemeId,
        mediaType,
        desc,
        createTime,
        authorNickname,
        authorSecUserId,
        "",          // authorAvatarUrl
        "",          // collectionTitle
        thumbnailUrl,
        thumbnailUrls,
        downloadUrls,
        Collections.emptyList());
  }

  public AwemeProfile(
      Platform platform,
      String awemeId,
      MediaType mediaType,
      String desc,
      long createTime,
      String authorNickname,
      String authorSecUserId,
      String authorAvatarUrl,
      String collectionTitle,
      String thumbnailUrl,
      List<String> thumbnailUrls,
      List<String> downloadUrls,
      List<MediaType> imageMediaTypes) {
    this.platform = platform == null ? Platform.DOUYIN : platform;
    this.awemeId = awemeId;
    this.mediaType = mediaType;
    this.desc = desc;
    this.createTime = createTime;
    this.authorNickname = authorNickname;
    this.authorSecUserId = authorSecUserId;
    this.authorAvatarUrl = authorAvatarUrl == null ? "" : authorAvatarUrl;
    this.collectionTitle = collectionTitle == null ? "" : collectionTitle;
    this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
    this.thumbnailUrls =
        thumbnailUrls == null ? Collections.emptyList() : List.copyOf(thumbnailUrls);
    this.downloadUrls = downloadUrls == null ? Collections.emptyList() : List.copyOf(downloadUrls);
    this.imageMediaTypes =
        imageMediaTypes == null ? Collections.emptyList() : List.copyOf(imageMediaTypes);
  }

  public AwemeProfile(
      String awemeId,
      MediaType mediaType,
      String desc,
      long createTime,
      String authorNickname,
      String authorSecUserId,
      String authorAvatarUrl,
      String collectionTitle,
      String thumbnailUrl,
      List<String> thumbnailUrls,
      List<String> downloadUrls,
      List<MediaType> imageMediaTypes) {
    this(
        Platform.DOUYIN,
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
        imageMediaTypes);
  }

  public Platform platform() {
    return platform;
  }

  public String awemeId() {
    return awemeId;
  }

  public MediaType mediaType() {
    return mediaType;
  }

  public String desc() {
    return desc;
  }

  public long createTime() {
    return createTime;
  }

  public String authorNickname() {
    return authorNickname;
  }

  public String authorSecUserId() {
    return authorSecUserId;
  }

  public String authorAvatarUrl() {
    return authorAvatarUrl;
  }

  public String collectionTitle() {
    return collectionTitle;
  }

  public String thumbnailUrl() {
    return thumbnailUrl;
  }

  public List<String> thumbnailUrls() {
    return thumbnailUrls;
  }

  public List<String> downloadUrls() {
    return downloadUrls;
  }

  public List<MediaType> imageMediaTypes() {
    return imageMediaTypes;
  }

  public MediaType mediaTypeAt(int index) {
    if (index < 0 || index >= imageMediaTypes.size()) {
      return MediaType.IMAGE;
    }
    MediaType type = imageMediaTypes.get(index);
    return type == null ? MediaType.IMAGE : type;
  }
}
