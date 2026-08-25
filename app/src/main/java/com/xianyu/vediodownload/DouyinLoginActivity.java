package com.xianyu.vediodownload;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.regex.Pattern;

/**
 * 抖音一键登录 Activity。
 *
 * 流程：
 * 1. 内嵌 WebView 用桌面 Chrome UA 打开 https://www.douyin.com/
 * 2. 用户在 WebView 里用手机抖音 APP 扫码登录
 * 3. 轮询检测 douyin.com 的 cookie，一旦出现 sessionid/uid_tt/msToken
 *    且没有登录按钮 → 视为登录成功
 * 4. 用 android.webkit.CookieManager.getCookie() 抓取所有 cookie
 *    （包含 HttpOnly 的 msToken/UIFID），写入本地 prefs
 * 5. 关闭本 Activity，回到主页
 *
 * ⚠️ 仅供个人技术学习使用，禁止商用
 */
public class DouyinLoginActivity extends AppCompatActivity {

    private static final String TAG = "DouyinLogin";

    private WebView webView;
    private TextView tvStatus;
    private LinearLayout layoutLoading;
    private com.xianyu.vediodownload.util.CookieManager prefsCookieManager;

    /** 桌面 Chrome UA —— 必须用桌面 UA，否则抖音会返回手机版 HTML（带扫码按钮但没有用户头像） */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 兜底：用户主动点 "我已登录" 时强制抓一次 cookie */
    private boolean userConfirmed = false;

    /** 登录成功标志（防止多次触发） */
    private volatile boolean finished = false;

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (finished) return;
            checkLoginStateByCookie();
            pollHandler.postDelayed(this, 1500); // 每 1.5s 轮询一次
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_douyin_login);

        webView = findViewById(R.id.webView);
        tvStatus = findViewById(R.id.tvStatus);
        layoutLoading = findViewById(R.id.layoutLoading);
        Button btnClose = findViewById(R.id.btnClose);
        Button btnManual = findViewById(R.id.btnManual);
        Button btnIveLoggedIn = findViewById(R.id.btnIveLoggedIn);

        prefsCookieManager = new com.xianyu.vediodownload.util.CookieManager(this);

        // 清理残留 cookie（避免上次未登录状态干扰）
        CookieManager wv = CookieManager.getInstance();
        wv.removeAllCookies(null);
        wv.removeSessionCookies(null);
        wv.setAcceptCookie(true);
        wv.setAcceptThirdPartyCookies(webView, true);

        // WebView 配置
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        // 关键：桌面 UA
        webView.getSettings().setUserAgentString(DESKTOP_UA);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setSupportMultipleWindows(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.d(TAG, "onPageStarted: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished: " + url);
                checkLoginStateByCookie();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        btnClose.setOnClickListener(v -> finish());
        btnManual.setOnClickListener(v -> {
            // 跳到主页打开手动粘贴对话框
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra("open_cookie_dialog", true);
            startActivity(i);
            finish();
        });
        btnIveLoggedIn.setOnClickListener(v -> {
            userConfirmed = true;
            tvStatus.setText("正在抓取 Cookie…");
            layoutLoading.setVisibility(View.VISIBLE);
            extractAndSaveCookie();
        });

        webView.loadUrl("https://www.douyin.com/");
        // 启动轮询检测
        pollHandler.postDelayed(pollRunnable, 2000);
    }

    /**
     * 通过 cookie 字段判断登录态（比 HTML 内容更可靠，因为 cookie 来自原生 API，
     * 不会被反爬的暗水印/混淆 HTML 干扰）。
     */
    private void checkLoginStateByCookie() {
        if (finished) return;
        CookieManager wv = CookieManager.getInstance();
        String raw = wv.getCookie("https://www.douyin.com/");
        if (TextUtils.isEmpty(raw)) return;

        String lower = raw.toLowerCase();
        boolean hasSession = lower.contains("sessionid=") || lower.contains("sessionid_ss=");
        boolean hasUidTt = lower.contains("uid_tt=");
        boolean hasMsToken = lower.contains("mstoken=");
        boolean hasUIFID = lower.contains("uifid=");
        boolean hasTtwid = lower.contains("ttwid=");
        boolean hasOdinTt = lower.contains("odin_tt=");

        // 至少要有 sessionid + uid_tt 才算登录
        boolean loggedIn = hasSession && hasUidTt;
        Log.d(TAG, "poll: loggedIn=" + loggedIn
                + " session=" + hasSession
                + " uid_tt=" + hasUidTt
                + " msToken=" + hasMsToken
                + " UIFID=" + hasUIFID
                + " ttwid=" + hasTtwid
                + " odin_tt=" + hasOdinTt);

        if (loggedIn) {
            tvStatus.setText("✓ 检测到登录态，正在抓取 Cookie…");
            layoutLoading.setVisibility(View.VISIBLE);
            extractAndSaveCookie();
        } else {
            tvStatus.setText("请用手机抖音 APP 扫码登录（登录后会自动保存 Cookie）");
        }
    }

    /** 把 WebView 里 douyin.com 的所有 Cookie 抓出来，拼接成字符串，保存 */
    private void extractAndSaveCookie() {
        if (finished) return;
        CookieManager wv = CookieManager.getInstance();
        String raw = wv.getCookie("https://www.douyin.com/");
        Log.d(TAG, "raw cookie from WebView (len=" + (raw == null ? 0 : raw.length()) + ")");

        if (TextUtils.isEmpty(raw)) {
            tvStatus.setText("未拿到 Cookie，请重试");
            layoutLoading.setVisibility(View.GONE);
            return;
        }

        String combined = raw.trim();
        prefsCookieManager.setCookie(combined);

        Toast.makeText(this, "登录成功，Cookie 已保存 (" + combined.length() + " 字符)", Toast.LENGTH_SHORT).show();
        finished = true;
        pollHandler.removeCallbacks(pollRunnable);
        finish();
    }

    @Override
    protected void onDestroy() {
        pollHandler.removeCallbacks(pollRunnable);
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}