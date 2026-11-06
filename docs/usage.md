# Using PeachOS TV

| Action | Remote control |
| --- | --- |
| Enter artwork from the dock | Up |
| Browse artwork | Left / Right |
| Open a card in its app | OK |
| Return from artwork to the dock | Down / Back |
| Open the control center | Press OK on the settings gear |
| Open launcher settings | Dedicated control center button; hold gear when enabled |
| Choose artwork sources | Launcher menu → Top shelf |
| Rearrange apps / edit the dock | Launcher menu → Reorder / Edit dock |
| App context menu | Hold OK on an app icon |
| Home while the control center is open | Close the panel and keep the selected app |

Enable **Launcher settings → Remote and sounds → Control Center over apps** to open the panel over the current app by holding Settings, with the Remote buttons helper enabled. Home and Back dismiss only the panel. This optional feature remains experimental: a small blurred snapshot is used when available, while protected video or firmware restrictions can require a simpler material.

Apps must publish their cards through Android TV Preview / Watch Next. Lampa history uses Lampa's published history channel. Private app databases are not read, and root is not required. Availability and freshness depend on the publishing app; empty or temporarily refreshed channels can show no cards.

The control center also provides shortcuts to system settings, apps, Bluetooth, accessibility, search when available, and the clock screen. VPN controls open the detected client or system VPN settings; the launcher cannot read another app's private VPN profiles. **Sleep** requires Android 9+ and the optional **PeachOS TV · Sleep** accessibility helper. This helper does not read screen content or intercept remote keys. The physical power button remains available without it.

### Remote Settings button

1. Enable **Launcher settings → Hold Settings for Control Center**.
2. To use it inside other apps, enable **PeachOS TV · Remote buttons** in Android Accessibility settings. The explanatory row opens that screen and shows the helper status.
3. If the button is not recognized, select **Settings button** and press your remote Settings key within 15 seconds. The launcher saves its code; **Use default** clears the assignment. Navigation, volume, power and Home cannot be assigned.
4. Check Settings and held-volume behavior with your remote.

When enabled, **a short press does nothing**. Holding opens the panel once, with no second action on release. The top gear is excluded from directional navigation; Back from the panel restores the last selected app icon.

The option is off by default. Disabling it lets directional navigation reach the top gear; OK opens the panel and Back restores gear focus.

The separate **Hold gear for launcher settings** option controls holding OK on either the top gear or the gear tab inside the panel, as well as holding Settings again while the panel is open. It is on by default. When off, these holds do nothing; the dedicated launcher settings button in the panel stays available.

Support depends on firmware and the remote keycode; some devices reserve Settings. On some firmware, global key filtering can interfere with held-volume repeat over HDMI. If that happens, you can disable the helper and use the on-screen gear. The sleep helper is a separate service and is not required for this feature.

## Missing posters

Open poster-source settings in the launcher. If **Allow channel access** appears, select it and approve the Android prompt, then sync channels. Open the video app so it can publish its cards. Uninstalling and reinstalling may require granting access again.

Not every app publishes Android TV cards. An app without published sources cannot supply posters.

## Data and network access

No account or API key is required by the launcher. Artwork may be fetched from URLs published by your apps. When metadata backgrounds are enabled, title matching can contact Stremio/Cinemeta services. Lampa TMDB image links are upgraded from HTTP to HTTPS.

With a card source selected, full-screen metadata backgrounds for that app are disabled. The decoded thumbnail cache is limited to 2 MiB, disk thumbnails to 32 MiB; one background worker loads artwork. Inter fonts are bundled locally.

Screenshots show a real installation. App logos and film/series artwork belong to their respective owners; they are not supplied media subscriptions or included streaming content.

## More information

[Wi-Fi](wifi-controls.md) · [AirPlay](airplay-prototype.md) · [Windows wizard](../tools/pc-setup/README.ru.md) · [Back to README](../README.md)
