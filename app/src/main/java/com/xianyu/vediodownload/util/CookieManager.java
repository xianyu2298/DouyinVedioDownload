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
}