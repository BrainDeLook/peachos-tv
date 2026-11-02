# Home theme bootstrap

Home and the remote-key service first load the last verified theme from the app's internal `home-theme-v1.json`. The versioned snapshot belongs to the package/UID and contains the raw theme overlay, bounded wallpaper inventory, image metadata, and captions. Local launcher preferences are still resolved against current resources on each load. Wallpaper decoding remains on the existing image worker.

The cached path does not ask Android for the external app directory, enumerate wallpapers, stat external images, read captions, or synchronize an external theme revision. This removes external-storage service waits from normal Home initialization. A missing, incompatible, oversized, or malformed snapshot falls back to the ordinary full loader once; the first installation/upgrade without a snapshot therefore does not receive the same startup benefit.

`ConfigRefresh` coalesces Home/helper subscribers into one low-priority worker. Completed startup results can be reused by a late subscriber. Activity/service teardown removes callbacks; stale deliveries are discarded, cancelled queued work is purged, and the idle worker exits after two seconds. The retained result has no Activity/Context reference. There is no periodic theme polling.

Only verified external reads publish a snapshot. AtomicFile preserves the last disk copy on an interrupted write, and monotonically ordered publication prevents an older scan replacing a newer result. An existing malformed/unreadable external config is distinguished from explicit deletion: it preserves the previous theme rather than clearing preferences. An unavailable volume is retried on mount or a later resume.

Home compares normalized JSON/image metadata and visual preferences. An unchanged theme does not rebuild the UI. A changed theme is applied through the existing Activity initialization, after dialogs/reorder/panels have closed, preserving the selected app. Only this replacement suppresses the normal entrance animation and withholds the empty catalogue frame. Ordinary animations and layout parameters remain unchanged.

Native checks and reproducible build commands are in [tools/bootstrap-fixture](../tools/bootstrap-fixture/README.md). They run under a separate test package, not against the owner's preferences.

This is an optimization of Home's startup path, not of Android's complete boot process. It neither preloads all installed apps nor adds a permanent background service.
