# Launcher review — 1.5.7 preview, 2026-10-31

## Changes

### Real app channels

The source picker previously offered Continue watching even when an app had never published
Watch Next data. It now reads the Android TV provider once for the whole settings screen.
Only preview channels belonging to each app and eligible, browsable Continue/Next Watch Next
entries are offered. Tuner channels and orphaned programs are excluded. Missing sources are
shown explicitly; choosing a missing source never substitutes unrelated recommendations.

Channel names come from the publishing app. Stable `internal_provider_id` values are saved
with the package; old numeric IDs migrate when still identifiable. Missing/duplicate provider
IDs retain numeric selection rather than guessing. Lampa history uses its published history
channel; PeachOS does not read another app's private database.

The screen observes channel/Watch Next changes while visible and has a manual synchronization
action that requests publication from apps supporting `INITIALIZE_PROGRAMS`. Apps remain
responsible for publishing content; the launcher cannot invent an unsupported history feed.

### Settings and Home

Settings have five categories: Apps and dock, Cards and background, Appearance, Remote and
sound, and System. Ineffective dock-opacity and wallpaper options are hidden for layouts
where they do not apply. Idle-clock delay is now adjustable. Reset layout/motion preserves
app order, card sources, sounds and remote mappings.

Home while already in the launcher uses the existing focus/scroll path to reach the first
dock tile, including from posters and the lower grid. Returning from a launched app retains
that app's tile. Home dismisses Control Center with the same spring as Back, then focuses
the first tile. Immediate panel dismissal moved from `onPause` to `onStop`: an in-place
Home intent can pause/resume the activity before delivering `onNewIntent`.

### Glass and resource use

When Control Center is opened after another app, the resumed Home window may not yet have
a readable frame. Capture now waits two frame callbacks, with at most three attempts after
a capture failure. The same 160-pixel snapshot is blurred once on a background worker;
there is no per-frame capture, enlarged buffer or continuous blur.

Metadata refresh notifications are debounced. The current row stays visible during the
provider read. Playback timestamps, database item IDs and rank changes that leave the
displayed content unchanged do not rebuild/fade the row. Actual changes wait for navigation
to settle; a focused poster row is not replaced under the user's selection. Decoded artwork
aspect is kept in the view binding, without mutating cached provider metadata.

Metadata queues are bounded to one pending job. Observers and pending refresh callbacks
are removed when their UI stops. Stale results use generation checks. Network reads observe
worker cancellation and response-size bounds; bounded text/image body reads have a total
read deadline. A cancelled image request does not poison the failed-URL cache.
Existing poster RAM (2 MiB) and disk (32 MiB) limits and image-worker count are unchanged.

The review covered activity/service lifecycle, receivers and observers, focus/scroll and
panel transitions, poster/image caches and queues, backdrop workers, local wallpaper decoding,
app-library tasks and sound release. Existing cached tile/dock rendering was preserved.
This is not a guarantee that all defects or firmware-specific problems have been eliminated.

## Validation on Homatics

Installed preview code 105 over stable code 104 with matching signing certificate, without
clearing app data or changing system animation/display settings. The suite contains 276
passing JVM tests; release assembly and lint pass (0 errors; existing warnings remain).

Hardware checks confirmed:

- Lampa launch → Home retains Lampa; another Home selects the first dock tile.
- Home from posters and lower grid selects the first dock tile.
- Control Center opened after another app retains the sampled/blurred material.
- Google Play reports no published channels; Jellyfin and Lampa list their actual channels.
- All five settings categories render and are reachable with D-pad navigation.

Twelve panel open/Back cycles showed no increasing window/view count: cycles 6 and 12 both
had 185 views and one activity/window root. Process PSS was about 102.3 and 102.5 MiB,
respectively. This short run is evidence against a per-opening leak, not a long-run heap proof.
Warm one-time blur work in those cycles took 4–8 ms; the earlier cold capture took 49 ms.

With initial loads settled, two successive four-second stationary-Home samples rendered
zero additional frames. No screen recording was active during frame measurements.

| Scenario | Measurement |
| --- | --- |
| Dock, 3 × (5 Right + 5 Left) | gfxinfo p50 25 ms / p95 29 ms |
| Dock presentation intervals | median 16.68 ms / p95 16.71 ms |
| Dock ↔ lower grid, 4 pairs | gfxinfo p50 15 ms / p95 34 ms |

SurfaceFlinger kept only the last 126 valid intervals in the dock sample. Intervals above
100 ms were excluded as idle gaps; the table is not a worst-case or locked-60-FPS claim.
`gfxinfo` includes queueing and this firmware's legacy jank counters disagree with newer
counters. There is no matched stable-vs-preview performance baseline in this review.
Cold boot, first texture/shader work, long-running playback and TCL were not benchmarked here.

Private screenshots, logs and frame data remain outside the repository under the local
review output directory; they are not publication assets.

## Control Center over another app: feasibility only

Android supports accessibility overlay windows owned by an enabled AccessibilityService.
The existing remote-buttons service could host a focusable overlay without launching Home.
The current panel is coupled to MainActivity, its capsule and view hierarchy, so this needs
a separate host interface and explicit Back/Home/focus/lifecycle handling, rather than an
activity flag change. Whether a player continues playback must be tested per app/device.

A low-overhead candidate would create the window only while visible, keep one UI tree,
remove it after the close spring and avoid continuous screenshots. Network/VPN/source
actions would reuse the current system intents and input discovery.

The current PixelCopy path copies our own window, not the other app. Accessibility screenshots
on supported Android versions require an additional declared capability and can fail on
secure content. Native cross-window blur is Android 12+, GPU-dependent, and can be disabled
during video playback. A readable translucent fallback is therefore required; a live blur
cannot be promised on every TV. No overlay, new capability or permission was enabled by
this preview.

Primary references:

- [Accessibility overlay windows](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#TYPE_ACCESSIBILITY_OVERLAY)
- [AccessibilityService screenshot API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))
- [Android cross-window blur support and fallback](https://source.android.com/docs/core/display/window-blurs)
