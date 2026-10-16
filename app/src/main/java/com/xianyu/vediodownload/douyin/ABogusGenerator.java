package com.xianyu.vediodownload.douyin;

import com.xianyu.vediodownload.util.CustomBase64;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 抖音 a_bogus 签名生成器，算法逆向自 DouK-Downloader (TikTokDownloader) 的
 * bdms.js（v1.0.1.19-fix.01），与 src/encrypt/aBogus.py 保持一致。
 *
 * <p>本代算法不对 HTTP 方法做哈希，仅哈希 query 与 body；User-Agent 必须与请求
 * 实际发送的 User-Agent 一致，签名中的第三条摘要链会与其绑定。
 *
 * <p>核心结构：SM3(SM3(text + "dhzx")) 双重哈希 + 50 字段载荷 + 非标准 RC4
 * + 自定义 Base64（s4 字母表），输出 192 字符签名。
 *
 * <p>仅供个人技术学习使用，禁止商用。
 */
final class ABogusGenerator {
  // a_bogus 使用的两套字母表：s4 编码最终签名，s3 编码进入第三个摘要的 User-Agent
  private static final String ALPHABET_S3 =
      "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe";
  private static final String ALPHABET_S4 =
      "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe";

  // 哈希前附加在 query 与 body 末尾的盐值
  private static final String SALT = "dhzx";

  // 签名头部的两个明文字节（相当于格式魔数）
  private static final int HEADER_MAGIC_FIRST = 3;
  private static final int HEADER_MAGIC_SECOND = 82;

  // "1.0.1.19-fix.01" 解析后的版本块
  private static final int[] SDK_VERSION = {1, 0, 1, 0};

  // 载荷密钥（单字节）。该 RC4 并非标准 RC4：S 盒降序初始化、密钥调度使用乘法
  private static final int PAYLOAD_KEY = 0xD3;

  // 2024-07-24T16:00:00Z，其中一个字段自此以双周为单位计数
  private static final long FORTNIGHT_EPOCH_MS = 1_721_836_800_000L;
  private static final long FORTNIGHT_MILLIS = 1000L * 60 * 60 * 24 * 14;

  // 抖音网页端的固定标识，随签名与 query 一起发送，两者必须一致
  private static final int PAGE_ID = 6241;
  private static final int AID = 6383;

  // fn148 用三个掩码把三个数据字节与一个噪声字节混成四个载体字节
  private static final int[] NOISE_MASKS = {0x91, 0x42, 0x2C};
  private static final int[] DATA_MASKS = {0x6E, 0xBD, 0xD3};

  // 五十个标量字段在载荷中的固定排列顺序（字节码中的原始顺序）
  private static final String[] FIELD_ORDER = {
    "L34", "L44", "L56", "L61", "L73", "L29", "L70", "L45", "L35", "L49",
    "L38", "L66", "L51", "L68", "L28", "L48", "L64", "L47", "L30", "L71",
    "L26", "L55", "L31", "L69", "L59", "L40", "L62", "L63", "L27", "L72",
    "L41", "L74", "L57", "L52", "L42", "L39", "L33", "L67", "L53", "L43",
    "L65", "L46", "L36", "L24", "L60", "L32", "L79", "L80", "L84", "L85",
  };

  private static final Map<String, Integer> FIELD_INDEX = buildFieldIndex();

  // 摘要哨兵：字段取 digest[offset:] 中第一个不等于 sentinel 的字节
  private static final int[][] CANARIES = {{3, 11, 12}, {4, 8, 9}, {5, 12, 13}};

  // 三条哈希链（query / body / user_agent）各自贡献的三个字节
  private static final String[] QUERY_CHAIN_SLOTS = {"L48", "L49", "L51"};
  private static final int[] QUERY_CHAIN_INDICES = {9, 18};
  private static final int[] QUERY_CHAIN_CANARY = CANARIES[0];
  private static final String[] BODY_CHAIN_SLOTS = {"L52", "L53", "L55"};
  private static final int[] BODY_CHAIN_INDICES = {10, 19};
  private static final int[] BODY_CHAIN_CANARY = CANARIES[1];
  private static final String[] UA_CHAIN_SLOTS = {"L56", "L57", "L59"};
  private static final int[] UA_CHAIN_INDICES = {11, 21};
  private static final int[] UA_CHAIN_CANARY = CANARIES[2];

  // 未被篡改的浏览器环境报告值（六个探针与机器人检测位集）
  private static final int ENV_FLAGS = 1;
  private static final int DETECT_FLAGS = 14;
  private static final int NR_FLAGS = 0x21;

  // window.onwheelx._Ax 存在且已锁定——SDK 自装的正常状态
  private static final int TRIPWIRE_LOCKED = 3;

  // fn149 的签名次数分桶：6 表示本页签名次数少于 140 次
  private static final int CALL_BUCKET = 6;

  // 固定浏览器几何信息（内窗|外窗|可用区|屏幕|平台），与请求参数保持一致。
  private static final String BROWSER_INFO = "1536|742|1536|864|1536|864|1536|864|MacIntel";

  // fn145 的绊线探测值：位 1、4、5、7 置位是健康状态
  private static final int TRIPWIRE_SET = 0xB2;
  private static final int TRIPWIRE_FREE = 0x4D;

  private final int[] userAgentDigest;

  ABogusGenerator(String userAgent) {
    this.userAgentDigest = userAgentDigest(userAgent == null ? "" : userAgent.strip());
  }

  String getValue(String query) {
    return getValue(query, System.currentTimeMillis(), new Random());
  }

  String getValue(String query, long nowMs, Random rng) {
    if (nowMs < FORTNIGHT_EPOCH_MS) {
      // 时钟早于纪元说明调用方传了秒而不是毫秒
      throw new IllegalArgumentException("clock is before the a_bogus epoch: " + nowMs);
    }

    int[] fields = buildFields(query == null ? "" : query, nowMs);

    // 四个噪声字节中有三个是环境报告而非噪声，仅版本块低字节为自由抽样
    int[] version =
        concat(
            maskPair(SDK_VERSION[0], SDK_VERSION[1], rng, null, null),
            maskPair(SDK_VERSION[2], SDK_VERSION[3], rng, probeNoise(rng), tripwireNoise(rng)));

    // 校验和只覆盖版本块与五十个标量
    int checksum = 0;
    for (int value : version) {
      checksum ^= value;
    }
    for (String name : FIELD_ORDER) {
      checksum ^= fields[fieldIndex(name)];
    }

    byte[] bodyBytes = new byte[FIELD_ORDER.length];
    for (int i = 0; i < FIELD_ORDER.length; i++) {
      bodyBytes[i] = (byte) fields[fieldIndex(FIELD_ORDER[i])];
    }
    byte[] infoBytes = jsBytes(BROWSER_INFO);
    byte[] tailBytes = jsBytes(((int) ((nowMs + 3) & 0xFF)) + ",");
    byte[] all =
        new byte[bodyBytes.length + infoBytes.length + tailBytes.length + 1];
    int offset = 0;
    System.arraycopy(bodyBytes, 0, all, offset, bodyBytes.length);
    offset += bodyBytes.length;
    System.arraycopy(infoBytes, 0, all, offset, infoBytes.length);
    offset += infoBytes.length;
    System.arraycopy(tailBytes, 0, all, offset, tailBytes.length);
    offset += tailBytes.length;
    all[offset] = (byte) checksum;

    int[] headerNoisePair = maskPair(HEADER_MAGIC_FIRST, HEADER_MAGIC_SECOND, rng, null, headerNoise(rng));
    byte[] header = new byte[headerNoisePair.length];
    for (int i = 0; i < headerNoisePair.length; i++) {
      header[i] = (byte) headerNoisePair[i];
    }
    byte[] frame = expandNoise(all, rng);

    byte[] sealedInput = new byte[version.length + frame.length];
    for (int i = 0; i < version.length; i++) {
      sealedInput[i] = (byte) version[i];
    }
    System.arraycopy(frame, 0, sealedInput, version.length, frame.length);
    byte[] sealed = rc4(new int[] {PAYLOAD_KEY}, sealedInput);
    byte[] result = new byte[header.length + sealed.length];
    System.arraycopy(header, 0, result, 0, header.length);
    System.arraycopy(sealed, 0, result, header.length, sealed.length);
    return CustomBase64.encode(result, ALPHABET_S4);
  }

  // ------------------------------------------------------------------
  // 字段布局
  // ------------------------------------------------------------------

  private int[] buildFields(String query, long nowMs) {
    int[] fields = new int[FIELD_ORDER.length];

    // ink 不比时钟慢一毫秒的载荷不是完整入口点的产物
    long ink = nowMs - 1;
    long fortnights = (nowMs - FORTNIGHT_EPOCH_MS) / FORTNIGHT_MILLIS;
    byte[] infoBytes = jsBytes(BROWSER_INFO);
    byte[] tailBytes = jsBytes(((int) ((nowMs + 3) & 0xFF)) + ",");

    int[] queryDigest = digestOf(query);

    fields[fieldIndex("L24")] = 41;
    fields[fieldIndex("L26")] = (int) fortnights;
    fields[fieldIndex("L27")] = CALL_BUCKET;
    // SDK 初始化至今的毫秒数加三：常量 3 表示入口点被即时到达
    fields[fieldIndex("L28")] = 3;
    fields[fieldIndex("L35")] = ENV_FLAGS & 0xFF;
    fields[fieldIndex("L36")] = (ENV_FLAGS / 256) & 0xFF;
    fields[fieldIndex("L38")] = NR_FLAGS & 0xFF;
    fields[fieldIndex("L39")] = (NR_FLAGS >> 8) & 0xFF;
    fields[fieldIndex("L66")] = TRIPWIRE_LOCKED;
    fields[fieldIndex("L79")] = infoBytes.length & 0xFF;
    fields[fieldIndex("L80")] = (infoBytes.length >> 8) & 0xFF;
    fields[fieldIndex("L84")] = tailBytes.length & 0xFF;
    fields[fieldIndex("L85")] = (tailBytes.length >> 8) & 0xFF;

    int[] nowLe = leBytes(nowMs, 6);
    for (int i = 0; i < nowLe.length; i++) {
      fields[fieldIndex("L" + (29 + i))] = nowLe[i];
    }
    int[] detectLe = leBytes(DETECT_FLAGS, 4);
    for (int i = 0; i < detectLe.length; i++) {
      fields[fieldIndex("L" + (44 + i))] = detectLe[i];
    }
    // 机器人检测标签 L40..L43 恒为 0，无需写入
    int[] inkLe = leBytes(ink, 6);
    for (int i = 0; i < inkLe.length; i++) {
      fields[fieldIndex("L" + (60 + i))] = inkLe[i];
    }
    int[] pageLe = leBytes(PAGE_ID, 4);
    for (int i = 0; i < pageLe.length; i++) {
      fields[fieldIndex("L" + (67 + i))] = pageLe[i];
    }
    int[] aidLe = leBytes(AID, 4);
    for (int i = 0; i < aidLe.length; i++) {
      fields[fieldIndex("L" + (71 + i))] = aidLe[i];
    }

    int[] queryChain = chainBytes(queryDigest, QUERY_CHAIN_INDICES, QUERY_CHAIN_CANARY);
    for (int i = 0; i < QUERY_CHAIN_SLOTS.length; i++) {
      fields[fieldIndex(QUERY_CHAIN_SLOTS[i])] = queryChain[i];
    }
    // GET 请求无 body，body 摘要链恒为空字符串的摘要
    int[] bodyChain = chainBytes(digestOf(""), BODY_CHAIN_INDICES, BODY_CHAIN_CANARY);
    for (int i = 0; i < BODY_CHAIN_SLOTS.length; i++) {
      fields[fieldIndex(BODY_CHAIN_SLOTS[i])] = bodyChain[i];
    }
    int[] uaChain = chainBytes(userAgentDigest, UA_CHAIN_INDICES, UA_CHAIN_CANARY);
    for (int i = 0; i < UA_CHAIN_SLOTS.length; i++) {
      fields[fieldIndex(UA_CHAIN_SLOTS[i])] = uaChain[i];
    }
    return fields;
  }

  private static int fieldIndex(String name) {
    Integer index = FIELD_INDEX.get(name);
    if (index == null) {
      throw new IllegalArgumentException("unknown field " + name);
    }
    return index;
  }

  private static Map<String, Integer> buildFieldIndex() {
    Map<String, Integer> index = new HashMap<>();
    for (int i = 0; i < FIELD_ORDER.length; i++) {
      index.put(FIELD_ORDER[i], i);
    }
    return index;
  }

  private static int[] chainBytes(int[] digest, int[] indices, int[] canary) {
    return new int[] {digest[indices[0]], digest[indices[1]], canary(digest, canary)};
  }

  private static int canary(int[] digest, int[] canarySpec) {
    int sentinel = canarySpec[1];
    int fallback = canarySpec[2];
    for (int i = canarySpec[0]; i < digest.length; i++) {
      if (digest[i] != sentinel) {
        return digest[i];
      }
    }
    return fallback;
  }

  // ------------------------------------------------------------------
  // 摘要与编码
  // ------------------------------------------------------------------

  private static int[] digestOf(String text) {
    // SM3(SM3(text + SALT))：内层摘要按原始字节参与外层哈希
    int[] inner = sm3ToArray(utf8(text + SALT));
    byte[] innerBytes = new byte[inner.length];
    for (int i = 0; i < inner.length; i++) {
      innerBytes[i] = (byte) inner[i];
    }
    return sm3ToArray(innerBytes);
  }

  /** 第三条链：RC4 加密 User-Agent，base64 编码后做一次 SM3。 */
  private static int[] userAgentDigest(String userAgent) {
    byte[] key = new byte[] {(byte) (ENV_FLAGS / 256), (byte) (ENV_FLAGS % 256), (byte) (DETECT_FLAGS % 256)};
    byte[] sealed = rc4(key, jsBytes(userAgent));
    return sm3ToArray(utf8(CustomBase64.encode(sealed, ALPHABET_S3)));
  }

  /**
   * 按 SDK 的 charCodeAt 规则把字符串转为字节。
   *
   * <p>非 UTF-8：逐个读取 UTF-16 码元，低于 U+0100 输出一字节，高于则输出两个
   * 大端字节——汉字为两字节而 UTF-8 为三字节。
   */
  private static byte[] jsBytes(String text) {
    byte[] out = new byte[text.length() * 2];
    int size = 0;
    for (int i = 0; i < text.length(); i++) {
      char code = text.charAt(i);
      if ((code & 0xFF00) != 0) {
        out[size++] = (byte) ((code >> 8) & 0xFF);
      }
      out[size++] = (byte) (code & 0xFF);
    }
    byte[] result = new byte[size];
    System.arraycopy(out, 0, result, 0, size);
    return result;
  }

  private static int[] leBytes(long value, int count) {
    int[] out = new int[count];
    for (int i = 0; i < count; i++) {
      out[i] = (int) ((value >> (8 * i)) & 0xFF);
    }
    return out;
  }

  private static int[] maskPair(int first, int second, Random rng, Integer low, Integer high) {
    int noise = (int) (rng.nextDouble() * 65535);
    int lowValue = low == null ? noise & 0xFF : low & 0xFF;
    int highValue = high == null ? (noise >> 8) & 0xFF : high & 0xFF;
    return new int[] {
      (lowValue & 0xAA) | (first & 0x55),
      (lowValue & 0x55) | (first & 0xAA),
      (highValue & 0xAA) | (second & 0x55),
      (highValue & 0x55) | (second & 0xAA),
    };
  }

  /** fn144：超过 109 时强制为奇数，因此 110..240 中有一半不可达 */
  private static int probeNoise(Random rng) {
    int value = (int) (rng.nextDouble() * 240);
    return value > 109 ? value + value % 2 + 1 : value;
  }

  /** fn145："绊线全部存在且已锁定"，叠加真实噪声 */
  private static int tripwireNoise(Random rng) {
    return ((int) (rng.nextDouble() * 255) & TRIPWIRE_FREE) | TRIPWIRE_SET;
  }

  /** fn143：伪装成噪声的浏览器家族报告（Chrome 桌面端基值为 0）。 */
  private static int headerNoise(Random rng) {
    return (int) (rng.nextDouble() * 40) & 0xFF;
  }

  private static byte[] expandNoise(byte[] body, Random rng) {
    // 每 3 字节扩为 4 字节，尾部不足 3 字节的分组按 SDK 的尾部分支处理
    byte[] out = new byte[body.length / 3 * 4 + body.length % 3];
    int size = 0;
    for (int offset = 0; offset < body.length; offset += 3) {
      int remaining = body.length - offset;
      if (remaining < 3) {
        // SDK 的尾部分支：本实现的载荷长度恒为 3 的倍数，仅为对齐字节码保留
        out[size++] = body[offset];
        if (remaining > 1 && (body[offset + 1] & 0xFF) != 0) {
          out[size++] = body[offset + 1];
        }
        continue;
      }
      int noise = (int) (rng.nextDouble() * 1000) & 0xFF;
      for (int i = 0; i < 3; i++) {
        out[size++] =
            (byte) ((noise & NOISE_MASKS[i]) | (body[offset + i] & DATA_MASKS[i]));
      }
      out[size++] =
          (byte) ((body[offset] & NOISE_MASKS[0]) | (body[offset + 1] & NOISE_MASKS[1]) | (body[offset + 2] & NOISE_MASKS[2]));
    }
    byte[] result = new byte[size];
    System.arraycopy(out, 0, result, 0, size);
    return result;
  }

  /** SDK 的 RC4，并非标准 RC4：S 盒降序初始化，密钥调度使用乘法 */
  private static byte[] rc4(byte[] key, byte[] data) {
    int[] intKey = new int[key.length];
    for (int i = 0; i < key.length; i++) {
      intKey[i] = key[i] & 0xFF;
    }
    return rc4(intKey, data);
  }

  private static byte[] rc4(int[] key, byte[] data) {
    int[] box = new int[256];
    for (int i = 0; i < 256; i++) {
      box[255 - i] = i;
    }
    int j = 0;
    for (int i = 0; i < 256; i++) {
      j = (j * box[i] + j + key[i % key.length]) % 256;
      int tmp = box[i];
      box[i] = box[j];
      box[j] = tmp;
    }
    byte[] out = new byte[data.length];
    int i = 0;
    j = 0;
    for (int index = 0; index < data.length; index++) {
      i = (i + 1) % 256;
      j = (j + box[i]) % 256;
      int tmp = box[i];
      box[i] = box[j];
      box[j] = tmp;
      out[index] = (byte) (data[index] ^ box[(box[i] + box[j]) % 256]);
    }
    return out;
  }

  // ------------------------------------------------------------------
  // SM3（标准实现，与上游 src/encrypt/sm3.py 一致）
  // ------------------------------------------------------------------

  private static final int[] SM3_IV = {
    0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600,
    0xA96F30BC, 0x163138AA, 0xE38DEE4D, 0xB0FB0E4E,
  };

  private static int[] sm3ToArray(byte[] message) {
    int[] v = SM3_IV.clone();
    byte[] padded = sm3Pad(message);
    int[] w = new int[68];
    int[] w1 = new int[64];

    for (int offset = 0; offset < padded.length; offset += 64) {
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

      int a = v[0], b = v[1], c = v[2], d = v[3], e = v[4], f = v[5], g = v[6], h = v[7];
      for (int j = 0; j < 64; j++) {
        int ss1 = rotl(rotl(a, 12) + e + rotl(j < 16 ? 0x79CC4519 : 0x7A879D8A, j), 7);
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

    int[] out = new int[32];
    for (int i = 0; i < 8; i++) {
      out[i * 4] = (v[i] >>> 24) & 255;
      out[i * 4 + 1] = (v[i] >>> 16) & 255;
      out[i * 4 + 2] = (v[i] >>> 8) & 255;
      out[i * 4 + 3] = v[i] & 255;
    }
    return out;
  }

  private static byte[] sm3Pad(byte[] msg) {
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

  private static int ff(int j, int x, int y, int z) {
    return j < 16 ? x ^ y ^ z : (x & y) | (x & z) | (y & z);
  }

  private static int gg(int j, int x, int y, int z) {
    return j < 16 ? x ^ y ^ z : (x & y) | ((~x) & z);
  }

  private static int p0(int x) {
    return x ^ rotl(x, 9) ^ rotl(x, 17);
  }

  private static int p1(int x) {
    return x ^ rotl(x, 15) ^ rotl(x, 23);
  }

  private static int rotl(int x, int n) {
    int r = n & 31;
    return (x << r) | (x >>> (32 - r));
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static int[] concat(int[] first, int[] second) {
    int[] out = new int[first.length + second.length];
    System.arraycopy(first, 0, out, 0, first.length);
    System.arraycopy(second, 0, out, first.length, second.length);
    return out;
  }
}
