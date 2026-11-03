package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Validates persisted TMDB entries and bounds their age and count at the storage boundary. */
final class TmdbCache {
    static final int MAX_ENTRIES = 512;
    static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final class Entry {
        final long time;
        final String paths, logo;
        Entry(long time, String paths, String logo) { this.time = time; this.paths = paths; this.logo = logo; }
    }

    static Entry parse(String raw, long now) {
        if (raw == null) return null;
        String[] fields = raw.split("\\|", -1);
        if (fields.length != 3) return null;
        long time;
        try { time = Long.parseLong(fields[0]); }
        catch (NumberFormatException invalid) { return null; }
        long ttl = (fields[1].isEmpty() ? 3 : 30) * DAY_MS;
        if (time < 0 || time > now || now - time >= ttl) return null;
        if (!fields[1].isEmpty()) for (String path : fields[1].split(",", -1))
            if (!path.startsWith("/") || path.length() == 1) return null;
        if (!fields[2].isEmpty() && (!fields[2].startsWith("/") || fields[2].length() == 1)) return null;
        return new Entry(time, fields[1], fields[2]);
    }

    static List<String> evictions(Map<String, ?> stored, long now) {
        List<String> remove = new ArrayList<>();
        List<Map.Entry<String, Long>> valid = new ArrayList<>();
        for (Map.Entry<String, ?> item : stored.entrySet()) {
            Entry entry = item.getValue() instanceof String raw ? parse(raw, now) : null;
            if (entry == null) remove.add(item.getKey());
            else valid.add(new java.util.AbstractMap.SimpleImmutableEntry<>(item.getKey(), entry.time));
        }
        valid.sort(Comparator.comparingLong(Map.Entry::getValue));
        for (int i = 0; i < valid.size() - MAX_ENTRIES; i++) remove.add(valid.get(i).getKey());
        return remove;
    }

    private TmdbCache() { }
}
