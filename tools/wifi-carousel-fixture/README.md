# Native Wi-Fi carousel fixture

Excluded from production builds. Uses the actual ControlCenter, WifiCarousel and Android spring with 32 synthetic APs. The real Wi-Fi scanner is suppressed; no radio, connection or permission is changed.

From launcher/ with JDK 17 and Android SDK configured:

```powershell
gradle --offline --no-daemon -I ../tools/wifi-carousel-fixture/fixture.init.gradle -PlauncherApplicationId=com.gathin.tvhome.bench :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell input keyevent 224
adb shell am start -W -n com.gathin.tvhome.bench/com.gathin.tvhome.WifiUiPreviewActivity --ez selftest true
adb logcat -d -s WifiFixture
```

Checks whole AP rows at rest, top action placement, intermediate scroll positions, complete-row settling, fast forward/reverse key repeats, list boundaries, return to the Network tab and cancellation on leaving the tab. Omit `--ez selftest true` for visual inspection. The display must be awake. Uninstall com.gathin.tvhome.bench afterwards; the real launcher is a different package.

The 35 native checks also cover focused masks against every clipping ancestor, both tab-strip boundaries, and directional navigation between overview columns and round shortcuts. Long-SSID checks cover motion, end pause, repeat, focus-loss cleanup, soft scrolling edges and static short names. A rendered-pixel check verifies the focused Wi-Fi glyph is dark on its white background. The production build does not include this Activity or its exported manifest entry.
