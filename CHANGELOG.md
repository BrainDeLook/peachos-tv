# Changelog

## 1.5.0 — 2026-10-29

- Add a compact control center with quick settings, network status, TV inputs where available, and detected VPN clients. Press the gear to open it; hold OK for the launcher menu.
- Add optional standby through an accessibility helper on Android 9+, without screen-content access or remote-key filtering.
- Animate header separation, tab focus and workspace changes. Keep the control center in Home's window so the returning clock and gear share the same rendered frame.
- Refine continuous tile corners, subtle focus lighting, typography and portrait/landscape shelf headings.
- Add optional app-opening and Home-return sounds, and retain the launched application's selection when returning Home.
- Load the app catalogue outside the UI thread, discard stale artwork work and defer channel initialization until after the first catalogue frame.
- Retain the existing 2 MiB thumbnail LRU on ordinary Home returns, while releasing it on memory pressure. Keep the disk cache capped at 32 MiB.
- Decode the first wallpaper in the background and avoid recycling hardware images while rendering may still reference them.

Validation: 248 unit tests pass; release lint has no errors. Control-center navigation and header return were checked on TCL Smart TV Pro and Homatics Box R 4K Plus. Cold first-use frame drops are not completely eliminated.

Package ID and signing certificate are unchanged. Install the new APK over the previous version to retain settings; do not uninstall first.
