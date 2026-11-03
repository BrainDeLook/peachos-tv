# Reliability review follow-up — 2026-11-03

Included in v1.5.11.

- MainActivity registers its default-network and VPN observers independently and records successful registration. A firmware refusal is logged without aborting Home. Destruction releases only registered observers and still attempts the second release if the first fails.
- The permanently hidden Inputs pill no longer subscribes to TV input events or probes sources at startup, resume and first draw. Available sources are recalculated immediately before opening Home's Control Center.
- Network updates still reach an open Control Center. The hidden legacy Wi-Fi pill skips its network enumeration, SSID queries, spans and tint work. With neither a visible pill nor an open panel, resume also skips the extra pre-draw listener and invalidation.
- TMDB persisted records are validated at the cache boundary. Malformed timestamps, wrong value types, legacy formats, invalid image paths, expired values and future timestamps are cache misses. Failed network searches are not cached. Positive results retain their 30-day TTL and negative results their 3-day TTL.
- Cache cleanup is lazy on the existing TMDB worker, at most daily for age-based cleanup; writes also enforce a 512-entry cap, evicting the oldest valid entries. There is no new timer, service or continuously retained mirror of the preference map.
- Wallpaper decode reports allocation pressure separately from an unreadable file. Allocation failure keeps the image in rotation and the current wallpaper visible, and defers further work for at least a minute using the existing update tick. Failed/unsupported hardware copy preserves the already-decoded software image. Unpublished temporary bitmaps are released on failure.
- Removed the unused Http.download method. Its obsolete cancellation test now tests the active Http.get entry point. R8 already removed the old method from release APKs, so this source cleanup is not claimed as a runtime speedup.

Validation: release assembly, lint with zero errors, 313 JVM tests, 73 fault-injection checks against the actual network lifecycle blocks/wallpaper decoder, 718 ControlCenter network checks and 35 native UI/navigation checks on Homatics. The real MainActivity also launched successfully in the isolated diagnostic package. The diagnostic package was removed afterwards.

The signed v1.5.11 release APK was installed on TCL while TVActivity/HDMI remained active. Its previously enabled remote-key helper was rebound and verified, without changing the source or volume configuration. Existing HDMI material, Wi-Fi text fades and tint fixes are included.

Not changed: synchronous reorder catalogue rebuilding, legacy header configuration branches, debug logging and Plex authentication storage. A single initial diagnostic catalogue load took 391 ms; this is not a measurement of reorder latency or a before/after FPS comparison. Those paths need a separate focused change rather than a blind async conversion or removal of diagnostic logs.

Reproduce fault injection with JDK 17:

```powershell
python tools/test_review_boundaries.py --java-home <jdk17>
python tools/test_control_network.py --java-home <jdk17>
```

The fault-injection tests use system facades and do not alter a real TV or deliberately exhaust its RAM.
