package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small, immutable presentation snapshot of one Wi-Fi scan; no Android service or retained context. */
final class WifiAccessPoints {
    static final int MAX_ENTRIES = 32;

    enum Security {
        OPEN(false, 0), WEP(true, 1), PSK(true, 2), SAE(true, -1), OWE(false, -1),
        EAP(true, 3), UNKNOWN(true, -1);

        final boolean secured;
        final int tvSecurity;

        Security(boolean secured, int tvSecurity) {
            this.secured = secured;
            this.tvSecurity = tvSecurity;
        }
    }

    /** Converts external scan fields once. SSIDs are identifiers: spaces and case are significant. */
    static final class Observation {
        final String ssid, bssid;
        final Security security;
        final int bars;

        Observation(String ssid, String bssid, String capabilities, int rssi) {
            this.ssid = ssid == null ? "" : ssid;
            this.bssid = bssid == null ? "" : bssid;
            security = parseSecurity(capabilities);
            // Match the established five-level Wi-Fi indicator, with explicit saturation at either end.
            bars = rssi <= -100 ? 0 : rssi >= -55 ? 4 : (rssi + 100) * 4 / 45;
        }
    }

    static final class Entry {
        final String key, ssid;
        final Security security;
        final int bars;
        final boolean connected;

        private Entry(String key, Observation observation, int bars, boolean connected) {
            this.key = key;
            ssid = observation.ssid;
            security = observation.security;
            this.bars = bars;
            this.connected = connected;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Entry entry && key.equals(entry.key)
                    && bars == entry.bars && connected == entry.connected;
        }

        @Override public int hashCode() {
            return (key.hashCode() * 31 + bars) * 31 + (connected ? 1 : 0);
        }
    }

    private static final Comparator<Entry> ORDER = Comparator
            .comparing((Entry entry) -> !entry.connected)
            .thenComparing(Comparator.comparingInt((Entry entry) -> entry.bars).reversed())
            .thenComparing(entry -> entry.ssid, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> entry.security)
            .thenComparing(entry -> entry.ssid);

    private WifiAccessPoints() { }

    static List<Entry> build(List<Observation> observations, String currentSsid, String currentBssid) {
        boolean matchBssid = currentBssid != null && !currentBssid.isEmpty();
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (Observation observation : observations) {
            if (observation.ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(observation.ssid)) continue;
            String key = observation.ssid + '\u0000' + observation.security;
            boolean connected = matchBssid
                    ? currentBssid.equalsIgnoreCase(observation.bssid)
                    : observation.ssid.equals(currentSsid);
            Entry previous = unique.get(key);
            int bars = previous == null ? observation.bars : Math.max(previous.bars, observation.bars);
            connected |= previous != null && previous.connected;
            if (previous == null || bars != previous.bars || connected != previous.connected)
                unique.put(key, new Entry(key, observation, bars, connected));
        }
        List<Entry> entries = new ArrayList<>(unique.values());
        entries.sort(ORDER);
        if (entries.size() > MAX_ENTRIES) entries.subList(MAX_ENTRIES, entries.size()).clear();
        return Collections.unmodifiableList(entries);
    }

    private static Security parseSecurity(String capabilities) {
        if (capabilities == null) return Security.UNKNOWN;
        String value = capabilities.toUpperCase(Locale.ROOT);
        // Do not present unsupported enterprise/WAPI authentication as an ordinary password network.
        if (value.contains("WAPI") || value.contains("SUITE_B")
                || value.contains("EAP/SHA256") || value.contains("EAP_SHA256")
                || value.contains("EAP") && (value.contains("WPA3") || value.contains("MFPR")))
            return Security.UNKNOWN;
        if (value.contains("EAP")) return Security.EAP;
        if (value.contains("WEP")) return Security.WEP;
        // Transition networks permit the ordinary WPA2 path on Android TV's existing connector.
        if (value.contains("PSK")) return Security.PSK;
        if (value.contains("SAE")) return Security.SAE;
        if (value.contains("OWE")) return Security.OWE;
        if (value.contains("RSN") || value.contains("WPA")) return Security.UNKNOWN;
        return Security.OPEN;
    }
}
