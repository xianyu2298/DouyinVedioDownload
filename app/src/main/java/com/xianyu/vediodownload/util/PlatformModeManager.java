package com.xianyu.vediodownload.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.xianyu.vediodownload.model.Platform;

/**
 * 全局「解析模式」持久化。
 *
 * <p>应用只支持一个当前生效的平台：抖音（默认）或快手。视频页 / 图片页在解析时读取该模式，
 * 决定走哪一个解析器；设置页负责切换。
 *
 * <p>用独立 SharedPreferences（{@code app_prefs}）而不是复用 {@code douyin_prefs}，
 * 避免平台无关设置和 Cookie 混在一起。
 */
public final class PlatformModeManager {

    private static final String PREF_NAME = "app_prefs";
    private static final String KEY_MODE = "app_mode";
    private static final String VALUE_KUAISHOU = "kuaishou";

    private final SharedPreferences prefs;

    public PlatformModeManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 当前解析模式。默认抖音。 */
    public Platform getMode() {
        String v = prefs.getString(KEY_MODE, "");
        return VALUE_KUAISHOU.equals(v) ? Platform.KUAISHOU : Platform.DOUYIN;
    }

    public void setMode(Platform mode) {
        prefs.edit()
                .putString(KEY_MODE, mode == Platform.KUAISHOU ? VALUE_KUAISHOU : "douyin")
                .apply();
    }

    public boolean isKuaishou() {
        return getMode() == Platform.KUAISHOU;
    }
}
