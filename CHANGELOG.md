# Changelog

## 1.5.2 — 2026-10-30

- Improve focus animation performance by caching attached app tiles, including their continuous mask and lighting. Release tile layers on detach and restore them on reattach; preserve the existing scale spring and interrupted-motion velocity.
- Stop changing image filters on every zoom frame. Only the first and last dock tiles invalidate the dock during their scale animation. Cache the full dock material and keep its ambient illumination stable.
- Refine dock spacing and end-tile clearance, substantially reduce icon illumination, and remove dark shadow contours outside app silhouettes.
- Refine the translucent clock capsule and share its font metrics with the control-center clock to keep the opening/closing handoff consistent.
- Keep fully visible posters stationary while moving focus between them; scroll the row only when needed to reveal an edge item.
- Adapt the section heading to artwork underneath the text, anticipate taller incoming posters, reserve animation headroom, and avoid card-to-card focus jitter. During D-pad key repeat, hold the highest reached heading position until the key is released and scrolling settles.

Validation: 258 unit tests pass; release lint has no errors. The preview performance investigation on Homatics Box R4K Plus reduced median dock presentation intervals from 33.37 ms to 16.68 ms in the measured scenario; this is not a guarantee of locked 60 FPS on all devices. Initial control-center openings can still have long frames. See [rendering review](docs/render-performance-review.md).

Package ID and signing certificate are unchanged. Install over the previous version to retain settings.

## 1.5.1 — 2026-10-29

- Play the existing navigation tick when D-pad focus moves between control-center tabs and buttons. Opening the panel or pressing against a focus boundary does not add a tick.
- Add a distinct, synthesized two-note sound for selecting TV sources, using the existing sound toggle and SoundPool. App-opening, Home-return and navigation sound files remain unchanged from 1.5.0.
- Give control-center buttons soft directional lighting, lower-edge shading and a small elevation shadow. Focus lighting transitions smoothly; paths and gradients are cached without additional blur buffers.
- Match the source buttons' corner radii to the other horizontal control-center buttons. The material mask and shadow follow the same outline.

Package ID and signing certificate are unchanged. Install over the previous version to retain settings.

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
