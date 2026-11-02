package com.gathin.tvhome;

import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded, internal bootstrap data. Reading it never stats the external theme or its images. */
final class ThemeSnapshot {
    static final String FILE_NAME = "home-theme-v1.json";
    private static final int VERSION = 1, MAX_BYTES = 256 * 1024;
    private static volatile ThemeSnapshot current;
    private static long publishedSequence;
    final File directory, wallpaper;
    final JSONObject overlay;
    final List<File> collection;
    final Map<String, String> singleCaptions, collectionCaptions;
    final String key;
    private final JSONObject document;

    static boolean isCurrent(ThemeSnapshot snapshot) {
        ThemeSnapshot value = current;
        return value != null && snapshot != null && value.key.equals(snapshot.key);
    }

    private ThemeSnapshot(JSONObject document, File directory, JSONObject overlay, File wallpaper,
                          List<File> collection, Map<String, String> singleCaptions,
                          Map<String, String> collectionCaptions) {
        this.document = document; this.directory = directory; this.overlay = overlay;
        this.wallpaper = wallpaper; this.collection = Collections.unmodifiableList(collection);
        this.singleCaptions = Collections.unmodifiableMap(singleCaptions);
        this.collectionCaptions = Collections.unmodifiableMap(collectionCaptions);
        key = canonical(document);
    }

    static ThemeSnapshot read(File file, String owner, int uid) {
        if (current != null) return current;
        return readFile(file, owner, uid);
    }

    private static synchronized ThemeSnapshot readFile(File file, String owner, int uid) {
        if (current != null) return current;
        AtomicFile storage = new AtomicFile(file);
        try (FileInputStream input = storage.openRead()) {
            long size = input.getChannel().size();
            if (size > MAX_BYTES) throw new IOException("snapshot exceeds size limit");
            byte[] bytes = new byte[(int) size];
            int count = 0, read;
            while (count < bytes.length && (read = input.read(bytes, count, bytes.length - count)) > 0) count += read;
            JSONObject data = new JSONObject(new String(bytes, 0, count, StandardCharsets.UTF_8));
            if (data.getInt("version") != VERSION || !owner.equals(data.getString("owner"))
                    || data.getInt("uid") != uid) return null;
            File directory = new File(data.getString("directory"));
            if (!directory.isAbsolute()) throw new JSONException("relative theme directory");
            JSONObject overlay = data.isNull("overlay") ? null : data.getJSONObject("overlay");
            JSONArray images = data.getJSONArray("images");
            if (images.length() > 100) throw new JSONException("too many images");
            List<File> collection = new ArrayList<>(images.length());
            Map<String, String> collectionCaptions = new HashMap<>();
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.getJSONObject(i);
                String name = image.getString("name");
                if (name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 255 || name.indexOf('\0') >= 0
                        || !new File(name).getName().equals(name)) throw new JSONException("invalid image name");
                if (image.getLong("length") < 0 || image.getLong("modified") < 0)
                    throw new JSONException("invalid image metadata");
                collection.add(new File(new File(directory, "wallpapers"), name));
                String caption = image.getString("caption");
                if (caption.length() > WallpaperCaptions.MAX_LENGTH) throw new JSONException("caption too long");
                if (!caption.isEmpty()) collectionCaptions.put(name, caption);
            }
            Map<String, String> singleCaptions = new HashMap<>();
            JSONObject single = data.getJSONObject("single");
            if (single.getLong("length") < 0 || single.getLong("modified") < 0)
                throw new JSONException("invalid wallpaper metadata");
            String caption = single.getString("caption");
            if (caption.length() > WallpaperCaptions.MAX_LENGTH) throw new JSONException("caption too long");
            if (!caption.isEmpty()) singleCaptions.put(Config.WALLPAPER_NAME, caption);
            File wallpaper = single.getBoolean("present") ? new File(directory, Config.WALLPAPER_NAME) : null;
            current = new ThemeSnapshot(data, directory, overlay, wallpaper, collection, singleCaptions, collectionCaptions);
            return current;
        } catch (IOException | JSONException unavailable) {
            // An absent/corrupt/old snapshot takes the ordinary full-load path once.
            return null;
        }
    }

    /** Only the full loader calls this: metadata and caption reads stay off the cached Home path. */
    static ThemeSnapshot capture(File directory, JSONObject overlay, File wallpaper, List<File> collection,
                                 String owner, int uid) {
        Map<String, String> singleCaptions = new HashMap<>();
        String singleCaption = WallpaperCaptions.read(directory).get(Config.WALLPAPER_NAME);
        if (singleCaption != null) singleCaptions.put(Config.WALLPAPER_NAME, singleCaption);
        Map<String, String> availableCaptions = WallpaperCaptions.read(new File(directory, "wallpapers"));
        Map<String, String> collectionCaptions = new HashMap<>();
        try {
            JSONObject data = new JSONObject().put("version", VERSION).put("owner", owner).put("uid", uid)
                    .put("directory", directory.getAbsolutePath()).put("overlay", overlay == null ? JSONObject.NULL : overlay);
            data.put("single", new JSONObject().put("present", wallpaper != null)
                    .put("length", wallpaper == null ? 0 : wallpaper.length())
                    .put("modified", wallpaper == null ? 0 : wallpaper.lastModified())
                    .put("caption", singleCaption == null ? "" : singleCaption));
            JSONArray images = new JSONArray();
            for (File image : collection) {
                String caption = availableCaptions.get(image.getName());
                if (caption != null) collectionCaptions.put(image.getName(), caption);
                images.put(new JSONObject().put("name", image.getName()).put("length", image.length())
                        .put("modified", image.lastModified()).put("caption", caption == null ? "" : caption));
            }
            data.put("images", images);
            return new ThemeSnapshot(data, directory, overlay, wallpaper, new ArrayList<>(collection),
                    singleCaptions, collectionCaptions);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible); // all keys and values above are owned, typed data
        }
    }

    /** A newer external read cannot be overwritten by an older, slower scan. */
    static synchronized boolean publish(File file, ThemeSnapshot snapshot, long sequence) {
        if (sequence < publishedSequence) return false;
        publishedSequence = sequence;
        if (current != null && current.key.equals(snapshot.key)) return true;
        current = snapshot;
        byte[] bytes = snapshot.document.toString().getBytes(StandardCharsets.UTF_8);
        AtomicFile storage = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            if (bytes.length > MAX_BYTES) throw new IOException("snapshot exceeds size limit");
            output = storage.startWrite(); output.write(bytes); storage.finishWrite(output);
        } catch (IOException unavailable) {
            if (output != null) storage.failWrite(output);
            Log.w("PeachStartup", "Could not persist theme snapshot", unavailable);
        }
        return true;
    }

    private static String canonical(Object value) {
        if (value instanceof JSONObject object) {
            List<String> names = new ArrayList<>(); object.keys().forEachRemaining(names::add);
            Collections.sort(names);
            StringBuilder result = new StringBuilder("{");
            for (String name : names) result.append(JSONObject.quote(name)).append(':').append(canonical(object.opt(name))).append(',');
            return result.append('}').toString();
        }
        if (value instanceof JSONArray array) {
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) result.append(canonical(array.opt(i))).append(',');
            return result.append(']').toString();
        }
        return value instanceof String text ? JSONObject.quote(text) : String.valueOf(value);
    }
}
