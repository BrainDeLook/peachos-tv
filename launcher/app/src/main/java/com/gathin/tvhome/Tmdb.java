package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Clean, full-resolution backdrops and title logos from TMDB for the titles apps put on their cards
 * (Netflix's own art is 853x480 with its logo and stickers baked in). Uses the key from Vibe Launcher
 * settings. Only exact title matches are used, and answers are remembered for a month so each title
 * costs two API calls at most.
 */
final class Tmdb implements TitleArt {

    private static final String API = "https://api.themoviedb.org/3";
    private static final String BACKDROPS = "https://image.tmdb.org/t/p/original";
    private static final String LOGOS = "https://image.tmdb.org/t/p/w500";
    private static final int CHOICES = 4;   // best few textless backdrops per title, picked at random
    private static final long FOUND_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long MISSING_MS = 3L * 24 * 60 * 60 * 1000;   // retry unmatched titles sooner

    private final VibeSettings settings;
    private final SharedPreferences cache;
    private final Random random = new Random();

    Tmdb(Context context) {
        settings = new VibeSettings(context);
        cache = context.getSharedPreferences("tmdb_cache", Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return !settings.tmdbKey().isEmpty();
    }

    @Override
    public Art lookup(String title) {
        String key = TitleArt.normalize(title);
        if (key.isEmpty() || !enabled()) return null;
        // Remembered as "time|backdrop paths|logo path"; entries from before logos are looked up again.
        String[] cached = cache.getString(key, "").split("\\|", -1);
        long now = System.currentTimeMillis();
        if (cached.length == 3) {
            long age = now - Long.parseLong(cached[0]);
            if (age < (cached[1].isEmpty() ? MISSING_MS : FOUND_MS)) return art(cached[1], cached[2]);
        }
        String[] found;
        try {
            found = find(title, key);
        } catch (IOException | JSONException | RuntimeException e) {
            return null;   // offline or a bad key: don't remember, try again next time
        }
        cache.edit().putString(key, now + "|" + found[0] + "|" + found[1]).apply();
        return art(found[0], found[1]);
    }

    private Art art(String paths, String logo) {
        if (paths.isEmpty()) return null;
        String[] list = paths.split(",");
        return new Art(BACKDROPS + list[random.nextInt(list.length)], logo.isEmpty() ? null : LOGOS + logo);
    }

    /** {comma-separated backdrop paths, logo path} for the best exact match; "" for none. */
    private String[] find(String title, String key) throws IOException, JSONException {
        JSONArray results = get("/search/multi?include_adult=false&query=" + Uri.encode(title))
                .optJSONArray("results");
        JSONObject best = null;
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String type = r.optString("media_type");
            if (!type.equals("movie") && !type.equals("tv")) continue;
            boolean same = key.equals(TitleArt.normalize(r.optString(type.equals("tv") ? "name" : "title")))
                    || key.equals(TitleArt.normalize(r.optString(type.equals("tv") ? "original_name" : "original_title")));
            if (!same || r.optString("backdrop_path").isEmpty()) continue;
            if (best == null || r.optDouble("popularity") > best.optDouble("popularity")) best = r;
        }
        if (best == null) return new String[]{"", ""};

        String lang = Locale.getDefault().getLanguage();
        JSONObject images = get("/" + best.getString("media_type") + "/" + best.getLong("id")
                + "/images?include_image_language=null,en," + lang);
        // Backdrops tagged with no language have no text on them: no title, no logo.
        List<String> paths = new ArrayList<>();
        JSONArray backdrops = images.optJSONArray("backdrops");
        for (int i = 0; backdrops != null && i < backdrops.length() && paths.size() < CHOICES; i++) {
            JSONObject b = backdrops.getJSONObject(i);   // TMDB lists them best-rated first
            if (b.optInt("width") >= 1280 && b.optString("iso_639_1", "null").equals("null")) {
                paths.add(b.getString("file_path"));
            }
        }
        if (paths.isEmpty()) paths.add(best.getString("backdrop_path"));
        return new String[]{String.join(",", paths), logo(images.optJSONArray("logos"), lang)};
    }

    /** The best-rated PNG logo in the TV's language, else English, else language-free; "" if none. */
    private static String logo(JSONArray logos, String lang) throws JSONException {
        for (String want : new String[]{lang, "en", "null"}) {
            for (int i = 0; logos != null && i < logos.length(); i++) {
                JSONObject l = logos.getJSONObject(i);
                if (l.optString("iso_639_1", "null").equals(want) && l.optString("file_path").endsWith(".png")) {
                    return l.getString("file_path");
                }
            }
        }
        return "";
    }

    private JSONObject get(String path) throws IOException, JSONException {
        String key = settings.tmdbKey();
        // A v4 read access token (a long JWT) goes in a header; a v3 API key goes in the URL.
        boolean token = key.startsWith("eyJ");
        String url = API + path + (token ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + Uri.encode(key));
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        if (token) headers.put("Authorization", "Bearer " + key);
        return new JSONObject(Http.getText(url, headers));
    }
}
