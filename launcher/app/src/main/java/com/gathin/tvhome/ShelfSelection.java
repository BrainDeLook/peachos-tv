package com.gathin.tvhome;

/** Source rules independent of Android. Never replace an explicitly selected empty source with another. */
final class ShelfSelection {
    static final String AUTO = "auto", OFF = "off", CONTINUE = "continue", HISTORY = "lampa-history";
    static final String LAMPA = "top.rootu.lampa";

    static boolean isHistory(String pkg, String providerId, String data, String link, String name) {
        if (!LAMPA.equals(pkg)) return false;
        return "history".equals(providerId) || "history".equals(data)
                || (link != null && link.endsWith("/update_channel/history"))
                || "история".equalsIgnoreCase(name) || "history".equalsIgnoreCase(name);
    }

    static boolean includes(String source, boolean watchNext, int watchType, boolean browsable,
                            long channelId, boolean history) {
        if (OFF.equals(source)) return false;
        if (CONTINUE.equals(source)) return watchNext && browsable && (watchType == 0 || watchType == 1);
        if (HISTORY.equals(source)) return !watchNext && history;
        if (source.startsWith("channel:")) return !watchNext && source.equals("channel:" + channelId);
        return AUTO.equals(source) && (!watchNext || (browsable && (watchType == 0 || watchType == 1)));
    }

    private ShelfSelection() { }
}
