# Control Center overlay — preview 109

This follows the [preview 105 review](launcher-review-1.5.7.md). It is an experimental
feature, not a stable release or a claim of validation on every TV firmware.

## Behaviour

Launcher settings → Remote and sounds → Panel over apps (preview). Default is off;
the trial is enabled on the owner's Homatics. It uses the existing remote-buttons
accessibility service and the existing Settings-hold option. On Home the capsule/header
transition remains in the activity's window. Over another app a temporary, focusable
`TYPE_ACCESSIBILITY_OVERLAY` window hosts the same Control Center UI and springs.
The service does not launch Home to open it. Back closes only the overlay; Home closes
it and performs the normal global Home action. The launcher's settings/sound options,
network and VPN intents, clock, sleep and source actions remain available.

The Home/overlay hosts share actions through a small interface, not through an invisible
second MainActivity. Input launching was extracted without changing its vendor-aware
fallback order. The overlay lists standard passthrough/CEC inputs and shares that launch
path; nonstandard provider-only source menus fall back to the existing Home input picker.
Those TV-specific overlay source paths have not been tested on TCL in this preview.

## Material and blur

All button materials retain their continuous corner mask, broad upper light, lower shade,
soft rim and animated focus depth. The clock's quiet `#10FFFFFF → #0AFFFFFF` translucent
gradient is the base where a readable blurred image is unavailable. With a readable image,
the blurred texture is partially transparent, so it no longer becomes an opaque dark tile.
The existing volume filters are preserved; transparency is not described as a real blur.

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
