package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackdropPolicyTest {
    @Test public void youtubeAndEverySmartTubeBuildUseLocalBrandArt() {
        assertTrue(BackdropPolicy.isStaticYouTube("com.google.android.youtube.tv"));
        assertTrue(BackdropPolicy.isStaticYouTube("com.google.android.youtube.tvkids"));
        assertTrue(BackdropPolicy.isStaticYouTube("org.smarttube.stable"));
        assertTrue(BackdropPolicy.isStaticYouTube("org.smarttube.beta"));
        assertTrue(BackdropPolicy.isStaticYouTube("com.liskovsoft.smarttubetv.beta"));
        assertTrue(BackdropPolicy.isStaticYouTube("com.liskovsoft.smartyoutubetv2.tv"));
        assertTrue(BackdropPolicy.isStaticYouTube("com.teamsmart.videomanager.tv"));
    }

    @Test public void movieAndUtilityAppsAreNotMistakenForYouTube() {
        assertFalse(BackdropPolicy.isStaticYouTube("org.jellyfin.androidtv"));
        assertFalse(BackdropPolicy.isStaticYouTube("ru.kinopoisk.tv"));
        assertFalse(BackdropPolicy.isStaticYouTube("top.rootu.lampa"));
        assertFalse(BackdropPolicy.isStaticYouTube("com.alphainventor.filemanager"));
        assertFalse(BackdropPolicy.isStaticYouTube(null));
    }
}
