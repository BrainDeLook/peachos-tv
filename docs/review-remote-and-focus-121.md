# Remote and Control Center review, preview 121

## Interface fixes

- Control Center page padding reserved room for focus zoom, but `clipToPadding` still clipped that room. Disable padding clipping on the page itself, preserving bounded hardware layers and the existing button geometry. Verified a focused full-width network capsule on the device: both ends remain rounded and visible.
- Pin the selected Home tile while Control Center temporarily owns focus. Reuse the existing activated state and focus animation, with no poster queries or per-frame image processing. Restore real focus before releasing the pin; clear activation on recycling. Verified the selected second dock tile stays enlarged under the panel.
- Keep the existing performance work from preview 119, including removal of the page-position observer on dismissal.

## HDMI volume investigation

On the tested Homatics running Android 12, physical remote input contained a DOWN/UP pair for an approximately two-second hold. With the Remote buttons helper enabled, holding volume stopped after two audible steps. A trial software repeat did not receive volume events and produced no launcher audio-service adjustments. It was disabled and removed from the settings UI.

A device comparison temporarily disabled only the Remote buttons helper and restored its exact previous accessibility configuration. Held volume immediately worked continuously, as confirmed by the user and a stream of HDMI-CEC commands. The helper was restored after this comparison; no firmware or CEC settings were changed.

The AOSP Android 12 input path explains the conflict: [KeyboardInterceptor](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/services/accessibility/java/com/android/server/accessibility/KeyboardInterceptor.java) asks window policy about volume before notifying accessibility services. On TV, [PhoneWindowManager](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/services/core/java/com/android/server/policy/PhoneWindowManager.java) consumes the event for direct audio handling. Neither the launcher key callback nor the dispatcher's native repeat sees this consumed event. AudioService START/END log entries alone must not be counted as volume steps.

There is no per-key exclusion in the public accessibility key-filter API. Disabling the helper was verified to remove the input filter and restore native volume holding. The final installation retains the Remote buttons helper, Settings hold, and Control Center over apps at the user's request; the existing HDMI volume limitation is accepted. No further workaround is applied. Do not replace the active media session, guess a hold from volume broadcasts, or enable a firmware profile globally.

## Existing firmware-specific repeat helper

For configurations that already opt into `consume_volume_keys` and actually deliver volume events, fix duplicate DOWN events resetting the timer and stale releases canceling a newer hold. Keep one timer for both key services, cancel it during service cleanup, and cap a synthetic hold at 15 seconds if a Bluetooth remote never sends UP. These changes do not repair the intercepted Homatics TV path described above.

## Validation

- Release APK built with the existing update signing identity.
- 295 unit tests passed; release lint passed.
- Preview 120 used for interface and physical HDMI comparison; preview 121 removes the unsuccessful user-facing trial while retaining the verified interface fixes and bounded legacy repeat logic.
- Twelve repeated Home panel openings completed. View counts were equal at cycles 6 and 12 (350); the test's explicit non-HOME activity launches also created one additional launcher task, which was removed after testing. This sample does not establish an FPS guarantee or prove the absence of all leaks.
- No stable release was published in this review.
