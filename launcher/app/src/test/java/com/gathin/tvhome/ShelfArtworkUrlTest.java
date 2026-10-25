package com.gathin.tvhome;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShelfArtworkUrlTest {
    @Test public void upgradesLegacyLampaPosterWithoutChangingItsIdentity() {
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg?x=1",
                ShelfArtworkUrl.normalize("http://image.tmdb.org/t/p/w500/a.jpg?x=1"));
    }
    @Test public void leavesOtherProvidersAndSchemesUnchanged() {
        for (String url : new String[]{"content://app/image/1", "https://image.tmdb.org/a.jpg",
                "http://image.tmdb.org.other/a.jpg", "http://server.local/a.jpg", ""}) {
            assertEquals(url, ShelfArtworkUrl.normalize(url));
        }
    }
}
