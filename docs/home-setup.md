# Compatible Home setup · 1.5.13-preview

The Windows installer configures Home according to observed firmware behaviour,
not a vendor name or a list of packages to disable. This document describes the
preview implementation; released v1.5.12 does not include this automatic flow.
An ordinary APK installation remains available without the PC installer or an
accessibility service.

## User choice and verification

**«Настроить открытие PeachOS по кнопке Home»** requests Home setup. A separate,
default-off **«Совместимый Home через службу пульта»** permits the fallback and
its accessibility/autostart changes. The reviewed plan states these effects.
Connecting a device does not enable the service. The compatible choice resets
on connection and is not inferred from a cleanup profile.

The installer performs these steps:

1. Install the selected APKs and record their results. An install failure stops
   the plan before Home setup or cleanup.
2. Try Android's `cmd package set-home-activity` and read the resolver result.
3. Open PeachOS explicitly, then launch the ordinary MAIN/HOME intent and check that PeachOS MainActivity
   remains foreground for the correct Android user. A Success response or HOME
   role alone does not establish that Home works. The check wakes the display
   if necessary; it does not change the device's standby configuration.
   Starting from a different screen makes this a real transition instead of
   re-delivering Home to an already visible stock launcher. Activity launches
   and foreground queries have bounded timeouts; OEM `am start -W` is not used.
4. If this succeeds, keep that route. A working existing redirect does not
   require new bridge, accessibility or autostart settings.
5. Otherwise restore the original native Home selection. Without compatible
   consent, stop before cleanup. With consent, configure the helper, wait for
   its confirmed binding/applied package and repeat actual Home verification.
6. Continue to selected cleanup only after Home is verified. A failed compatible
   setup attempts rollback; installed APKs are retained.

The generic route does not disable stock Home, SetupWraith, network services or
the vendor's protection software. The former Xiaomi-specific disabling option
is no longer exposed in the wizard. Reading/restoring old journals and the
legacy core API remain supported; they are not the new setup strategy.

## Android bridge

`HomeSetupReceiver` is an explicit exported receiver protected by
`android.permission.DUMP`. ADB shell/system can call it; ordinary applications
cannot. The installer does not grant that permission to PeachOS or another app.
The receiver itself neither enables accessibility nor changes AppOps.

The fixed receiver component is
`com.gathin.tvhome.backdrops/com.gathin.tvhome.HomeSetupReceiver`, with action
`com.gathin.tvhome.HOME_SETUP`. It accepts `read` or `write` operations. A write
contains a stock Home package and a Boolean key-filter policy; Android validates
the external fields and checks that a nonempty package provides a Home activity.
It does not accept arbitrary shell commands or permission names.

The response uses the versioned prefix `peach-home-v1:` and Base64-encoded JSON.
It reports persisted state, whether the service is bound, its applied Home
package and active key-filter policy. The installer validates this response at
the boundary before using typed state. A write is acknowledged only after the
private preferences have been committed.

Installer-owned state lives in the app-private `home_setup` preferences,
separately from the theme and visual preferences. Setup does not rewrite
`config.json`, change the theme, reset the app order or replace visual settings.
Clearing the override returns control to existing theme configuration.

## Accessibility and remote behaviour

The existing **«PeachOS TV · Кнопки пульта»** service observes window-state
events. The redirect matches the configured stock package and its Home activity
classes, including aliases, rather than every window in that package.
One invisible probe after a new compatible target binds handles a stock Home
that was already visible before the subscription started. It restores the
previous window: apps and HDMI remain outside the Home matcher. Repeated
settings refreshes do not repeat the probe; no permanent polling is added.

A fresh compatible setup uses Home-only mode with key filtering off. Explicit
Settings-hold or other remote bindings can enable filtering when those features
need it; an already enabled helper keeps its current policy. Compatible Home
does not attempt to emulate volume or change HDMI volume behaviour. Physical
Home, held volume and sources still need checking on the target device. Some
firmware conflicts between global key filtering and held HDMI volume cannot be
solved by assigning a different Home role.

The installer adds its service to the live accessibility list without replacing
other entries and enables the global accessibility flag if required. It checks
the named `AUTO_START` operation first. If supported and not already allowed,
it records the previous mode, allows autostart for PeachOS and persists AppOps
with `cmd appops write-settings`. Unsupported operations are skipped; no numeric
operation IDs or broad permission grants are guessed.

This does not overcome every firmware restriction. A device may prohibit shell
configuration, deny service binding or impose an additional vendor policy. The
installer reports the failed stage and stops before cleanup rather than
disabling unrelated system packages. Tested behaviour on one firmware does not
establish compatibility with every Android or Google TV.

## Journal and recovery

`restore.json` contains the original native Home, compatible bridge state,
accessibility membership/master flag and supported autostart mode. Each intended
change is durably recorded before its command, including a command that times
out after the device has already applied it. On failure the installer attempts
to restore this state; if ADB is unavailable, the saved journal can be used
after reconnecting to the same device and Android user.

Restoration uses fixed, validated operations:

- Remove only the PeachOS service that this setup added, retaining other
  accessibility services, including ones enabled later.
- Do not turn accessibility off if a new unrelated service now needs it.
- Restore the bridge only if its state still matches the installer's target
  or has already returned to its previous value. Report a later user change as
  a conflict rather than overwriting it.
- Restore supported AUTO_START only if it still matches the mode applied by the
  installer or is already restored; preserve later changes and report conflicts.
- Return the original native Home selection when the plan changed it.

The journal does not back up APK binaries, accounts or the entire firmware.
Restoring it does not uninstall or downgrade an APK. A rollback may stop on a
conflict or disconnection and require attention before retrying.

## Update-only and validation boundaries

**«Только обновить»** requires an existing PeachOS installation and permits only
the launcher APK update. It excludes Home assignment, compatible setup,
accessibility/AppOps changes, cleanup, extra APKs and the optional AirPlay module
regardless of other selections in the wizard. An ordinary reinstall can request
Home setup explicitly; it does not silently reuse compatible consent from a
different device.

Python protocol/plan/rollback tests use simulated ADB. Android tests cover the
bridge state and key-filter policy. They exercise validation, failure recovery,
preservation of unrelated services and actual-foreground detection logic, but
cannot establish real service binding or remote behaviour on an untested TV.
See [TCL G10 Android 14 observations](tcl-g10-android14.md) for firmware-specific
findings; hardware results should be recorded separately from simulated tests.
