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

PeachOS TV v1.5.11's existing remote accessibility helper can redirect the stock
Home without disabling either package. Its external `config.json` can contain:

```json
{"stock_home_redirect":"com.google.android.apps.tv.launcherx","consume_volume_keys":false}
```

Merge this into an existing theme rather than replacing its other settings.
Enable `PeachOS TV · Кнопки пульта` with the owner's consent. The TCL-specific
AUTO_START app-op must also permit this app. Otherwise TclAppBoot rejects binding
HomeKeyService (`callee_does't_have_OP_AUTO_START_permission`) after a package
replacement or reboot, and Android resets `accessibility_enabled` to 0 while
retaining the service in the enabled list. Merely restoring that flag does not
fix the underlying denial. Leave TGuard and its global protection enabled.
ADB AppOps changes must be persisted before an immediate test reboot
(`cmd appops write-settings`); a read-back confirmed AUTO_START remained allowed.
After a subsequent reboot the helper bound without errors and PeachOS became
the foreground Home automatically. Replacing the same APK also retained the
helper once AUTO_START was allowed; no installer rewrite of accessibility settings
was needed. This permission setup was performed separately for the owner's TV.

The installer does not automatically grant accessibility or AUTO_START. Its
standard Home-selection attempt still stops before cleanup if selection fails.
After configuring and checking the redirect, repeat the plan without changing Home.
The installer now explains the TCL limitation instead of suggesting the Xiaomi
workaround on this device.

## Conservative cleanup

The AU02 profile recommends four present packages: Google feedback, TCL LogKit,
TCL Home Passive and ESticker. Package actions on this device were inspected:
LogKit exposes log capture/upload; ESticker exposes showroom and soundbar demo
screens; Home Passive exposes its content activity and startup receivers.

The Windows wizard applied these four reversible disable operations with a
restore journal and verified each result. Network, input, remote, Google TV,
SetupWraith and TGuard packages remain enabled. No package data was removed.
`com.tcl.exhibit` is deliberately excluded: this build also exposes Art Mode / 
multimode functions, so the Android 12 demonstration-only assumption is not reused.

Physical Home from an app, held volume and source switching were confirmed by
the owner. Installer unit/UI tests cover exact-build matching, rejection of
other builds, protection of the excluded packages and cleanup stopping on a
failed Home assignment. Hardware behavior on other firmware remains unverified.
