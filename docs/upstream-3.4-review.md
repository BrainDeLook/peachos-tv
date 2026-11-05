# CleanYourTV 3.4.0 review — 2026-11-05

Official release: https://github.com/Gathin23/cleanyourtv/releases/tag/v3.4.0

Tag commit: `fc148d5a9707b30e8f49f489625947421740dc55`.
Our imported reference snapshot: `50fcc2141a38eb1a741a270d23b3d57123aa036c`.

## Package reference data

The comparison contains six commits and changes README, SHOWCASE, the launcher
version, manage.py and its tests. It changes no device lists. Our 442-list,
1,981-package reference index already includes the device data in 3.4.0.
The normal installer still exposes 122 described candidates, not actions for
every package or model in that index. No additional cleanup is authorised by
this review.

## Launcher changes worth separate backports

| Upstream change | PeachOS assessment |
| --- | --- |
| ClockRefresh | Our layouts still contain TextClock. Upstream refreshes those clocks at minute boundaries and display wake on Android below 12, where minute broadcasts can stop. Useful for older TVs; the reported fix does not apply to Homatics API 31 or TCL API 34. Our general minute timer is not the same as explicitly redrawing TextClock. |
| Xiaomi picture settings | Our SystemSettings lacks upstream displayIntent, which prefers Xiaomi PictureActivity when installed. Useful for Mi TVs that resolve standard display settings to an empty stub. Detect the component, rather than assume every Xiaomi firmware has it. |
| Remote input targets | Our RemoteKeys still sends the channel URI implicitly. Upstream first aims it at the Live TV package to avoid competing apps producing a chooser. Use the same route as our own input menu in a separate tested change. |
| inputs_cec_state_only | Optional upstream workaround for firmware that reports HDMI connection states against the wrong port. Keep it optional; a device without CEC must not be reported as physically disconnected merely because it is silent. |

Upstream also adds five themes, two add-ons, and device-specific fixes for dock
placement, memory reporting and mirrored navigation. These were reviewed as
release changes, not merged into PeachOS's custom interface.

## Tool security

The latest manage.py change validates extracted application IDs before using
them in TV shell operations. PeachOS does not ship that manager. The portable
installer already validates APK metadata/package names and quotes its Android
shell boundary. Importing the manager is unnecessary for this fix.

## Separate PeachOS reinstall issue

After an uninstall, READ_TV_LISTINGS was no longer granted. Android returned no
other-app channel data, while the old sync UI could still say synchronization
finished. PeachOS 1.5.15-preview makes the permission requirement explicit,
requests it from the channel screen, handles refusal and checks access before
provider reads. The Homatics owner confirmed both recovery after an ADB grant
and recovery through the new Android permission dialog after permission was
revoked again. This was not an upstream package-list regression.
