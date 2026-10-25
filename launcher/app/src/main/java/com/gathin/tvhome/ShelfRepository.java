package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
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
    static final class Channel {
        long id;
        String name;
        boolean history;
    }
    static final class Item {
        String title, image, link;
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
        try (Cursor c = context.getContentResolver().query(Uri.parse("content://android.media.tv/channel"),
                null, null, null, null)) {
            int visited = 0;
            while (c != null && c.moveToNext() && visited++ < 4000) {
                if (!pkg.equals(text(c, "package_name"))) continue;
                Channel channel = new Channel();
                channel.id = number(c, "_id", -1);
                channel.name = text(c, "display_name");
                String data = textOrBlob(c, "internal_provider_data");
                channel.history = ShelfSelection.isHistory(pkg, text(c, "internal_provider_id"), data,
                        text(c, "app_link_intent_uri"), channel.name);
                result.channels.add(channel);
            }
        } catch (RuntimeException e) {
            result.unavailable = true;
            Log.w("TopShelf", "Channel provider unavailable: " + e.getClass().getSimpleName());
        }
        if (ShelfSelection.OFF.equals(source)) return result;
        for (boolean watch : new boolean[]{true, false}) {
            Uri table = Uri.parse("content://android.media.tv/" + (watch ? "watch_next_program" : "preview_program"));
            try (Cursor c = context.getContentResolver().query(table, null, null, null, null)) {
                int visited = 0;
                while (c != null && c.moveToNext() && visited++ < 6000) {
                    if (!pkg.equals(text(c, "package_name"))) continue;
                    long channelId = number(c, "channel_id", -1);
                    boolean history = false;
                    for (Channel channel : result.channels) if (channel.id == channelId) history = channel.history;
                    int type = (int) number(c, "watch_next_type", -1);
                    if (!ShelfSelection.includes(source, watch, type, number(c, "browsable", 1) != 0,
                            channelId, history)) continue;
                    Item item = new Item();
                    item.title = text(c, "title");
                    item.image = text(c, "poster_art_uri");
                    if (item.image.isEmpty()) item.image = text(c, "thumbnail_uri");
                    item.image = ShelfArtworkUrl.normalize(item.image);
                    item.link = text(c, "intent_uri");
                    if (item.title.isEmpty()) continue;
                    item.id = number(c, "_id", 0);
                    item.continuing = watch;
                    item.rank = number(c, watch ? "last_engagement_time_utc_millis" : "weight", 0);
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

    private static String text(Cursor c, String name) {
        int index = c.getColumnIndex(name);
        return index < 0 || c.isNull(index) ? "" : c.getString(index);
    }
    private static long number(Cursor c, String name, long fallback) {
        int index = c.getColumnIndex(name);
        return index < 0 || c.isNull(index) ? fallback : c.getLong(index);
    }
    private static String textOrBlob(Cursor c, String name) {
        int index = c.getColumnIndex(name);
        if (index < 0 || c.isNull(index)) return "";
        return c.getType(index) == Cursor.FIELD_TYPE_BLOB
                ? new String(c.getBlob(index), StandardCharsets.UTF_8) : c.getString(index);
    }
    private ShelfRepository() { }
}
