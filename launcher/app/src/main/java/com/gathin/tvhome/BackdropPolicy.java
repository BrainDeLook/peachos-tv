package com.gathin.tvhome;

/** YouTube video thumbnails are deliberately excluded from metadata backgrounds. */
final class BackdropPolicy {
    private BackdropPolicy() {}

    static boolean isStaticYouTube(String pkg) {
        if (pkg == null) return false;
        if (pkg.equals("com.google.android.youtube.tv") || pkg.equals("com.google.android.youtube.tvkids")) return true;
        for (String alias : SmartTube.PACKAGES) if (alias.equals(pkg)) return true;
        return false;
    }
}
