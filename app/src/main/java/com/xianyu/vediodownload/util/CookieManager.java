package com.xianyu.vediodownload.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Cookie 本地存储（SharedPreferences）。
 *
 * 仅用于保存用户主动输入的抖音 Web 端 Cookie，
 * 不会上传到任何服务器。
 *
 * ⚠️ 仅供个人技术学习使用，禁止商用
 */
public final class CookieManager {

    private static final String PREF_NAME = "douyin_prefs";
    private static final String KEY_COOKIE = "dy_cookie";
    private static final String KEY_DISCLAIMER_SHOWN = "disclaimer_shown";

    private final SharedPreferences prefs;

    public CookieManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getCookie() {
        String v = prefs.getString(KEY_COOKIE, "");
        return v == null ? "" : v.trim();
    }

    public void setCookie(String cookie) {
        if (cookie == null) cookie = "";
        prefs.edit().putString(KEY_COOKIE, cookie.trim()).apply();
    }

    public void clearCookie() {
        prefs.edit().remove(KEY_COOKIE).apply();
    }

    public boolean isSet() {
        return !getCookie().isEmpty();
    }

    public boolean isDisclaimerShown() {
        boolean v = prefs.getBoolean(KEY_DISCLAIMER_SHOWN, false);
        Log.d("CookieManager", "isDisclaimerShown=" + v);
        return v;
    }

    public void setDisclaimerShown() {
        boolean ok = prefs.edit().putBoolean(KEY_DISCLAIMER_SHOWN, true).commit();
        Log.d("CookieManager", "setDisclaimerShown ok=" + ok);
    }

    /**
     * 本地检查 Cookie 的字段完整性（不联网）。
     *
     * <p>解析抖音作品需要 Cookie 里的关键字段，任一缺失都可能导致解析失败。
     * 这里逐项检查并返回结果，供设置页「Cookie 检查」弹窗展示。
     */
    public CookieCheckResult checkCookie() {
        return checkCookie(getCookie());
    }

    /** 对任意 Cookie 字符串做字段完整性检查（不依赖本地存储）。 */
    public static CookieCheckResult checkCookie(String cookie) {
        if (cookie == null || cookie.trim().isEmpty()) {
            return new CookieCheckResult(false, true, false, false, false, false, false);
        }

        String lower = cookie.toLowerCase();
        boolean hasMsToken = lower.contains("mstoken=");
        boolean hasUifid = lower.contains("uifid=");
        boolean hasSession = lower.contains("sessionid=") || lower.contains("sessionid_ss=");
        boolean hasTtwid = lower.contains("ttwid=");
        boolean hasOdinTt = lower.contains("odin_tt=");
        boolean hasUidTt = lower.contains("uid_tt=");

        // 真正必需的字段是 sessionid（登录态）+ uifid（WebSign 签名）+ ttwid（设备标识）。
        // msToken 是动态 token，登录 Cookie 里通常没有，缺失不影响解析，故不纳入 completeness 判定。
        boolean complete = hasSession && hasUifid && hasTtwid;

        return new CookieCheckResult(
                complete, false, hasMsToken, hasUifid, hasSession, hasTtwid, hasUidTt);
    }

    /** Cookie 字段完整性检查结果。 */
    public static final class CookieCheckResult {
        /** 关键字段是否齐全（sessionid + uifid + ttwid）。 */
        public final boolean complete;
        /** Cookie 是否为空。 */
        public final boolean empty;
        public final boolean hasMsToken;
        public final boolean hasUifid;
        public final boolean hasSession;
        public final boolean hasTtwid;
        public final boolean hasUidTt;

        public CookieCheckResult(
                boolean complete,
                boolean empty,
                boolean hasMsToken,
                boolean hasUifid,
                boolean hasSession,
                boolean hasTtwid,
                boolean hasUidTt) {
            this.complete = complete;
            this.empty = empty;
            this.hasMsToken = hasMsToken;
            this.hasUifid = hasUifid;
            this.hasSession = hasSession;
            this.hasTtwid = hasTtwid;
            this.hasUidTt = hasUidTt;
        }
    }
}