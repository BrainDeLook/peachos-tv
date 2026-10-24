package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What's on TV now, for the HDHomeRun app's backdrop: the shows airing on the user's own tuner, from
 * SiliconDust's free guide (what their app uses; no key or account). The tuner is found through
 * SiliconDust's discovery, which lists the tuners on the same internet connection, and gives the
 * token the guide asks for. The guide's images are small (480x270), so Backdrops looks each title
 * up for a sharp backdrop first.
 */
final class HdHomeRun {

    /** A show on now: its title and the guide's image. */
    static final class Show {
        final String title, image;

        Show(String title, String image) {
            this.title = title;
            this.image = image;
        }
    }

    private static final String DISCOVER = "https://api.hdhomerun.com/discover";
    private static final String GUIDE = "https://api.hdhomerun.com/api/guide.php?DeviceAuth=";
    private static final long GUIDE_MS = 30L * 60 * 1000;
    private static final long SOON_S = 30 * 60;   // shows starting within this count as on now
    /** Guide fillers with nothing worth showing. */
    private static final Set<String> SKIPPED = new HashSet<>(Arrays.asList(
            "paid programming", "to be announced", "sign off", "off air"));

    private final SharedPreferences cache;
    private List<Show> shows = Collections.emptyList();
    private long showsTime;

    HdHomeRun(Context context) {
        cache = context.getSharedPreferences("hdhomerun", Context.MODE_PRIVATE);
    }

    /** The shows on now across the tuner's channels; empty without a tuner or offline. Worker thread. */
    synchronized List<Show> onNow() {
        long now = System.currentTimeMillis();
        if (now - showsTime < GUIDE_MS && !shows.isEmpty()) return shows;
        try {
            String auth = deviceAuth();
            if (auth == null) return shows;
            JSONArray channels = new JSONArray(Http.getText(GUIDE + Uri.encode(auth), Collections.emptyMap()));
            long nowS = now / 1000;
            List<Show> found = new ArrayList<>();
            Set<String> titles = new HashSet<>();
            for (int i = 0; i < channels.length(); i++) {
                JSONArray guide = channels.getJSONObject(i).optJSONArray("Guide");
                for (int j = 0; guide != null && j < guide.length(); j++) {
                    JSONObject p = guide.getJSONObject(j);
                    if (p.optLong("StartTime") > nowS + SOON_S || p.optLong("EndTime") <= nowS) continue;
                    String title = p.optString("Title").trim(), image = p.optString("ImageURL");
                    // One per title: the same show often runs on a station's sister channels too.
                    if (title.isEmpty() || !image.startsWith("https://") || SKIPPED.contains(title.toLowerCase(Locale.ROOT))
                            || !titles.add(title)) continue;
                    found.add(new Show(title, image));
                }
            }
            shows = found;
            showsTime = now;
        } catch (IOException | JSONException | RuntimeException e) {
            // Offline, or the tuner or guide is unreachable: the last list, if any.
        }
        return shows;
    }

    /** The tuner's guide token (it changes, so asked for each time), or null without a tuner. */
    private String deviceAuth() {
        String ip = cache.getString("ip", "");
        String auth = ip.isEmpty() ? null : authFrom(ip);
        if (auth != null) return auth;
        try {
            // The tuner may have a new address: ask SiliconDust's discovery for the tuners here.
            JSONArray tuners = new JSONArray(Http.getText(DISCOVER, Collections.emptyMap()));
            for (int i = 0; i < tuners.length(); i++) {
                String found = tuners.getJSONObject(i).optString("LocalIP");
                if (found.isEmpty() || (auth = authFrom(found)) == null) continue;
                cache.edit().putString("ip", found).apply();
                return auth;
            }
        } catch (IOException | JSONException | RuntimeException e) {
            // Offline.
        }
        return null;
    }

    /** The token from the tuner at this home network address, or null when it isn't there. */
    private static String authFrom(String ip) {
        try {
            String auth = new JSONObject(Http.getLan(ip, "/discover.json")).optString("DeviceAuth");
            return auth.isEmpty() ? null : auth;
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }
}
