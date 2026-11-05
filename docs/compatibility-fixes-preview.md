PeachOS TV 1.5.16-preview1
========================

Implementation checkpoint for the [v1.5.15 compatibility review](compatibility-review-2026-11-05.md). This preview is not a published stable release.

Settings hold keeps its existing purpose: Control Center can open over applications and HDMI without switching to Home. Existing visual materials, focus effects and panel animation parameters are retained. Neither global interception nor volume emulation is enabled automatically.

- C01/C13: TV metadata has typed permission/provider/table/temporary statuses. Missing providers and partial observer registration cannot crash Home. Channels, preview and Watch Next failures are isolated; available content is retained and Settings no longer reports false synchronization success.
- C02/C07–C09/C16: one bounded coordinator handles delivery to both legacy remote services. It executes a mapped action once, preserves native HOME observation, cancels abandoned gestures on lifecycle events, finishes cancelled learning within a bounded interval, distinguishes vendor scan collisions, includes scan-only Settings bindings and reports effective service readiness. Optional software volume repeat has a finite lifetime; its default and native HDMI/CEC volume behavior are unchanged.
- C03/C05/C06: restore continues independent package operations after bridge failure or uninstall. Native Home and role changes are compared against the saved state. A private installation generation prevents old backups from overwriting a later clean install. Service usage reasons preserve later Settings/remap dependencies. Partial recovery writes `restore-result.json` and reports remaining conflicts rather than claiming complete success.
- C04: installer observes a distinct verification screen before each HOME intent and shell HOME keyevent. It observes the full bounded transition window and records typed results. Physical-remote verification stays explicitly unverified. Old APKs can use a resolved system Settings screen as the origin; lack of an observable origin is not treated as success.
- C10/C11: Home and overlay use the same normalized input catalogue, Coocaa/vendor fallback, hardware tuner selection, CEC child labels and TCL/Sony status rules. Failure of an optional TIF/VPN API does not cancel the entire panel. Source discovery remains on demand; no new persistent polling is introduced.
- C12/C14/C15: settings candidates are checked as activities, with launch-time fallback on permission/component changes. Sleep reuses a connected remote service before asking for a separate helper. Network selection follows the default route; a stale or unvalidated Ethernet link cannot hide an active Wi-Fi route. Ambiguous VPN transport does not guess Ethernet.

Verification at this checkpoint:

- JVM tests: 356 passing, including new input catalogue, network route, metadata observer and remote-event policy regressions.
- PC setup: 109 passing tests, including uninstall recovery, later Home/role selection, private generation, late Settings dependency and delayed stock Home.
- Actual-source fault harnesses: system settings launch/resolution, Control Center network updates and existing network/wallpaper resource boundaries.
- Release variant compilation, R8/resource shrink and release vital lint passed during preview assembly. Existing APK signing identity is retained.

New hardware checks of this preview have not been performed. In particular, physical Settings over HDMI, native CEC repeat, OEM boot policy and frame timing are not proven by these local tests. TCL recovery remains outside this checkpoint.

The review's broader roadmap remains separate work: a user-facing compatibility report/export, Android instrumented/TV AVD matrix, complete APK preflight and wireless ADB pairing UI. They are not silently represented as completed features in this preview.
