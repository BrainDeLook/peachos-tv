# Optional AirPlay companion — 0.1.0-preview

Optional preview bundled with the stable PeachOS TV 1.5.9 launcher, not a universal receiver APK. Device testing uses Homatics Box R 4K Plus, Android 12, with a 32-bit ARMv7 OS.

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
| Native video casting from Rutube | User-confirmed with the preceding platform-player build; not revalidated with Media3 |
| Native video casting from YouTube | User-confirmed with Media3 after preferring AVC/H.264 and rechecked successfully after the shared-User-Agent update |
| Generated local HLS through Media3 | MPEG-TS, fragmented MP4 and MAP/BYTERANGE fragmented MP4 played and returned to the exact previous task on Homatics |
| Native video casting from VK | Picture and sound user-confirmed with Media3 after the shared-User-Agent/prompt-fallback update |

Screen Mirroring and native video casting are separate paths. The local MP4 result does not establish VK, Photos or general internet-video compatibility. Audio-only ALAC is unsupported. Broader audio/video, reconnect, handoff and additional-device testing remains necessary. No Apple certification or DRM-protected playback compatibility is claimed.

## Native-video implementation and checks

The companion requests sender-delegated HLS manifests over AirPlay's reverse/FCUP channel. YouTube's `mlhls://localhost` namespace and correlated status `0`/omitted-status replies are supported; their bodies must still pass the bounded HLS parser. Other delegated hosts, unsolicited/stale replies, explicit HTTP errors and malformed bodies are rejected.

Signed query strings and URL escapes survive manifest handling. Relative and cross-origin variant URLs resolve against the complete source URL and receive distinct local routes. HLS tags determine master/media type, including a root media playlist without a master. YouTube condensed manifests support LF/CRLF and are expanded before media/key/map URLs are resolved. This does not implement arbitrary binary segment forwarding through the iPhone.

Direct HTTP(S) playback starts asynchronously. An initial HLS load failure can make one bounded FCUP retry when a reverse channel exists; expired responses cannot reopen it. An earlier VK capture had valid absolute HTTPS media references with EXT-X-MAP/BYTERANGE and no encryption. A generated local fragmented-MP4 fixture also failed with the old Android MediaPlayer; the earlier malformed-URL hypothesis was not supported by the capture. Before the shared-User-Agent correction, a later VK attempt instead failed with HTTP 400 on direct and delegated root retrieval.

The companion's native-video path now uses Media3 ExoPlayer/HLS 1.4.1 with an 8 MiB encoded-buffer target and 3–8 second time-buffer range. These are load-control settings, not a total-RAM limit. Preparation has a 15-second watchdog; network waits and the one FCUP retry are bounded. Mirroring retains its direct MediaCodec path. Media3 played generated MPEG-TS, fragmented-MP4 and MAP/BYTERANGE fragmented-MP4 fixtures on Homatics, each returning to the exact previous task.

The initial Media3 YouTube test timed out on selected VP9 media despite valid delegated manifests. The user subsequently confirmed Media3 playback works after preferring AVC/H.264. VK picture and sound were then confirmed after applying a shared AppleCoreMedia User-Agent to player HTTP resources and avoiding delayed FCUP fallback from repeated unchanged HTTP-400 loads. YouTube was rechecked successfully after that HTTP change. The compatibility clue is documented in [UxPlay PR #625](https://github.com/FDH2/UxPlay/pull/625) and its [User-Agent change](https://github.com/shrustam/UxPlay/commit/236d2d375fc8aa20f93e734d530dc714c88cea3a). Explicit real-app stop/return confirmation remains outstanding; automatic return passed the local fixture tests. Sanitized load-stage diagnostics omit signed media URLs. The stable launcher release is unchanged.

Android native socket workers now use bounded `poll` arrays to avoid FD_SETSIZE crashes with high socket numbers. Stop paths also join already-ended workers and close their sockets. This replaces the existing readiness wait and adds no launcher polling loop.

Development validation passed 17 host tests of the native HLS parser, five native FCUP-send tests, four native request-state tests and 22 production-Java Media3 lifecycle/HTTP-policy scenarios. Five socket-wait cases passed on Homatics ARMv7, including descriptor 1188, closure/invalid descriptors, timeout, full watch-array indexing and interruption. Device integration with the preceding player covered direct local MP4, FCUP fallback playback, expired-reply suppression and delegated `mlhls` playback; protocol cases included zero/omitted status, HTTP errors and malformed bodies. Real iPhone playback confirmation includes YouTube and VK with Media3 and Rutube with the preceding platform player, with the remaining lifecycle checks noted above. These checks do not establish production stability, universal sender compatibility or a streaming frame-rate guarantee.

An early idle sample was about 11 MiB PSS without an Activity or active decoder. It predates the latest compatibility changes and is not a current streaming memory guarantee. No decoded PCM recordings are written to storage.

## Playback controls and current lifecycle checks

The player supports remote OK for pause/play, Left/Right for timeline selection, OK to confirm and Back to cancel seeking. Holding a direction progressively accelerates the timeline; only a confirmed seek changes decoder/network position. Its lightweight transport stops drawing when hidden or settled.

Repeated VK stop/reconnect and YouTube compatibility were user-confirmed after the lifecycle fixes. A subsequent VK format failure was fixed in the production Media3 compatibility parser and the user confirmed picture/sound. Screen mirroring keeps its aspect ratio and returns to the preceding task on stop. These checks supersede the earlier outstanding stop/return note above, without establishing compatibility with every provider or device.

## Source and packaging

Receiver APKs must be accompanied by matching complete source ZIP and GPL license. The portable builder takes `-AirPlayApk`, `-AirPlaySource` and `-AirPlayLicense` together, embedding `PeachOS-AirPlay.apk`, `PeachOS-AirPlay-source.zip` and `LICENSE-AirPlay.txt`. Source must reflect the shipped binary, not an earlier prototype snapshot.

Companion source includes UxPlay, libplist, native build definitions, the OpenSSL 3.3.2 dependency and its official source archive/license. Native video adds Media3 1.4.1 and its Gradle-resolved AndroidX, Guava and Kotlin dependencies. Exact versions, attribution and full license texts are recorded in the module and packaged under the receiver APK's `assets/third-party/`. Its README.md, PEACHOS-PROTOTYPE.md and THIRD-PARTY-NOTICES.md contain revisions, dependency details and limitations. Validated tools are JDK 17, Gradle 8.9, Android SDK 34, NDK 27.0.12077973 and CMake 3.22.1. The receiver still uses development signing. Do not present it as a production-ready or universal release.
