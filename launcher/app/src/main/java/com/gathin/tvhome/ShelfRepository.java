package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only Android TV metadata. All calls run on a worker; provider filters are applied locally. */
final class ShelfRepository {
    private static final String[] CHANNEL_COLUMNS = {"package_name", "_id", "display_name",
            "internal_provider_id", "internal_provider_data", "app_link_intent_uri", "type"};
    private static final String[] PREVIEW_COLUMNS = {"package_name", "_id", "title", "poster_art_uri",
            "poster_art_aspect_ratio", "thumbnail_uri", "thumbnail_aspect_ratio", "intent_uri", "channel_id", "weight"};
    private static final String[] WATCH_COLUMNS = {"package_name", "_id", "title", "poster_art_uri",
            "poster_art_aspect_ratio", "thumbnail_uri", "thumbnail_aspect_ratio", "intent_uri", "watch_next_type",
            "browsable", "last_engagement_time_utc_millis"};

    static final class Channel {
        long id;
        String name, providerId;
        boolean history;
        String source() { return ShelfSelection.channelSource(id, providerId); }
    }
    static final class Item {
        String title, image, link;
        float aspect;
        long rank, id;
        boolean continuing;
    }
    static final class Result {
        final List<Channel> channels = new ArrayList<>();
        final List<Item> items = new ArrayList<>();
        boolean continueWatching;
        TvMetadata.Status channelsStatus = TvMetadata.Status.READY;
        TvMetadata.Status watchStatus = TvMetadata.Status.READY;
        TvMetadata.Status previewStatus = TvMetadata.Status.READY;

        TvMetadata.Status issue() {
            // A permission change can be remedied by the user; other provider failures are distinct.
            if (channelsStatus == TvMetadata.Status.PERMISSION_REQUIRED || watchStatus == TvMetadata.Status.PERMISSION_REQUIRED
                    || previewStatus == TvMetadata.Status.PERMISSION_REQUIRED) return TvMetadata.Status.PERMISSION_REQUIRED;
            if (channelsStatus != TvMetadata.Status.READY) return channelsStatus;
            return watchStatus != TvMetadata.Status.READY ? watchStatus : previewStatus;
        }
        boolean unavailable() { return issue() != TvMetadata.Status.READY; }
    }

    /** Compare the displayed channel/card content, excluding provider timestamps and ranking weights. */
    static boolean sameContent(Result first, Result second) {
        if (first == null || first.channelsStatus != second.channelsStatus
                || first.watchStatus != second.watchStatus || first.previewStatus != second.previewStatus
                || first.continueWatching != second.continueWatching
                || first.channels.size() != second.channels.size() || first.items.size() != second.items.size()) return false;
        for (int i = 0; i < first.channels.size(); i++) {
            Channel a = first.channels.get(i), b = second.channels.get(i);
            if (a.id != b.id || a.history != b.history || !a.name.equals(b.name)
                    || !a.providerId.equals(b.providerId)) return false;
        }
        for (int i = 0; i < first.items.size(); i++) {
            Item a = first.items.get(i), b = second.items.get(i);
            if (!a.title.equals(b.title) || !a.image.equals(b.image) || !a.link.equals(b.link)
                    || a.aspect != b.aspect || a.continuing != b.continuing) return false;
        }
        return true;
    }

    static String source(Context context, String pkg) {
        return context.getSharedPreferences("top_shelf", Context.MODE_PRIVATE).getString(pkg,
                BackdropPolicy.isStaticYouTube(pkg) ? ShelfSelection.OFF
                        : ShelfSelection.LAMPA.equals(pkg) ? ShelfSelection.HISTORY : ShelfSelection.AUTO);
    }

    static void save(Context context, String pkg, String source) {
        context.getSharedPreferences("top_shelf", Context.MODE_PRIVATE).edit().putString(pkg, source).apply();
    }

    static java.util.Map<String, Result> catalogue(Context context, Set<String> apps,
            android.os.CancellationSignal cancellation) {
        TvMetadata.Status access = TvMetadata.access(context);
        java.util.Map<String, Result> result = readChannels(context, apps, access, cancellation);
        if (access != TvMetadata.Status.READY) return result;
        try (Cursor c = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                new String[]{"package_name", "watch_next_type", "browsable", "title"}, null, null, null, cancellation)) {
            if (c == null) {
                for (Result value : result.values()) value.watchStatus = TvMetadata.Status.TEMPORARILY_UNAVAILABLE;
                return result;
            }
            int visited = 0;
            while (!Thread.currentThread().isInterrupted() && c.moveToNext() && visited++ < 6000) {
                String pkg = text(c, 0);
                if (apps.contains(pkg) && !text(c, 3).isEmpty() && ShelfSelection.includes(ShelfSelection.CONTINUE, true,
                        (int) number(c, 1, -1), number(c, 2, 1) != 0, -1, false)) {
                    result.get(pkg).continueWatching = true;
                }
            }
        } catch (android.os.OperationCanceledException cancelled) {
            throw cancelled;
        } catch (RuntimeException e) {
            for (Result value : result.values()) value.watchStatus = TvMetadata.failure(e);
            Log.w("TopShelf", "Watch Next catalogue unavailable: " + e.getClass().getSimpleName());
        }
        return result;
    }

    private static java.util.Map<String, Result> readChannels(Context context, Set<String> apps,
            TvMetadata.Status access, android.os.CancellationSignal cancellation) {
        java.util.Map<String, Result> result = new java.util.LinkedHashMap<>();
        for (String app : apps) result.put(app, new Result());
        if (access != TvMetadata.Status.READY) {
            for (Result value : result.values())
                value.channelsStatus = value.watchStatus = value.previewStatus = access;
            return result;
        }
        try (Cursor c = context.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                CHANNEL_COLUMNS, null, null, null, cancellation)) {
            if (c == null) {
                for (Result value : result.values()) value.channelsStatus = TvMetadata.Status.TEMPORARILY_UNAVAILABLE;
                return result;
            }
            int visited = 0;
            while (!Thread.currentThread().isInterrupted() && c.moveToNext() && visited++ < 4000) {
                String pkg = text(c, 0);
                if (!apps.contains(pkg) || !TvContract.Channels.TYPE_PREVIEW.equals(text(c, 6))) continue;
                Channel channel = new Channel();
                channel.id = number(c, 1, -1);
                if (channel.id < 0) continue;
                channel.name = text(c, 2);
                channel.providerId = text(c, 3);
                channel.history = ShelfSelection.isHistory(pkg, channel.providerId, textOrBlob(c, 4), text(c, 5), channel.name);
                result.get(pkg).channels.add(channel);
            }
        } catch (android.os.OperationCanceledException cancelled) {
            throw cancelled;
        } catch (RuntimeException e) {
            for (Result value : result.values()) value.channelsStatus = TvMetadata.failure(e);
            Log.w("TopShelf", "Channel provider unavailable: " + e.getClass().getSimpleName());
        }
        for (Result value : result.values()) {
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (Channel channel : value.channels) counts.merge(channel.providerId, 1, Integer::sum);
            for (Channel channel : value.channels) if (counts.get(channel.providerId) > 1) channel.providerId = "";
        }
        return result;
    }

    static Result read(Context context, String pkg, String source) {
        return read(context, pkg, source, null);
    }

    static Result read(Context context, String pkg, String source, android.os.CancellationSignal cancellation) {
        TvMetadata.Status access = TvMetadata.access(context);
        Result result = readChannels(context, Collections.singleton(pkg), access, cancellation).get(pkg);
        if (access != TvMetadata.Status.READY) return result;
        Set<Long> historyChannels = new HashSet<>();
        Set<Long> appChannels = new HashSet<>();
        String resolvedSource = source;
        for (Channel channel : result.channels) {
            appChannels.add(channel.id);
            if (channel.history) historyChannels.add(channel.id);
            if (ShelfSelection.matchesChannel(source, channel.id, channel.providerId)) {
                resolvedSource = "channel:" + channel.id;
                if (source.startsWith("channel:") && !channel.providerId.isEmpty()) save(context, pkg, channel.source());
            }
        }
        if (ShelfSelection.OFF.equals(source)) return result;
        for (boolean watch : new boolean[]{true, false}) {
            if (watch ? !ShelfSelection.usesWatchNext(source) : !ShelfSelection.usesPreview(source)) continue;
            Uri table = watch ? TvContract.WatchNextPrograms.CONTENT_URI : TvContract.PreviewPrograms.CONTENT_URI;
            try (Cursor c = context.getContentResolver().query(table, watch ? WATCH_COLUMNS : PREVIEW_COLUMNS,
                    null, null, null, cancellation)) {
                if (c == null) {
                    if (watch) result.watchStatus = TvMetadata.Status.TEMPORARILY_UNAVAILABLE;
                    else result.previewStatus = TvMetadata.Status.TEMPORARILY_UNAVAILABLE;
                    continue;
                }
                int packageColumn = c.getColumnIndexOrThrow("package_name");
                int idColumn = c.getColumnIndexOrThrow("_id");
                int titleColumn = c.getColumnIndexOrThrow("title");
                int posterColumn = c.getColumnIndexOrThrow("poster_art_uri");
                int posterAspectColumn = c.getColumnIndexOrThrow("poster_art_aspect_ratio");
                int thumbnailColumn = c.getColumnIndexOrThrow("thumbnail_uri");
                int thumbnailAspectColumn = c.getColumnIndexOrThrow("thumbnail_aspect_ratio");
                int linkColumn = c.getColumnIndexOrThrow("intent_uri");
                int channelColumn = watch ? -1 : c.getColumnIndexOrThrow("channel_id");
                int typeColumn = watch ? c.getColumnIndexOrThrow("watch_next_type") : -1;
                int browsableColumn = watch ? c.getColumnIndexOrThrow("browsable") : -1;
                int rankColumn = c.getColumnIndexOrThrow(watch ? "last_engagement_time_utc_millis" : "weight");
                int visited = 0;
                while (!Thread.currentThread().isInterrupted() && c.moveToNext() && visited++ < 6000) {
                    if (!pkg.equals(text(c, packageColumn))) continue;
                    long channelId = watch ? -1 : number(c, channelColumn, -1);
                    if (!watch && !appChannels.contains(channelId)) continue;
                    int type = watch ? (int) number(c, typeColumn, -1) : -1;
                    boolean browsable = !watch || number(c, browsableColumn, 1) != 0;
                    String title = text(c, titleColumn);
                    if (title.isEmpty()) continue;
                    if (watch && ShelfSelection.includes(ShelfSelection.CONTINUE, true, type, browsable, -1, false))
                        result.continueWatching = true;
                    if (!ShelfSelection.includes(resolvedSource, watch, type, browsable,
                            channelId, historyChannels.contains(channelId))) continue;
                    Item item = new Item();
                    item.title = title;
                    item.image = text(c, posterColumn);
                    int aspectColumn = posterAspectColumn;
                    if (item.image.isEmpty()) {
                        item.image = text(c, thumbnailColumn);
                        aspectColumn = thumbnailAspectColumn;
                    }
                    item.aspect = ShelfGeometry.ratio((int) number(c, aspectColumn, -1));
                    item.image = ShelfArtworkUrl.normalize(item.image);
                    item.link = text(c, linkColumn);
                    item.id = number(c, idColumn, 0);
                    item.continuing = watch;
                    item.rank = number(c, rankColumn, 0);
                    result.items.add(item);
                }
            } catch (android.os.OperationCanceledException cancelled) {
                throw cancelled;
            } catch (RuntimeException e) {
                if (watch) result.watchStatus = TvMetadata.failure(e);
                else result.previewStatus = TvMetadata.failure(e);
                Log.w("TopShelf", "Program provider unavailable: " + e.getClass().getSimpleName());
            }
        }
        // Continue watching first; history and other channels retain the publishing app's order (weight).
        Collections.sort(result.items, (a, b) -> {
            if (a.continuing != b.continuing) return a.continuing ? -1 : 1;
            int rank = Long.compare(b.rank, a.rank);
            return rank != 0 ? rank : Long.compare(b.id, a.id);
        });
        Set<String> seen = new HashSet<>();
        result.items.removeIf(item -> !seen.add(item.link.isEmpty() ? item.title + "\n" + item.image : item.link));
        if (result.items.size() > 20) result.items.subList(20, result.items.size()).clear();
        Log.i("TopShelf", pkg + " source=" + source + " channels=" + result.channels.size()
                + " cards=" + result.items.size());
        return result;
    }

    private static String text(Cursor c, int index) {
        return c.isNull(index) ? "" : c.getString(index);
    }
    private static long number(Cursor c, int index, long fallback) {
        return c.isNull(index) ? fallback : c.getLong(index);
    }
    private static String textOrBlob(Cursor c, int index) {
        if (c.isNull(index)) return "";
        return c.getType(index) == Cursor.FIELD_TYPE_BLOB
                ? new String(c.getBlob(index), StandardCharsets.UTF_8) : c.getString(index);
    }
    private ShelfRepository() { }
}
