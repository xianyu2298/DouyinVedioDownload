package com.xianyu.vediodownload.douyin;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

final class ABogusGenerator {
  private static final String END_STRING = "cus";
  private static final String UA_KEY = "\u0000\u0001\u000e";
  private static final int[] ARGUMENTS = {0, 1, 14};
  private static final int[] REG_INIT = {
    0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600, 0xA96F30BC, 0x163138AA, 0xE38DEE4D, 0xB0FB0E4E
  };

  private static final Map<String, String> STR_MAP = new HashMap<>();

  static {
    STR_MAP.put("s0", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=");
    STR_MAP.put("s1", "Dkdpgh4ZKsQB80/Mfvw36XI1R25+WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=");
    STR_MAP.put("s2", "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=");
    STR_MAP.put("s3", "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe");
    STR_MAP.put("s4", "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe");
  }

  private final int[] uaCode;
  private final int[] browserCode;
  private final int browserLen;

  ABogusGenerator(String userAgent) {
    String ua = userAgent != null ? userAgent : "";
    this.uaCode = generateUaCode(ua);
    String browser = "1536|742|1536|864|0|0|0|0|1536|864|1536|864|1536|742|24|24|Win32";
    this.browserCode = charCodeAt(browser);
    this.browserLen = browser.length();
  }

  String getValue(String encodedUrlParams, String method) {
    String m = (method == null || method.trim().isEmpty()) ? "GET" : method;
    String string1 = generateString1(null, null, null);
    String string2 = generateString2(encodedUrlParams, m, 0L, 0L);
    return generateResult(string1 + string2, "s4");
  }

  private String generateString1(Double r1, Double r2, Double r3) {
    return fromCharCode(list1(r1)) + fromCharCode(list2(r2)) + fromCharCode(list3(r3));
  }

  private int[] list1(Double r) {
    return randomList(r, 170, 85, 1, 2, 5, 45 & 170);
  }

  private int[] list2(Double r) {
    return randomList(r, 170, 85, 1, 0, 0, 0);
  }

  private int[] list3(Double r) {
    return randomList(r, 170, 85, 1, 0, 5, 0);
  }

  private int[] randomList(Double seed, int b, int c, int d, int e, int f, int g) {
    double r = seed != null ? seed : ThreadLocalRandom.current().nextDouble() * 10000.0;
    int v1 = ((int) r) & 255;
    int v2 = ((int) r) >> 8;
    return new int[] {(v1 & b) | d, (v1 & c) | e, (v2 & b) | f, (v2 & c) | g};
  }

  private String generateString2(String urlParams, String method, long startTime, long endTime) {
    int[] base = generateString2List(urlParams, method, startTime, endTime);
    int endCheck = endCheckNum(base);

    int[] all = new int[base.length + browserCode.length + 1];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(browserCode, 0, all, base.length, browserCode.length);
    all[all.length - 1] = endCheck;

    return rc4Encrypt(fromCharCode(all), "y");
  }

  private int[] generateString2List(String urlParams, String method, long startTime, long endTime) {
    long st = startTime > 0 ? startTime : System.currentTimeMillis();
    long et = endTime > 0 ? endTime : st + ThreadLocalRandom.current().nextLong(4, 9);

    int[] paramsArray = generateParamsCode(urlParams + END_STRING);
    int[] methodArray = generateParamsCode(method + END_STRING);

    return list4(
        (int) ((et >> 24) & 255),
        paramsArray[21],
        uaCode[23],
        (int) ((et >> 16) & 255),
        paramsArray[22],
        uaCode[24],
        (int) ((et >> 8) & 255),
        (int) (et & 255),
        (int) ((st >> 24) & 255),
        (int) ((st >> 16) & 255),
        (int) ((st >> 8) & 255),
        (int) (st & 255),
        methodArray[21],
        methodArray[22],
        (int) (et / 256 / 256 / 256 / 256),
        (int) (st / 256 / 256 / 256 / 256),
        browserLen);
  }

  private int[] list4(
      int a,
      int b,
      int c,
      int d,
      int e,
      int f,
      int g,
      int h,
      int i,
      int j,
      int k,
      int m,
      int n,
      int o,
      int p,
      int q,
      int r) {
    return new int[] {
      44,
      a,
      0,
      0,
      0,
      0,
      24,
      b,
      n,
      0,
      c,
      d,
      0,
      0,
      0,
      1,
      0,
      239,
      e,
      o,
      f,
      g,
      0,
      0,
      0,
      0,
      h,
      0,
      0,
      ARGUMENTS[2],
      i,
      j,
      0,
      k,
      m,
      3,
      p,
      1,
      q,
      1,
      r,
      0,
      0,
      0
    };
  }

  private int endCheckNum(int[] data) {
    int v = 0;
    for (int n : data) {
      v ^= (n & 255);
    }
    return v & 255;
  }

  private int[] generateUaCode(String userAgent) {
    String u = rc4Encrypt(userAgent, UA_KEY);
    String encoded = generateResult(u, "s3");
    return sm3ToArray(encoded.getBytes(StandardCharsets.UTF_8));
  }

  private int[] generateParamsCode(String text) {
    int[] first = sm3ToArray(text.getBytes(StandardCharsets.UTF_8));
    byte[] firstBytes = new byte[first.length];
    for (int i = 0; i < first.length; i++) {
      firstBytes[i] = (byte) (first[i] & 255);
    }
    return sm3ToArray(firstBytes);
  }

  private int[] sm3ToArray(byte[] input) {
    byte[] digest = sm3Digest(input);
    int[] out = new int[digest.length];
    for (int i = 0; i < digest.length; i++) {
      out[i] = digest[i] & 255;
    }
    return out;
  }

  private byte[] sm3Digest(byte[] message) {
    int[] v = REG_INIT.clone();
    byte[] padded = sm3Pad(message);

    for (int offset = 0; offset < padded.length; offset += 64) {
      int[] w = new int[68];
      int[] w1 = new int[64];

      for (int j = 0; j < 16; j++) {
        int pos = offset + j * 4;
        w[j] =
            ((padded[pos] & 255) << 24)
                | ((padded[pos + 1] & 255) << 16)
                | ((padded[pos + 2] & 255) << 8)
                | (padded[pos + 3] & 255);
      }

      for (int j = 16; j < 68; j++) {
        int x = w[j - 16] ^ w[j - 9] ^ rotl(w[j - 3], 15);
        w[j] = p1(x) ^ rotl(w[j - 13], 7) ^ w[j - 6];
      }

      for (int j = 0; j < 64; j++) {
        w1[j] = w[j] ^ w[j + 4];
      }

      int a = v[0];
      int b = v[1];
      int c = v[2];
      int d = v[3];
      int e = v[4];
      int f = v[5];
      int g = v[6];
      int h = v[7];

      for (int j = 0; j < 64; j++) {
        int ss1 = rotl(rotl(a, 12) + e + rotl(t(j), j), 7);
        int ss2 = ss1 ^ rotl(a, 12);
        int tt1 = ff(j, a, b, c) + d + ss2 + w1[j];
        int tt2 = gg(j, e, f, g) + h + ss1 + w[j];

        d = c;
        c = rotl(b, 9);
        b = a;
        a = tt1;
        h = g;
        g = rotl(f, 19);
        f = e;
        e = p0(tt2);
      }

      v[0] ^= a;
      v[1] ^= b;
      v[2] ^= c;
      v[3] ^= d;
      v[4] ^= e;
      v[5] ^= f;
      v[6] ^= g;
      v[7] ^= h;
    }

    byte[] out = new byte[32];
    for (int i = 0; i < 8; i++) {
      int val = v[i];
      out[i * 4] = (byte) ((val >>> 24) & 255);
      out[i * 4 + 1] = (byte) ((val >>> 16) & 255);
      out[i * 4 + 2] = (byte) ((val >>> 8) & 255);
      out[i * 4 + 3] = (byte) (val & 255);
    }
    return out;
  }

  private byte[] sm3Pad(byte[] msg) {
    long bitLen = ((long) msg.length) * 8L;
    int k = (int) ((448 - (bitLen + 1) % 512 + 512) % 512);
    int totalLen = (int) ((bitLen + 1 + k + 64) / 8);

    byte[] padded = new byte[totalLen];
    System.arraycopy(msg, 0, padded, 0, msg.length);
    padded[msg.length] = (byte) 0x80;

    for (int i = 0; i < 8; i++) {
      padded[totalLen - 8 + i] = (byte) ((bitLen >>> (56 - i * 8)) & 255);
    }
    return padded;
  }

  private int ff(int j, int x, int y, int z) {
    if (j < 16) {
      return x ^ y ^ z;
    }
    return (x & y) | (x & z) | (y & z);
  }

  private int gg(int j, int x, int y, int z) {
    if (j < 16) {
      return x ^ y ^ z;
    }
    return (x & y) | ((~x) & z);
  }

  private int p0(int x) {
    return x ^ rotl(x, 9) ^ rotl(x, 17);
  }

  private int p1(int x) {
    return x ^ rotl(x, 15) ^ rotl(x, 23);
  }

  private int t(int j) {
    return j < 16 ? 0x79CC4519 : 0x7A879D8A;
  }

  private int rotl(int x, int n) {
    int r = n & 31;
    return (x << r) | (x >>> (32 - r));
  }

  private int[] charCodeAt(String s) {
    int[] out = new int[s.length()];
    for (int i = 0; i < s.length(); i++) {
      out[i] = s.charAt(i);
    }
    return out;
  }

  private String fromCharCode(int[] values) {
    StringBuilder sb = new StringBuilder(values.length);
    for (int value : values) {
      sb.append((char) (value & 255));
    }
    return sb.toString();
  }

  private String rc4Encrypt(String plaintext, String key) {
    int[] s = new int[256];
    for (int i = 0; i < 256; i++) {
      s[i] = i;
    }

    int j = 0;
    for (int i = 0; i < 256; i++) {
      j = (j + s[i] + key.charAt(i % key.length())) & 255;
      int tmp = s[i];
      s[i] = s[j];
      s[j] = tmp;
    }

    int i = 0;
    j = 0;
    StringBuilder out = new StringBuilder(plaintext.length());
    for (int k = 0; k < plaintext.length(); k++) {
      i = (i + 1) & 255;
      j = (j + s[i]) & 255;
      int tmp = s[i];
      s[i] = s[j];
      s[j] = tmp;
      int t = (s[i] + s[j]) & 255;
      out.append((char) ((s[t] ^ plaintext.charAt(k)) & 255));
    }
    return out.toString();
  }

  private String generateResult(String source, String key) {
    String alphabet = STR_MAP.getOrDefault(key, STR_MAP.get("s4"));
    StringBuilder out = new StringBuilder((source.length() * 4) / 3 + 4);

    for (int i = 0; i < source.length(); i += 3) {
      int n;
      if (i + 2 < source.length()) {
        n = (source.charAt(i) << 16) | (source.charAt(i + 1) << 8) | source.charAt(i + 2);
      } else if (i + 1 < source.length()) {
        n = (source.charAt(i) << 16) | (source.charAt(i + 1) << 8);
      } else {
        n = source.charAt(i) << 16;
      }

      int[] shifts = {18, 12, 6, 0};
      int[] masks = {0xFC0000, 0x03F000, 0x000FC0, 0x00003F};

      for (int idx = 0; idx < shifts.length; idx++) {
        int shift = shifts[idx];
        if (shift == 6 && i + 1 >= source.length()) {
          break;
        }
        if (shift == 0 && i + 2 >= source.length()) {
          break;
        }
        out.append(alphabet.charAt((n & masks[idx]) >> shift));
      }
    }

    int pad = (4 - (out.length() % 4)) % 4;
    out.append("=".repeat(pad));
    return out.toString();
  }
}
