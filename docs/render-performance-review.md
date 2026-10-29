# Rendering review — 2026-10-30

Reviewed the UI rendering and focus paths, dock decoration, poster loading and scrolling,
control panel transitions, catalogue refresh, wallpaper/backdrop workers, memory trimming,
and sound lifecycle. Release lint and the complete unit suite cover the project separately.
This is a performance review, not a claim that every feature or device has been exhaustively tested.

## Confirmed bottleneck

An Android 12 Homatics Box R4K Plus / Mali-G31 replayed the continuous clip and multiple
lighting/shadow paths of **stationary neighbouring app tiles** during another tile's zoom.
The first attempt (transient layers on just the two animated tiles) did not resolve it.
An atrace capture showed median `flush commands` wall time of 19.2 ms, including roughly
17.3 ms of scheduled RenderThread CPU time. Focus/input/layout processing was much shorter.

## Changes

- Cache attached app tiles as local hardware textures. Detaching a child clears its layer;
  background memory pressure already detaches the adapter and clears recycled holders.
  Reattaching restores the cache. Scrolling no longer drops every tile cache at the end,
  which previously made the next animation replay all the paths again.
- Apply the subtle idle image tone once per focus change, not throughout the scale spring.
  Only two shared tone states remain. The existing spring stiffness, damping, scale and
  interruption/velocity handling are preserved.
- Only the first and last dock tiles invalidate the RecyclerView during zoom, because only
  those tiles change the dock's outside clearance. Interior focus changes do not invalidate it.
- Cache the entire glass band, including its continuous rim and illumination. Reuse the
  bitmap/Canvas when its dimensions match. Cache width is bounded to 2048 pixels; at 1080p
  it retains native resolution for the thin rim. Other uncacheable dock layouts retain direct drawing.
  The cached band's broad ambient light stays centred, so changing apps does not repaint/upload it.
- Reduce selected icon light from 0.92 to 0.70; idle light stays at 0.50.

## Device results

Same installed app data, 1080p launcher surface, 4K/59.94 Hz HDMI output, three passes of
five Right and five Left inputs, with 100 ms sleeps between shell input commands.
Screen recording was off. Android's input command startup adds time between inputs.

| Measurement | Previous preview 85 | Final preview 89 |
| --- | ---: | ---: |
| Median SurfaceFlinger presentation interval | 33.37 ms | 16.68 ms |
| gfxinfo 50th percentile | 53 ms | 25 ms |
| gfxinfo 95th percentile | 73 ms | 30 ms |

Presentation intervals measure actual displayed updates; `gfxinfo` totals also include queueing.
This firmware reports inconsistent legacy/deadline counters and an unavailable GPU completion
timestamp, so neither a zero jank count nor the GPU histogram is proof of perfect frame pacing.
The result supports approximately 60 Hz **median dock updates**, not a promise of locked 60 FPS.

For five Down/Up pairs between the dock and lower apps, preview 87 reduced gfxinfo p95 from
57 ms to 31 ms (p99 200 ms to 36 ms). Preview 87 used the same tile caching but a lower-resolution
dock cache. Preview 88 was also exercised on a TCL Android 12 TV (dock p50 25 ms, p95 44 ms);
there is no matched TCL baseline for a before/after claim.
Preview 89 is installed on both devices. A later preview-88 lower-grid run after waking the
box from HDMI-CEC sleep measured p50 32 ms/p95 48 ms: warm and post-wake runs are not interchangeable.

The measured tile render targets occupied about 2.81 MiB after the mixed navigation run.
This excludes window buffers, source artwork, the dock bitmap and driver scratch allocations.
Total process PSS varies with metadata and GC; it is not equivalent to the tile cache size.
Existing poster RAM/disk limits and worker counts were not increased.

## Remaining limits and checks

- Initial control-panel openings still show occasional long frames; do not claim that shader
  warmup, first texture uploads and snapshot preparation have been eliminated.
- Rapid focus movement, lower-grid transitions and repeated control-panel open/close were
  exercised on hardware. No global display, animation, CPU, GPU or power settings were changed.
- Source selection, audio assets, poster geometry/heading hold, and stored user settings are preserved.
- Release unit suite: 258 tests. Release lint: 0 errors (existing warnings remain).
- Measurements above were taken on the named previews. Their final rendering fixes and subsequent
  clock/icon polish are included in stable 1.5.2; the measurements are not new stable-build benchmarks.
