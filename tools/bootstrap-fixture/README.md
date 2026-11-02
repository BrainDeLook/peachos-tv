# Native bootstrap checks

This fixture is excluded from production source sets. Build it with a separate application ID and run it on an Android TV device; it uses the actual Android JSON, AtomicFile, preferences, Handler, Config, and ConfigRefresh implementations.

From `launcher/`, with the normal Android/JDK environment configured:

```powershell
gradle --offline --no-daemon -I ../tools/bootstrap-fixture/fixture.init.gradle -PlauncherApplicationId=com.gathin.tvhome.bench :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell input keyevent 224
adb shell am start -W -n com.gathin.tvhome.bench/com.gathin.tvhome.BootstrapTestActivity
adb logcat -d -s PeachBootstrapTest
```

The first run checks missing/corrupt/incompatible snapshots, bounded file input, external-storage-free cached reads, preference overrides, removed images, malformed/removed themes, stale publication, coalescing, cancellation, result reuse, and worker shutdown.

Run the actual Home recreation case in a fresh test process:

```powershell
adb shell am force-stop com.gathin.tvhome.bench
adb shell input keyevent 224
adb shell am start -W -n com.gathin.tvhome.bench/com.gathin.tvhome.BootstrapTestActivity --ez refresh true
adb logcat -d -s PeachBootstrapTest PeachStartup
adb uninstall com.gathin.tvhome.bench
```

This second case seeds only the test package's external theme, blocks verification while cached Home draws, selects another app, then verifies that a real theme change applies without losing the selected app or triggering a recreation loop. Keep the screen awake; a system screensaver can invalidate the foreground-window assertions. Neither test changes the real launcher's preferences, HOME default, accessibility services, network, or theme files.
