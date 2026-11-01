# Keep the JavaScript bridge methods if minification is ever enabled.
-keepclassmembers class com.offline.flipclock.ClockBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
