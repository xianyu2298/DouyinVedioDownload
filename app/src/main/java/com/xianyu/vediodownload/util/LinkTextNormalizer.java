package com.xianyu.vediodownload.util;

/**
 * 分享文本归一化。
 *
 * <p>用户复制的分享文案在真实场景里会被改形，常见三类：
 * <ul>
 *   <li>输入法把半角符号转成全角（{@code https:／／v.douyin.com}）——直接导致 URL 正则匹配不到；
 *   <li>从微信 / QQ 转发时混入零宽字符（U+200B / U+200C / U+200D / U+FEFF）——肉眼不可见，
 *       但会切断 URL；
 *   <li>全角字母数字（如 {@code ｖ．ｄｏｕｙｉｎ．ｃｏｍ}）——域名校验失败。
 * </ul>
 *
 * <p>这里统一做一次「全角 ASCII → 半角 ASCII + 去零宽 + 全角空格转普通空格」，
 * 再做后续的 URL 提取。只用于解析输入，不改变用户输入框里显示的内容。
 */
public final class LinkTextNormalizer {

  private LinkTextNormalizer() {}

  public static String normalize(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\u3000') {
        // 全角空格
        sb.append(' ');
        continue;
      }
      if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
        // 零宽字符：直接丢弃
        continue;
      }
      if (c >= '\uFF01' && c <= '\uFF5E') {
        // 全角 ASCII 区间，整体平移 0xFEE0 得到对应半角字符
        sb.append((char) (c - 0xFEE0));
        continue;
      }
      sb.append(c);
    }
    return sb.toString();
  }
}
