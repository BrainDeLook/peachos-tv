package com.gathin.tvhome;

/** Older Lampa versions publish TMDB posters as HTTP. The identical resource is available over HTTPS. */
final class ShelfArtworkUrl {
    static String normalize(String url) {
        return url.startsWith("http://image.tmdb.org/") ? "https://" + url.substring(7) : url;
    }
    private ShelfArtworkUrl() { }
}
