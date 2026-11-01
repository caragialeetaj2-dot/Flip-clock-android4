package com.offline.flipclock;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;

/**
 * Single Activity hosting the offline flip clock in a WebView.
 *
 * - Loads only file:///android_asset/index.html
 * - Network loads are blocked; the APK has no permissions at all.
 * - The minute-aligned timer lives in JavaScript (one setTimeout); it is paused in
 *   onPause() and resynchronised with the device clock in onResume().
 * - FLAG_KEEP_SCREEN_ON is held only while the Activity is in the foreground.
 */
public class MainActivity extends Activity {

    private static final String CLOCK_URL = "file:///android_asset/index.html";
    private static final String ASSET_PREFIX = "file:///android_asset/";

    /** Delay before re-hiding system bars after the user revealed them. */
    private static final long REHIDE_DELAY_MS = 2500L;

    private WebView webView;
    private boolean pageReady = false;
    private boolean receiverRegistered = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable reapplyFullscreen = new Runnable() {
        @Override
        public void run() {
            applyFullscreen();
        }
    };

    /**
     * Not a timer: reacts only when the user/system changes the clock, time zone or date,
     * so the display re-syncs instantly instead of waiting for the next minute boundary.
     * These are protected system broadcasts and need no permission.
     */
    private final BroadcastReceiver timeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            runJs("window.ClockApp&&window.ClockApp.resume();");
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 28) {
            allowDisplayCutout();
        }

        webView = new WebView(this);
        configureWebView(webView);
        setContentView(webView);

        installSystemUiListener();
        applyFullscreen();

        webView.loadUrl(CLOCK_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Keep the screen readable only while the clock is visible.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
        registerTimeReceiver();
        applyFullscreen();

        // Immediately re-read the device clock and schedule the next minute boundary.
        runJs("window.ClockApp&&window.ClockApp.resume();");
    }

    @Override
    protected void onPause() {
        // Stop the JavaScript minute timer first, then freeze the WebView.
        runJs("window.ClockApp&&window.ClockApp.pause();");

        uiHandler.removeCallbacksAndMessages(null);
        unregisterTimeReceiver();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        unregisterTimeReceiver();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (webView != null) {
            pageReady = false;
            webView.stopLoading();
            webView.removeJavascriptInterface(ClockBridge.JS_NAME);
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyFullscreen();
        }
    }

    // ------------------------------------------------------------------ WebView setup

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView(WebView view) {
        view.setBackgroundColor(Color.BLACK);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setHapticFeedbackEnabled(false);
        view.setLongClickable(false);
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true; // no text-selection / context menu
            }
        });

        WebSettings s = view.getSettings();
        // Required settings.
        s.setBlockNetworkLoads(true);
        s.setAllowFileAccess(true);
        s.setJavaScriptEnabled(true);

        // Hardening / low-resource settings (all available on API 14).
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setDomStorageEnabled(false);
        s.setDatabaseEnabled(false);
        s.setSaveFormData(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= 16) {
            restrictFileUrlAccess(s);
        }

        view.addJavascriptInterface(new ClockBridge(this), ClockBridge.JS_NAME);
        view.setWebViewClient(new LocalOnlyClient());
    }

    @TargetApi(16)
    private static void restrictFileUrlAccess(WebSettings s) {
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
    }

    /** Keeps the WebView pinned to the local clock page and refuses any other resource. */
    private final class LocalOnlyClient extends WebViewClient {

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return true; // never navigate away from the clock page
        }

        @TargetApi(24)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return true;
        }

        @SuppressWarnings("deprecation")
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            if (url != null && url.startsWith(ASSET_PREFIX)) {
                return null; // allow packaged assets
            }
            return blockedResponse();
        }

        @TargetApi(21)
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl() != null ? request.getUrl().toString() : null;
            if (url != null && url.startsWith(ASSET_PREFIX)) {
                return null;
            }
            return blockedResponse();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (url != null && url.startsWith(CLOCK_URL)) {
                pageReady = true;
                runJs("window.ClockApp&&window.ClockApp.resume();");
            }
        }
    }

    private static WebResourceResponse blockedResponse() {
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }

    /** Runs a JavaScript snippet in the clock page (evaluateJavascript on 4.4+, javascript: URL before). */
    private void runJs(String script) {
        if (webView == null || !pageReady) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 19) {
            evaluateJs(script);
        } else {
            webView.loadUrl("javascript:" + script);
        }
    }

    @TargetApi(19)
    private void evaluateJs(String script) {
        webView.evaluateJavascript(script, null);
    }

    // ------------------------------------------------------------------ time-change receiver

    private void registerTimeReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        registerReceiver(timeChangeReceiver, filter);
        receiverRegistered = true;
    }

    private void unregisterTimeReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(timeChangeReceiver);
        } catch (IllegalArgumentException ignored) {
            // already unregistered
        }
        receiverRegistered = false;
    }

    // ------------------------------------------------------------------ fullscreen / immersive

    /**
     * API 19+ : sticky immersive mode (status + navigation bars hidden).
     * API 16-18: fullscreen layout, navigation buttons dimmed (low profile).
     * API 14-15: window FLAG_FULLSCREEN + low profile.
     * HIDE_NAVIGATION is deliberately NOT used below API 19, because there the first touch
     * is consumed by the system to show the bar again, which would break the triple tap.
     */
    @SuppressWarnings("deprecation")
    private void applyFullscreen() {
        if (webView == null) {
            return;
        }
        int flags;
        if (Build.VERSION.SDK_INT >= 19) {
            flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        } else if (Build.VERSION.SDK_INT >= 16) {
            flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LOW_PROFILE;
        } else {
            flags = View.SYSTEM_UI_FLAG_LOW_PROFILE;
        }
        webView.setSystemUiVisibility(flags);
    }

    @SuppressWarnings("deprecation")
    private void installSystemUiListener() {
        webView.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override
            public void onSystemUiVisibilityChange(int visibility) {
                // Re-hide only when the bars came back (or low-profile was cleared).
                int watched = Build.VERSION.SDK_INT >= 16
                        ? View.SYSTEM_UI_FLAG_FULLSCREEN
                        : View.SYSTEM_UI_FLAG_LOW_PROFILE;
                if ((visibility & watched) == 0) {
                    uiHandler.removeCallbacks(reapplyFullscreen);
                    uiHandler.postDelayed(reapplyFullscreen, REHIDE_DELAY_MS);
                }
            }
        });
    }

    @TargetApi(28)
    private void allowDisplayCutout() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
    }
}
