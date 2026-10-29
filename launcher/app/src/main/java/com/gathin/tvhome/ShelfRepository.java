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
            "internal_provider_id", "internal_provider_data", "app_link_intent_uri"};
    private static final String[] PREVIEW_COLUMNS = {"package_name", "_id", "title", "poster_art_uri",
            "poster_art_aspect_ratio", "thumbnail_uri", "thumbnail_aspect_ratio", "intent_uri", "channel_id", "weight"};
    private static final String[] WATCH_COLUMNS = {"package_name", "_id", "title", "poster_art_uri",
            "poster_art_aspect_ratio", "thumbnail_uri", "thumbnail_aspect_ratio", "intent_uri", "watch_next_type",
            "browsable", "last_engagement_time_utc_millis"};

    static final class Channel {
        long id;
        String name;
        boolean history;
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
        boolean unavailable;
    }

    static String source(Context context, String pkg) {
        return context.getSharedPreferences("top_shelf", Context.MODE_PRIVATE).getString(pkg,
                BackdropPolicy.isStaticYouTube(pkg) ? ShelfSelection.OFF
                        : ShelfSelection.LAMPA.equals(pkg) ? ShelfSelection.HISTORY : ShelfSelection.AUTO);
    }

    static void save(Context context, String pkg, String source) {
        context.getSharedPreferences("top_shelf", Context.MODE_PRIVATE).edit().putString(pkg, source).apply();
    }

    static Result read(Context context, String pkg, String source) {
        Result result = new Result();
        Set<Long> historyChannels = new HashSet<>();
        try (Cursor c = context.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                CHANNEL_COLUMNS, null, null, null)) {
            if (c != null) {
                int packageColumn = c.getColumnIndexOrThrow("package_name");
                int idColumn = c.getColumnIndexOrThrow("_id");
                int nameColumn = c.getColumnIndexOrThrow("display_name");
                int providerColumn = c.getColumnIndexOrThrow("internal_provider_id");
                int dataColumn = c.getColumnIndexOrThrow("internal_provider_data");
                int linkColumn = c.getColumnIndexOrThrow("app_link_intent_uri");
                int visited = 0;
                while (c.moveToNext() && visited++ < 4000) {
                    if (!pkg.equals(text(c, packageColumn))) continue;
                    Channel channel = new Channel();
                    channel.id = number(c, idColumn, -1);
                    channel.name = text(c, nameColumn);
                    String data = textOrBlob(c, dataColumn);
                    channel.history = ShelfSelection.isHistory(pkg, text(c, providerColumn), data,
                            text(c, linkColumn), channel.name);
                    if (channel.history) historyChannels.add(channel.id);
                    result.channels.add(channel);
                }
            }
        } catch (RuntimeException e) {
            result.unavailable = true;
            Log.w("TopShelf", "Channel provider unavailable: " + e.getClass().getSimpleName());
        }
        if (ShelfSelection.OFF.equals(source)) return result;
        for (boolean watch : new boolean[]{true, false}) {
            if (watch ? !ShelfSelection.usesWatchNext(source) : !ShelfSelection.usesPreview(source)) continue;
            Uri table = watch ? TvContract.WatchNextPrograms.CONTENT_URI : TvContract.PreviewPrograms.CONTENT_URI;
            try (Cursor c = context.getContentResolver().query(table, watch ? WATCH_COLUMNS : PREVIEW_COLUMNS,
                    null, null, null)) {
                if (c == null) continue;
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
                while (c.moveToNext() && visited++ < 6000) {
                    if (!pkg.equals(text(c, packageColumn))) continue;
                    long channelId = watch ? -1 : number(c, channelColumn, -1);
                    int type = watch ? (int) number(c, typeColumn, -1) : -1;
                    boolean browsable = !watch || number(c, browsableColumn, 1) != 0;
                    if (!ShelfSelection.includes(source, watch, type, browsable,
                            channelId, historyChannels.contains(channelId))) continue;
                    Item item = new Item();
                    item.title = text(c, titleColumn);
                    item.image = text(c, posterColumn);
                    int aspectColumn = posterAspectColumn;
                    if (item.image.isEmpty()) {
                        item.image = text(c, thumbnailColumn);
                        aspectColumn = thumbnailAspectColumn;
                    }
                    item.aspect = ShelfGeometry.ratio((int) number(c, aspectColumn, -1));
                    item.image = ShelfArtworkUrl.normalize(item.image);
                    item.link = text(c, linkColumn);
                    if (item.title.isEmpty()) continue;
                    item.id = number(c, idColumn, 0);
                    item.continuing = watch;
                    item.rank = number(c, rankColumn, 0);
                    result.items.add(item);
                }
            } catch (RuntimeException e) {
                result.unavailable = true;
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
