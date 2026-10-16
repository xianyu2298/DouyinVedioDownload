package com.xianyu.vediodownload.util;

/**
 * 支持自定义字母表的 Base64 编码器（兼容 Android 7.0+，无 java.util.Base64 依赖）。
 *
 * <p>用于新版 a_bogus 签名：s4 字母表编码最终签名，s3 字母表编码 User-Agent 摘要链。
 * 注意 s4 字母表包含 "/" 与 "=" 填充，发送前需做百分号编码（见 WebSignGenerator.quote）。
 *
 * <p>算法逆向自 bdms.js v1.0.1.19-fix.01，仅供个人技术学习使用。
 */
public final class CustomBase64 {

  private CustomBase64() {}

  /** 使用指定 64 字符字母表对字节数组做标准 Base64 编码。 */
  public static String encode(byte[] data, String alphabet) {
    if (alphabet.length() != 64) {
      throw new IllegalArgumentException("Base64 alphabet must contain 64 characters");
    }
    StringBuilder out = new StringBuilder(((data.length + 2) / 3) * 4);
    for (int offset = 0; offset < data.length; offset += 3) {
      int remaining = data.length - offset;
      int block =
          ((data[offset] & 0xFF) << 16)
              | (remaining > 1 ? (data[offset + 1] & 0xFF) << 8 : 0)
              | (remaining > 2 ? data[offset + 2] & 0xFF : 0);
      out.append(alphabet.charAt((block >>> 18) & 0x3F));
      out.append(alphabet.charAt((block >>> 12) & 0x3F));
      out.append(remaining > 1 ? alphabet.charAt((block >>> 6) & 0x3F) : '=');
      out.append(remaining > 2 ? alphabet.charAt(block & 0x3F) : '=');
    }
    return out.toString();
  }
}
