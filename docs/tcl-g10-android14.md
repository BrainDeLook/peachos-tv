# TCL G10 / Q6CS: Android 14 AU02

Test device: TCL Smart TV Pro, product `G10_4K_GB`, device `G10`, API 34,
build `G10-user 14 UTT2.250416.001 AU02 release-keys`. This is a separate
installer profile; other Android versions and build strings do not inherit it.

## Home selection

On this firmware, `set-home-activity` reports Success and updates the HOME role,
but the HOME intent still resolves to Google TV (priority 2). With Google TV
disabled, SetupWraith RecoveryActivity (priority 1) wins instead. Component-only
changes are rejected by the firmware. Do not disable SetupWraith: it can own
saved network configuration. The diagnostic Google TV package change was reverted.

The existing remote accessibility helper can redirect the stock Home without
disabling either package. Earlier device checks configured it manually and
confirmed physical Home from an app, held volume and input switching. That
manual configuration is evidence for this device, not validation of every
installer path or other firmware.

### Installer 1.5.13

Version 1.5.13 uses the general [Home setup](home-setup.md) flow, independent of the
cleanup profile. Select **«Настроить открытие PeachOS по кнопке Home»** and,
if permitted, **«Совместимый Home через службу пульта»**. The latter is off by
default. The installer tries native assignment first and checks the actual
foreground Home rather than trusting Success or the HOME role. An already
working redirect is retained without new service or permission changes.

If native Home fails and the compatible option is approved in the plan, the
installer configures the existing helper through a receiver protected by
`android.permission.DUMP`, enables its accessibility membership and allows
`AUTO_START` only when the firmware recognises that named operation. The
configuration uses private preferences; no merge or replacement of the theme's
`config.json` is required. A new Home-only configuration leaves key filtering
off; existing remote choices are preserved. Stock Home, SetupWraith and TGuard
remain enabled.

The TCL AUTO_START app-op matters independently of Android's accessibility flag.
Without it, TclAppBoot rejected HomeKeyService binding with
`callee_does't_have_OP_AUTO_START_permission` after package replacement or reboot;
Android then reset `accessibility_enabled` to 0 while retaining the service in
the enabled list. Merely restoring that flag did not fix the denial. The installer
persists supported AppOps changes with `cmd appops write-settings` before checking
the service. In earlier manual tests, an allowed and persisted AUTO_START survived
reboot, the helper bound and PeachOS became foreground Home; replacing the same
APK also retained the helper. Leave global TGuard protection enabled.

Before each compatible-Home change, the installer records its previous state in
the restore journal. If binding or actual Home verification fails, it attempts
rollback and stops before cleanup. An interrupted rollback can be retried from
the saved copy after ADB reconnects. Other accessibility services and later
user changes are not overwritten silently. Without the compatible option, a
failed native assignment returns the original selection and stops before cleanup.
Installed APKs remain in either case.

**«Только обновить»** only replaces the APK with `adb install -r`: it does not
configure Home, accessibility or AUTO_START. Version 1.5.12 and earlier did not
perform the automatic compatible setup described above.

The v1.5.13 installer flow was tested on this device: initial compatible setup,
same-APK reinstall preserving the existing redirect, strict update-only,
restoration to the original bridge/accessibility/autostart state, and setup
again from that restored state. After a real reboot, AUTO_START remained allowed,
the Home-only service bound without key filtering, and PeachOS was already the
foreground Home before the explicit verification.

A subsequent user reinstall exposed a wizard consent bug: checking compatible
Home without the separate Home checkbox produced a successful plan with
`home_attempted=false`. Version 1.5.13 makes compatible selection request Home setup
itself, and clearing Home clears compatible consent. Both simple and advanced
plans have regression tests. After applying the previously omitted Home setup,
the owner confirmed physical Home opens PeachOS from another app.

## Conservative cleanup

The AU02 profile now recommends five present packages: Google feedback, TCL LogKit,
TCL Home Passive, ESticker and the TCL Channel catalogue (`com.tcl.waterfall.overseas`).
The catalogue's manifest exposes its independent web/video portal; upstream
lists distinguish it from the protected `com.tcl.tv` HDMI/antenna player and
the optional `com.tcl.channelplus` FAST input. Only the first four cleanup
operations have been applied and checked on this device. Package actions were inspected:
LogKit exposes log capture/upload; ESticker exposes showroom and soundbar demo
screens; Home Passive exposes its content activity and startup receivers.

The Windows wizard applied these four reversible disable operations with a
restore journal and verified each result. Network, input, remote, Google TV,
SetupWraith and TGuard packages remain enabled. No package data was removed.
`com.tcl.exhibit` is deliberately excluded: this build also exposes Art Mode / 
multimode functions, so the Android 12 demonstration-only assumption is not reused.

Installer tests cover exact-build matching, rejection of other builds,
protection of the excluded packages and cleanup stopping on a failed Home
assignment. Compatible-Home protocol and rollback tests use simulated ADB;
hardware behavior on other firmware remains unverified.
