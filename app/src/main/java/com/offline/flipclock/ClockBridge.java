package com.offline.flipclock;

import android.app.Activity;
import android.webkit.JavascriptInterface;

import java.lang.ref.WeakReference;

/**
 * JavaScript bridge exposed to the clock page as "AndroidClock".
 *
 * JavaScript usage:  AndroidClock.closeApp();
 *
 * Only methods annotated with @JavascriptInterface are reachable from JavaScript on
 * Android 4.2+ (API 17+). The page is a local asset and the WebView cannot load
 * network content, so no remote page can ever reach this bridge.
 */
public final class ClockBridge {

    /** Name under which the bridge is visible in JavaScript. */
    public static final String JS_NAME = "AndroidClock";

    private final WeakReference<Activity> activityRef;

    public ClockBridge(Activity activity) {
        this.activityRef = new WeakReference<Activity>(activity);
    }

    /**
     * Called from JavaScript after a valid triple tap.
     * JavaScript bridge calls arrive on a WebView background thread, so finish()
     * is posted to the UI thread.
     */
    @JavascriptInterface
    public void closeApp() {
        final Activity activity = activityRef.get();
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!activity.isFinishing()) {
                    activity.finish();
                }
            }
        });
    }
}
