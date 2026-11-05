package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.EnumMap;

/** The optional TV provider is a system boundary, not a prerequisite for opening Home. */
final class TvMetadata {
    enum Status { READY, PERMISSION_REQUIRED, PROVIDER_MISSING, TABLE_UNSUPPORTED, TEMPORARILY_UNAVAILABLE }
    enum Table { CHANNELS, PREVIEW, WATCH_NEXT }

    static Status access(Context context) {
        try {
            if (context.getPackageManager().resolveContentProvider(TvContract.AUTHORITY, 0) == null)
                return Status.PROVIDER_MISSING;
            return context.checkSelfPermission(Backdrops.PERMISSION) == PackageManager.PERMISSION_GRANTED
                    ? Status.READY : Status.PERMISSION_REQUIRED;
        } catch (RuntimeException unavailable) {
            return failure(unavailable);
        }
    }

    static Status failure(RuntimeException failure) {
        if (failure instanceof SecurityException) return Status.PERMISSION_REQUIRED;
        if (failure instanceof IllegalArgumentException) return Status.TABLE_UNSUPPORTED;
        return Status.TEMPORARILY_UNAVAILABLE;
    }

    static int message(Status status) {
        return switch (status) {
            case PERMISSION_REQUIRED -> R.string.shelf_permission_required;
            case PROVIDER_MISSING -> R.string.shelf_provider_unavailable;
            case TABLE_UNSUPPORTED -> R.string.shelf_table_unsupported;
            case TEMPORARILY_UNAVAILABLE -> R.string.shelf_temporarily_unavailable;
            case READY -> R.string.shelf_synced;
        };
    }

    /** Each subscription owns one observer, including registrations that succeeded only in part. */
    interface ObserverBackend {
        void register(Table table);
        void unregister();
    }

    static final class Observation implements AutoCloseable {
        private final ObserverBackend backend;
        private final EnumMap<Table, Status> states = new EnumMap<>(Table.class);
        private boolean registered;

        Observation(Status access, ObserverBackend backend, Table... tables) {
            this.backend = backend;
            for (Table table : tables) {
                Status status = access;
                if (status == Status.READY) {
                    try {
                        backend.register(table);
                        registered = true;
                    } catch (RuntimeException unavailable) {
                        status = failure(unavailable);
                    }
                }
                states.put(table, status);
            }
        }

        Status status(Table table) { return states.get(table); }
        boolean live() { return registered; }
        boolean complete() {
            return registered && !states.containsValue(Status.PERMISSION_REQUIRED)
                    && !states.containsValue(Status.PROVIDER_MISSING) && !states.containsValue(Status.TABLE_UNSUPPORTED)
                    && !states.containsValue(Status.TEMPORARILY_UNAVAILABLE);
        }

        @Override public void close() {
            if (!registered) return;
            registered = false;
            // A provider/process can disappear after registration. Cleanup must not crash Home.
            try { backend.unregister(); }
            catch (RuntimeException unavailable) { }
        }
    }

    static Observation observe(Context context, Status access, ContentObserver observer, Table... tables) {
        return new Observation(access, new ObserverBackend() {
            @Override public void register(Table table) {
                Uri uri = switch (table) {
                    case CHANNELS -> TvContract.Channels.CONTENT_URI;
                    case PREVIEW -> TvContract.PreviewPrograms.CONTENT_URI;
                    case WATCH_NEXT -> TvContract.WatchNextPrograms.CONTENT_URI;
                };
                context.getContentResolver().registerContentObserver(uri, true, observer);
            }
            @Override public void unregister() {
                context.getContentResolver().unregisterContentObserver(observer);
            }
        }, tables);
    }

    private TvMetadata() { }
}
