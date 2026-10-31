# Optional AirPlay companion — preview 127

Experimental work, not a stable release or universal receiver APK. Initial device testing uses Homatics Box R 4K Plus, Android 12, with a 32-bit ARMv7 OS.

The fourth round Control Center shortcut remains Search without a trusted companion. Installing `com.gathin.peachos.airplay`, signed with the launcher's certificate, replaces that slot with an AirPlay on/off shortcut without changing panel layout. Blue means both discovery services registered, not that an iPhone is connected. The integration is shared by Control Center on Home and over applications.

The portable setup wizard offers this module as an initially unchecked option. Launcher-only installation remains available. The installer checks Android API 24+ and actual device ABIs: the current receiver is **armeabi-v7a only**. An ARM64 processor does not guarantee a 32-bit-compatible OS. Launcher-update-only mode does not add the receiver or perform cleanup.

## Permission and lifecycle

The module setup screen offers Enable/Disable and Android's visible **Draw over other apps** permission flow, needed to open an incoming cast window. The user grants this permission in Android settings; installation and the launcher do not silently grant it. Receiving uses a foreground notification and enabled receiving is restored after boot.

On iPhone, select `PeachOS TV · <device model>` in Screen Mirroring. Incoming casts open a fullscreen Activity in a transient task. Stopping mirroring closes that task and returns to the previous screen. Disabling receiving unregisters discovery and stops the server, Activity and codecs. This prototype does not enforce a receiver password; use it on a trusted local network.

The separate GPL-3.0 APK/process is based on [gzhanlei/uxplay-android](https://github.com/gzhanlei/uxplay-android/tree/d9646d76cd416a79c5d800d7f306f1b11c7e4b2d), commit `d9646d76cd416a79c5d800d7f306f1b11c7e4b2d`, with native HLS/media changes adapted from [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server/tree/a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea), commit `a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea`. The MIT launcher contains optional signature-protected IPC, event-driven status observation and a vector icon. It does not run a native receiver, decoder or permanent polling loop.

## Current real-device result

| Check | Result |
|---|---|
| Search fallback, shortcut bounds, ready-state blue icon | Verified |
| Signature restriction, enable/disable, sequential discovery | Verified |
| iPhone Screen Mirroring | Moving picture confirmed; user reports sufficiently smooth motion |
| Portrait aspect ratio | Aspect-fit correction confirmed by the user; portrait screen is no longer stretched |
| Stop mirroring on iPhone | Return to the previous screen confirmed |
| Native playback of a generated local MP4 | Playback and return to the exact same prior task verified |
| Native video casting from Rutube | Confirmed working by the user |
| Native video casting from VK | HTTP 400 before media download; still unresolved |
| Native video casting from YouTube | Fails before Java playback callback; FCUP/reverse channel diagnosis pending |

Screen Mirroring and native video casting are separate paths. The local MP4 result does not establish VK, Photos or general internet-video compatibility. Audio-only ALAC is unsupported. Broader audio/video, reconnect, handoff and additional-device testing remains necessary. No Apple certification or DRM-protected playback compatibility is claimed.

An early idle sample was about 11 MiB PSS without an Activity or active decoder. It is one device's idle sample, not a streaming memory guarantee. No decoded PCM recordings are written to storage.

## Source and packaging

Receiver APKs must be accompanied by matching complete source ZIP and GPL license. The portable builder takes `-AirPlayApk`, `-AirPlaySource` and `-AirPlayLicense` together, embedding `PeachOS-AirPlay.apk`, `PeachOS-AirPlay-source.zip` and `LICENSE-AirPlay.txt`. Source must reflect the shipped binary, not an earlier prototype snapshot.

Companion source includes UxPlay, libplist, native build definitions, the OpenSSL 3.3.2 dependency and its official source archive/license. Its README.md, PEACHOS-PROTOTYPE.md and THIRD-PARTY-NOTICES.md contain revisions, dependency details and limitations. Validated tools are JDK 17, Gradle 8.9, Android SDK 34, NDK 27.0.12077973 and CMake 3.22.1. The receiver still uses development signing. Do not present it as a production-ready or universal release.
