# Offline Flip Clock

A fullscreen, fully offline 24-hour bedside flip clock for Android 4.0+ (API 14).
It uses one Java Activity and a local HTML/CSS/JavaScript page in a WebView.
The app has no permissions, no network access and no third-party libraries.

- Shows `HH:MM` on two dark flip cards, with a fold line and two lit separator dots.
- Shows the date in the upper-right corner as `DD MONTH`, for example `01 NOVEMBER`.
- Updates once per minute using a single timer aligned to the minute boundary.
- **Exit:** tap the screen three times within 900 ms.

## Project structure

```
OfflineFlipClock/
├── settings.gradle
├── build.gradle                      (top level, AGP 8.7.3)
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties   (Gradle 8.9)
└── app/
    ├── build.gradle                  (minSdk 14, targetSdk 35, Java only)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml       (no <uses-permission> at all)
        ├── java/com/offline/flipclock/
        │   ├── MainActivity.java     (WebView, fullscreen, lifecycle, keep-screen-on)
        │   └── ClockBridge.java      (JS bridge "AndroidClock" -> closeApp())
        ├── assets/
        │   ├── index.html
        │   ├── css/clock.css
        │   └── js/clock.js           (ES5 only)
        └── res/
            ├── mipmap-*/ic_launcher.png
            └── values/ (strings.xml, styles.xml)
```

## Build in Android Studio

1. Install a recent Android Studio with JDK 17 (the bundled JBR is fine) and Android SDK Platform 35.
2. Use **File → Open** and select the `OfflineFlipClock` folder.
3. Let Gradle sync. Android Studio downloads Gradle 8.9 and AGP 8.7.3 from `gradle-wrapper.properties` the first time. The build machine needs internet access for this step. The app itself does not.
   - The `gradlew` scripts and `gradle-wrapper.jar` are not included. Android Studio does not need them. If you want to build from the command line, run `gradle wrapper` once in the project folder. This requires a local Gradle installation.
   - If Android Studio offers to upgrade AGP, decline the upgrade. Newer plugin versions may drop support for minSdk 14.
4. To build the APK:
   - **Debug APK:** **Build → Build Bundle(s) / APK(s) → Build APK(s)**. The output is `app/build/outputs/apk/debug/app-debug.apk`.
   - **Release APK:** **Build → Generate Signed App Bundle / APK → APK**. Create or select a keystore, then choose `release`. The APK is signed with v1 and v2 signatures, so Android 4.x can install it.

## Install the APK

**With USB (adb):**
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Without a PC:**
1. Copy the APK to the phone.
2. Allow installation from unknown sources:
   - On Android 4.x–7.x, use **Settings → Security → Unknown sources**.
   - On Android 8+, Android asks you to allow the file manager when you open the APK.
3. Tap the APK to install it.

Open **Offline Flip Clock** from the launcher. The clock opens directly in fullscreen.

## Behaviour notes

| Topic | Implementation |
|---|---|
| Time source | `new Date()` in the WebView reads the device clock. No time server is used. |
| Updates | A single `setTimeout` fires just after each minute boundary. The DOM only changes when a value changes. The date rolls over at midnight. |
| Pause / resume | `onPause()` stops the JavaScript timer and calls `webView.onPause()` and `pauseTimers()`. `onResume()` re-reads the clock and reschedules the timer. |
| Time / time-zone change | A receiver listens for `TIME_SET`, `TIMEZONE_CHANGED` and `DATE_CHANGED` while the clock is visible and resyncs immediately. These broadcasts need no permission. |
| Network | No permissions. `setBlockNetworkLoads(true)` is set. Any request outside `file:///android_asset/` gets an empty response. Navigation away from the page is blocked. |
| Screen | `FLAG_KEEP_SCREEN_ON` is added in `onResume()` and cleared in `onPause()` and `onDestroy()`. No wake locks are used. |
| Fullscreen | Android 4.4+ uses sticky immersive mode. Android 4.1–4.3 hides the status bar and dims the navigation buttons. Android 4.0 uses the fullscreen window and dimmed navigation buttons. The navigation bar is not hidden below 4.4, because the first touch would only bring it back and would not count as a tap. |
| Orientation | `sensorLandscape`: always landscape, in either direction. Change it to `landscape` in the manifest if you want one fixed direction. |
| Scaling | The layout is designed for 1280 × 720. `clock.js` scales it proportionally to fit any screen, using em-based sizes so text stays sharp. |
| Triple tap | The exit triggers when three single-finger taps fall within 900 ms. One or two taps never close the app. |

**Security note for Android 4.0–4.1 (API < 17):** On these versions, every public method of a JavaScript bridge can be reached from JavaScript. This is safe here because the WebView only loads the packaged local page and cannot load network content.

**Date language:** Month names are in English. To use another language, edit the `MONTHS` array in `assets/js/clock.js`.

## Verify offline / no permissions

```
aapt dump permissions app/build/outputs/apk/debug/app-debug.apk
```
The output should list no `uses-permission` entries. You can also test the clock with airplane mode turned on.
