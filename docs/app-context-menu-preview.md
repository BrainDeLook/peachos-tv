# App context menu preview 116

Long OK opens a compact side menu beside the transformed tile. Placement switches left/right and clamps to the screen. Short OK continues to launch; the release of the opening long press cannot activate a row. Back closes with the reverse spring and restores the selected app. Home closes before returning to the first dock app. Uninstall opens Android confirmation, and is omitted for system apps.

The selected icon retains its scale and tone. Its very narrow colored halo follows the same continuous silhouette, with 1.5dp downward offset and 2.5dp soft blur prepared once in a shared ALPHA_8 bitmap (cache capped at 256 KiB). Focus only animates opacity and transforms. The cache is evicted with discarded app artwork under memory pressure.

Control Center uses a white focused surface, dark foreground and a small retargetable spring on Home and in-app overlays through the shared ControlBackdrop. Existing blur, material volume, page caching and reduced-motion settings remain in use.

Validation on Homatics: release assembly, 281 unit tests and lint passed; upgrade signature preserved. Both menu sides, selected row, reverse close, submenu/back, and white Control Center foreground checked. A short warm Home control-focus sample rendered 112 frames, p50 16ms/p95 34ms; this is device-specific evidence, not a 60fps guarantee. In-app physical-remote visual confirmation remains pending.

## Preview 117 corrections

- Halo colors continue the artwork at each local edge through clamped bitmap sampling, rather than selecting the most vivid logo color. A small half-resolution ARGB halo is cached per app and geometry (512 KiB cap), alongside the shared silhouette masks.
- Menu close transfers focus directly to its anchor before detaching the menu, preventing ViewRoot from briefly focusing the first app.
- Finished blur explicitly invalidates glass materials before building animation layers. Home capture no longer waits up to 900ms for network poster loading; only settled snapshots are stored for reuse.
- Cached pages reserve 5dp of internal space for focused scaling. Round shortcut containers allow their child to grow beyond the slot, preventing clipped sides.

Validation: 281 tests, release assembly and lint; Homatics screen capture shows glass on first opening and frame-by-frame menu close retains Lampa. In-app physical remote confirmation remains pending.
