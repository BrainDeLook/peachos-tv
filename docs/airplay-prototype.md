# Optional AirPlay companion — preview 127

The fourth round Control Center shortcut remains Search when no trusted companion
is installed. Installing `com.gathin.peachos.airplay` signed with the launcher's
certificate replaces that slot with an on/off AirPlay shortcut, preserving layout.
The icon turns blue only after the receiver has registered both mDNS services.
This applies to the same Control Center implementation on Home and over apps.

The receiver is a separate GPL-3.0 APK/process based on gzhanlei/uxplay-android
(d9646d76cd416a79c5d800d7f306f1b11c7e4b2d). The launcher contains only optional
signature-protected IPC, status observation and a vector icon. No AirPlay native
library, media decoder or permanent polling loop runs in the launcher.

Prototype outputs and complete receiver source are maintained separately. Receiver
build details, permissions and limitations are in its PEACHOS-PROTOTYPE.md.
Initial device: Homatics Box R 4K Plus, Android 12, 32-bit ARMv7 OS.
This is an experimental branch, not a stable release or universal receiver APK.

Verified: absent-module Search fallback; equal shortcut bounds; ready blue state;
on/off foreground service; signature restriction; sequential discovery callbacks.
Real iPhone screen/audio and reconnect testing are required before release.
Receiver idle sample was about 11 MiB PSS, without an Activity or active decoder.
The Draw over other apps permission was separately approved for this device.

## Current real-device result

Discovery works, but initial iPhone tests produced a black/waiting screen and
incorrect audio. Those tests are not a successful mirroring validation. Receiver
follow-up fixes retain configuration NALs before Surface creation, align mDNS
features with native UxPlay, reject unsupported ALAC instead of decoding it as AAC,
and keep the last frame visible while a mirroring session is idle. Retesting these
changes is pending. Do not publish this prototype as stable.

Receiver source snapshot: c05dd79 (local feat/peachos-companion branch), archived
with GPL license and native sources alongside the prototype APK.
