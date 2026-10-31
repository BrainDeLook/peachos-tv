# Control Center overlay — preview 115

This follows the [preview 105 review](launcher-review-1.5.7.md). It is an experimental
feature, not a stable release or a claim of validation on every TV firmware.

## Behaviour

Launcher settings → Remote and sounds → Panel over apps (preview). Default is off;
the trial is enabled on the owner's Homatics. It uses the existing remote-buttons
accessibility service and the existing Settings-hold option. On Home the capsule/header
transition remains in the activity's window. Over another app a temporary, focusable
`TYPE_ACCESSIBILITY_OVERLAY` window hosts the same Control Center UI and springs.
The service does not launch Home to open it. Back and Home smoothly close only the
overlay, leaving the underlying app active. Once closed, a subsequent Home press follows
the normal Home handling. The launcher's settings/sound options,
network and VPN intents, clock, sleep and source actions remain available.

The Home/overlay hosts share actions through a small interface, not through an invisible
second MainActivity. Input launching was extracted without changing its vendor-aware
fallback order. The overlay lists standard passthrough/CEC inputs and shares that launch
path; nonstandard provider-only source menus fall back to the existing Home input picker.
Those TV-specific overlay source paths have not been tested on TCL in this preview.

## Material and blur

All button materials retain their continuous corner mask, broad upper light, lower shade,
soft rim and animated focus depth. The clock's quiet `#10FFFFFF → #0AFFFFFF` translucent
gradient is the overlay base where a readable blurred image is unavailable. Its contrast
tint is 40% with a readable backdrop and 50% without one; the 60–65% trial was too dark.
Home keeps its original sampled material and 20% tint, without overlay transparency or
extra darkening. With a readable image in the overlay,
the blurred texture is partially transparent, so it no longer becomes an opaque dark tile.
The existing volume filters are preserved; transparency is not described as a real blur.

Preview 111 raises only the in-app overlay's overall background scrim from 31% to 40%
for readability. Home retains its previous 31% scrim; button materials are unchanged.

Home's previous Settings-hold defect was reproduced with the physical remote. PixelCopy
succeeded **before** the poster row appeared, permanently freezing a plain dark background
into the controls. A new capture waits for the row/artwork reveal to settle, up to a bounded
deadline. A failed or not-ready capture is not stored as a valid prepared background.
The owner confirmed that blur appeared after this correction.

To speed subsequent openings, Home keeps at most six 160-pixel prepared backgrounds
(about 0.33 MiB at 16:9), keyed by selected app, settings revision and saved card source.
They expire after 60 seconds and are evicted on the existing memory-pressure path.
A cache hit starts the existing reveal without another capture/blur/readiness wait.
This palette cache does not cache or delay actual poster metadata updates.

Over another app, Android 11+ can provide one accessibility screenshot before the window
is added. A short-lived GPU renderer downsamples it directly to 160 pixels; a full-size
software bitmap is not allocated. The hardware buffer, renderer and image reader are
closed, then the small texture is blurred once off the UI thread. There is no screenshot
loop or per-frame blur. The service declares only the screenshot capability additionally;
it still does not request access to the other app's view hierarchy.

Secure/unsupported screenshots, a timeout, or a nearly black unreadable control region
use the translucent material rather than an opaque black captured rectangle. A dark-region
check is only a fallback heuristic; it does not prove that a video is protected. Nothing
attempts to bypass protected content or change the player's security flags.

On the tested Homatics, `wm disable-blur` reports **Blur supported on device: false** and
**Blur enabled: false**. Kinopoisk playback also prevented a normal shell screenshot.
Consequently this device cannot supply true live blur of that protected video through the
public paths used here. Transparency/volume remain available, while real sampled blur is
retained for Home and readable app content. The clock's existing background itself is a
translucent gradient, not a capture or a blur.

## Lifecycle and checks

Capture/configuration work has one worker; it expires after two idle seconds. Cancellation
uses a generation token. Back removes the overlay window, restores the service's previous
event mask and unregisters network callbacks. Short UI sound tails are released after
closing. Unbinding/destroying the service cancels work and removes its window.

On Homatics the owner opened the preview using the physical Settings button. While the
overlay was displayed, Android still reported Jellyfin's activity as resumed. Network-tab
navigation and Back were exercised; Back left Jellyfin resumed, removed the extra window
and restored the helper's zero-event subscription. The owner also opened it over Kinopoisk
playback; Android reported the player activity, rather than Home, as resumed. A readable
Jellyfin UI capture's one-time blur took 5 ms in that run.

These runtime checks began on preview 106. Preview 109 includes the final translucent
material, dark-image fallback and Home cache. Performance while playing protected video,
final material appearance and cold vs warm opening should be assessed separately; no
locked-60-FPS or zero-leak claim follows from a short functional run.

Primary Android references:

- [Accessibility overlay window](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#TYPE_ACCESSIBILITY_OVERLAY)
- [Screenshot API and declared capability](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))
- [Cross-window blur support and video limitations](https://source.android.com/docs/core/display/window-blurs)

## Stability and performance follow-up (previews 112–115)

Opening cancellation now owns the complete request, including its screenshot deadline and
queued configuration work. Home/Back/Escape consume the complete dismiss key gesture even
when a window disappears before key-up. Back during preparation cancels the pending opening
instead of reaching the app under it. Stale screenshots release their hardware buffer without
GPU readback or blur. Service reconnection/unbind cancels the old controller and key timer.

VPN app discovery and source-tab detection run on the existing worker before window attachment.
Unchanged theme config no longer rewrites its internal fallback copy on each opening, and the
wallpaper collection is scanned only when needed. The Settings hold deadline uses the original
key-down uptime rather than adding another full hold timeout after delayed delivery.

Overview, network and VPN page views/materials are reused within one opening, not globally.
Input rows are refreshed when selected. Cached pages reset outgoing alpha, focus/scale animations
and backdrop position when reattached. Switching pages applies the last network snapshot instead
of querying ConnectivityManager again; network callbacks refresh it and coalesce duplicate UI work.
Backdrop shader matrices update only when the sampled screen position changes. There is still
at most one tiny snapshot/blur per opening, no live capture loop, and the existing visual parameters and
spring/crossfade durations are preserved.

Reusing Views alone did not solve the tail latency. Preview 113 retains attached, invisible pages
and at most two page textures, instead of detaching pages and using `withLayer()` on each fade.
Inactive pages reject touch/focus/accessibility and do not affect the selected page's measured height.
Repeated network values no longer invalidate those textures. Preview 114 additionally gates retained
page/body layers by measured height and a two-MiB RGBA limit per layer, so a long VPN/source list
cannot create an unbounded permanent render target. All page/body layers are released on dismissal.

Preview 114 builds the controls in the window's first traversal and starts the floating reveal at
pre-draw, removing the previous empty-window traversal and extra posted initialization. Home's capture
and capsule handoff scheduling stay unchanged. Brief debug timings separate configuration, screenshot,
control construction and reveal preparation from the physical long-press threshold.

Queued screenshot callbacks are allowed to finish their stale-request cleanup after service closure;
deleting every Handler message would skip hardware-buffer release. Storage/discovery failures reset
the pending request, and network subscription failures leave the other controls available. These two
platform failure branches were reviewed but not artificially injected on the real device.

Validation uses the same six Right/Left tab pairs over the Kinopoisk player on Homatics.
Preview 111 baseline: 111 rendered frames, gfxinfo p50 29 ms / p95 61 ms, 12 deadline-missed
frames. A trace confirms expensive input work (median 10.9 ms) and repeated layer builds
(median 7.4 ms). These counters include queueing and are not a locked-FPS measurement.
Preview 112's view-only cache improved p50 to 21 ms but p95 was still 69 ms; tracing showed repeated
layer construction. Preview 113's retained layers measured p50 16 ms / p95 32 ms on the first tab run,
then 14 ms / 23 ms on a repeat. The first run still had two roughly 400-ms frames: its trace records
six cold shader compilations totalling 374 ms. Warm results must not hide that cold-start limitation.

On preview 112 the owner also exercised three Settings/Back openings and one Settings/Home opening
over Kinopoisk playback. The player remained resumed, the overlay window was removed and the service
returned to its zero-event subscription. RenderThread frame-start intervals in the retained closing
segments had medians of 16.1–16.7 ms; this is not a SurfaceFlinger presentation or locked-FPS claim.
The trace buffer retained only the last two opening/closing segments, so it is not a per-cycle audit
of all four user actions. Final preview 114 checks are recorded separately below.

Preview 114's cold opening exposed the main remaining startup cost: 30 driver shader compilations
totalling about 1,018 ms, including a 725-ms layer build. Two subsequent openings prepared their
reveal roughly 205–206 ms after the hold callback (excluding the system's long-press interval).
The config took 8–11 ms and snapshot preparation 50–55 ms, so reducing the screenshot timeout or
weakening the hold gesture would not address the observed cold bottleneck.

Preview 115 rasterizes only the in-app material's six static lighting components per geometry,
then draws their small cached masks/textures with the same independent dynamic alphas and order.
This preserves focus depth, the soft rim and underlying sampled blur/transparency while avoiding
the same clipped-gradient shader combinations on every new control geometry. Home keeps the
original GPU material. Software/GPU edge antialiasing can differ slightly at individual pixels.
Only the surface uses RGBA pixels; the white light/rims and black shade use `ALPHA_8` coverage
masks, restoring their constant color in the draw Paint. Five typical geometries at density 1.5
require about 0.934 MiB of CPU bitmap storage rather than 2.49 MiB for six RGBA images each.
The lighting LRU is capped at two MiB, with an oversized-entry fallback. It is cleared on existing
Home memory trimming and on service memory pressure even when no launcher Activity exists.
Active materials retain their images until the panel closes; GPU copies are additional memory.

### Final Homatics checks, 2026-10-31

Preview 115 is installed with the original certificate and user settings. The same six Right/Left
tab pairs over Kinopoisk rendered 113 frames: gfxinfo p50 **13 ms**, p95 **24 ms**, seven deadline
misses. Baseline 111 was 29/61 ms. Input handling's trace median fell from 10.9 to 2.6 ms.
The first visit to the network page still produced a roughly 133-ms frame; tracing shows two
remaining shader compilations totalling 92 ms. This is improved responsiveness, not zero jank.

A clean repeat of physical Settings/Back and Settings/Home produced 83 frames with gfxinfo
p50 **18 ms**, p95 **29 ms**. Its two opening segments had RenderThread frame-start medians
of 16.3 and 15.4 ms; the two closing segments were 16.6 and 17.5 ms. Warm reveal preparation
took 178–268 ms after the hold callback in those two openings. Window-buffer allocation and
first draws still produced occasional long frames; these are not guaranteed 60-FPS results.

The first 115 opening's diagnostic log recorded 329 ms to construct controls and another 474 ms
to prepare the reveal, versus 334+804 ms on the preceding 114 cold opening. Those are single
instrumented openings rather than a controlled startup benchmark. The rest of that first 115
navigation pass was excluded from the FPS comparison after the owner accidentally left the app.

The real box uses density 2: lighting images logged for 208×208, 208×96, 96×96, 432×192 and
432×88 total **1,740,672 bytes (1.66 MiB)** of CPU bitmap allocations. Initial geometry raster
costs were 5–15 ms each. Page textures and driver copies remain separate from this figure.
Process PSS after tab navigation was about 105 MiB, and about 102 MiB after closing and the
memory-pressure check; total PSS is workload/GC dependent, not a fixed cache budget.

An injected `RUNNING_LOW` trim was followed by Down/Up/Back navigation successfully. The final
window dump had no Control Center overlay, Kinopoisk's existing player remained focused/resumed,
the remote service restored its zero-event subscription and the tested process logged no crash.
All 276 release unit tests passed; release lint reported zero errors and 106 existing warnings.
These are short device checks, not a long-duration leak/soak test or validation on every firmware.

Additional implementation references:

- [Hardware layers and animation costs](https://developer.android.com/topic/performance/views/hardware-accel-views)
- [Handler scheduling uses uptime](https://developer.android.com/reference/android/os/Handler#postAtTime(java.lang.Runnable,long))
- [Alpha-only bitmap masks](https://developer.android.com/reference/android/graphics/Bitmap.Config#ALPHA_8)
