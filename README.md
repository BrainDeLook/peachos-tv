# PeachOS TV

![PeachOS TV — A calmer home for Android TV](docs/images/brand.svg)

**Your apps. Your posters. A calmer home screen.**

PeachOS TV is a minimal launcher for Android TV and Google TV, built around a dock of favourite apps, large artwork, and quiet glass effects. It replaces your home screen, while your apps and subscriptions stay where they are.

[Download APK](https://github.com/BrainDeLook/peachos-tv/releases/latest) · [Русский](README.ru.md) · [Installation](#install) · [Build from source](#build)

> PeachOS TV was previously called **CleanYourTV + Art**. Existing users can update in place: the package ID and signing certificate are unchanged. Despite the name, this is a launcher, not a replacement operating system.

## A home screen built around what you watch

![Large portrait artwork above the app dock](docs/images/home.png)

- **Artwork for the focused app.** Choose a source for each app: all Android TV cards, continue watching, Lampa history, or a specific published channel.
- **Room for the artwork.** Five large portrait posters, with landscape and square previews using their own proportions. Captions appear only for the selected poster.
- **A quiet dock.** Responsive spring focus, a subtle highlight, compact clock and settings, and a glimpse of the app grid below.
- **Soft interface sounds.** A short navigation tick and a pair of opening/return cues, preloaded without delaying app launch. Turn them off in Launcher settings → Interface sounds.
- **Lightweight glass.** Restrained translucent materials with no live background capture or blur buffers. Glass can be turned off.
- **Your layout.** Rearrange apps and choose which ones belong in the dock.
- **A predictable fallback.** Empty sources show the app name. With cards disabled, metadata backgrounds or a calm colour glow can remain; YouTube and SmartTube have a static background by default.

## Made for TVs and streaming boxes

![Compact source picker with green connection indicators](docs/images/sources.png)

On TVs with available inputs, a compact button next to settings opens the HDMI/source picker. Green dots show connected inputs; more than one can be connected. Devices without switchable TV inputs keep the simpler header.

CEC device names are preserved. On supported TCL firmware, a confirmed physical HDMI connection is used when its sole CEC child incorrectly reports standby. The remote's native Input button keeps its existing behaviour.

## Controls

| Action | Remote control |
| --- | --- |
| Enter artwork from the dock | Up |
| Browse artwork | Left / Right |
| Open a card in its app | OK |
| Return from artwork to the dock | Down / Back |
| Open the launcher menu | Hold OK on the settings gear |
| Choose artwork sources | Launcher menu → Top shelf |
| Rearrange apps / edit the dock | Launcher menu → Reorder / Edit dock |

Apps must publish their cards through Android TV Preview / Watch Next. Lampa history uses Lampa's published history channel. Private app databases are not read, and root is not required. Availability and freshness depend on the publishing app; empty or temporarily refreshed channels can show no cards.

## Install

Requires **Android TV / Google TV 8.0+ (API 26)**. This APK is not for Samsung Tizen or LG webOS.

1. Download `PeachOS-TV.apk` from [Releases](https://github.com/BrainDeLook/peachos-tv/releases/latest).
2. Install it on your TV or box and open **PeachOS TV**.
3. Choose it as the default Home app if your firmware allows it. ADB can be used as follows.

```sh
adb -s <device> install -r -g PeachOS-TV.apk
adb -s <device> shell am start -n com.gathin.tvhome.backdrops/com.gathin.tvhome.MainActivity
```

After confirming that it opens and your remote works:

```sh
adb -s <device> shell cmd package set-home-activity com.gathin.tvhome.backdrops/com.gathin.tvhome.MainActivity
```

These commands do not debloat the device or disable its original launcher. Firmware may restrict Home replacement. Keep the original launcher available so you can switch back in system settings.

**Updating:** install the newer signed APK over the existing one. Do not uninstall first if you want to preserve settings. The package remains `com.gathin.tvhome.backdrops`, including for older CleanYourTV + Art installations. The original `com.gathin.tvhome` can remain installed alongside it.

## Data and network access

No account or API key is required by the launcher. Artwork may be fetched from URLs published by your apps. When metadata backgrounds are enabled, title matching can contact Stremio/Cinemeta services. Lampa TMDB image links are upgraded from HTTP to HTTPS.

With a card source selected, full-screen metadata backgrounds for that app are disabled. The decoded thumbnail cache is limited to 2 MiB, disk thumbnails to 32 MiB; one background worker loads artwork. Inter fonts are bundled locally.

Screenshots show a real installation. App logos and film/series artwork belong to their respective owners; they are not supplied media subscriptions or included streaming content.

## Build

Use **JDK 17**, **Android SDK Platform 34**, **Build Tools 34.0.0**, and the bundled **Gradle 8.9** wrapper. Set `JAVA_HOME` and `ANDROID_HOME`.

```sh
cd launcher
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

On Windows, use `gradlew.bat`. The APK is written to `launcher/app/build/outputs/apk/release/app-release.apk`.

Release APKs currently use the project's original local debug signing certificate for update compatibility. **The private signing key is not in this repository.** A self-built APK signed with another key cannot update an installed release. Set `ANDROID_USER_HOME` to the directory containing your original `debug.keystore` only if you already have that key.

## Tested devices

- **Homatics Box R 4K Plus / Android 12:** dock and grid navigation, portrait/landscape cards, history sources, card opening, Home return, and hiding the source control when no passthrough inputs exist.
- **TCL Smart TV Pro / Android 12:** compact source picker, connection indicators, HDMI 1 selection, and existing short/held settings actions.

245 unit tests pass; release lint reports no errors. Firmware and remote behaviour vary, and smooth 60 fps is not guaranteed on every device.

## Credits and license

PeachOS TV is an independent fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv) 2.7, based on commit `ab7a89e7543f2ef12aac0fa66a4d58c7f4452781`. Metadata/background work was adapted from [Vibe Launcher](https://github.com/maydings/vibe-launcher) v1.9.3, commit `fe00ad13730146def04275967e1df4b8365b670e`.

- Code: MIT; see [LICENSE](LICENSE), [CleanYourTV license](LICENSE-CleanYourTV), and [Vibe Launcher license](LICENSE-VibeLauncher).
- [Inter 4.1](https://github.com/rsms/inter/releases/tag/v4.1): [SIL OFL 1.1](launcher/FONT-LICENSE-INTER.txt). Original Outfit resources retain their [OFL license](launcher/FONT-LICENSE-OFL.txt).
- PeachOS TV's original peach-coloured screen mark and branding assets are included under this repository's MIT license.

This Android TV launcher is not affiliated with Apple, Google, the unrelated peachOS Linux project, or the apps shown in screenshots. No Apple logos, SF fonts, or SF Symbols are bundled as PeachOS TV branding.
