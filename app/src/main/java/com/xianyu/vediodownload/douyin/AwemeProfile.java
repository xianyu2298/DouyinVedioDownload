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
  /** 清晰度标注（如 "720p · 30fps"）。抖音作品为空字符串；仅供 UI 如实展示画质。 */
  private final String qualityLabel;
  /** 抖音曲库背景音乐标题（原声作品可能为空）。 */
  private final String musicTitle;
  /** 背景音乐作者 / 原声归属，可能为空。 */
  private final String musicAuthor;
  /** 背景音乐直链（m4a）。原声作品常为空，此时只能从视频音轨提取。 */
  private final String musicUrl;
  /**
   * 该作品是否使用「原声」而非曲库音乐。
   *
   * <p>为 true 时说明博主没有添加背景音乐，视频音轨本身就是纯原声；
   * 为 false 且 musicUrl 非空时，视频音轨是「人声 + 背景音乐」的混音。
   */
  private final boolean musicIsOriginal;

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
    this(
        platform,
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
        "");
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
      List<MediaType> imageMediaTypes,
      String qualityLabel) {
    this(
        platform,
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
        qualityLabel,
        "",
        "",
        "",
        false);
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
      List<MediaType> imageMediaTypes,
      String qualityLabel,
      String musicTitle,
      String musicAuthor,
      String musicUrl,
      boolean musicIsOriginal) {
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
    this.qualityLabel = qualityLabel == null ? "" : qualityLabel;
    this.musicTitle = musicTitle == null ? "" : musicTitle;
    this.musicAuthor = musicAuthor == null ? "" : musicAuthor;
    this.musicUrl = musicUrl == null ? "" : musicUrl;
    this.musicIsOriginal = musicIsOriginal;
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

  /** 清晰度标注，如 "720p · 30fps"；无可用信息时为空字符串。 */
  public String qualityLabel() {
    return qualityLabel;
  }

  /** 背景音乐标题；原声作品可能为空。 */
  public String musicTitle() {
    return musicTitle;
  }

  /** 背景音乐作者 / 原声归属，可能为空。 */
  public String musicAuthor() {
    return musicAuthor;
  }

  /** 背景音乐直链（m4a）；为空表示只能从视频音轨提取。 */
  public String musicUrl() {
    return musicUrl;
  }

  /** 是否存在独立背景音乐直链。 */
  public boolean hasBackgroundMusic() {
    return !musicUrl.isEmpty();
  }

  /**
   * 视频音轨是否已经是纯原声。
   *
   * <p>成立条件：作品使用原声（{@code music.is_original}）或压根没有背景音乐直链——
   * 这两种情况下音轨里不会有曲库 BGM 混入。
   */
  public boolean videoTrackIsPureOriginal() {
    return musicIsOriginal || musicUrl.isEmpty();
  }

  /** 作品是否使用了「原声」（此时 musicUrl 指向的是原声录音，而不是曲库 BGM）。 */
  public boolean musicIsOriginal() {
    return musicIsOriginal;
  }

  public MediaType mediaTypeAt(int index) {
    if (index < 0 || index >= imageMediaTypes.size()) {
      return MediaType.IMAGE;
    }
    MediaType type = imageMediaTypes.get(index);
    return type == null ? MediaType.IMAGE : type;
  }
}
