package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

/** Artwork behind the original Home layout. D-pad handling remains entirely in MainActivity. */
final class MetadataBackdrop {
    private final Context context;
    private final ImageView image;
    private final GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xB8000000, 0x00000000, 0x40000000});
    private Backdrops backdrops;
    private String requested;
    private String showing;
    private boolean active;
    private int generation;

    MetadataBackdrop(Context context, ImageView image) {
        this.context = context;
        this.image = image;
        ChannelSetup.run(context);
    }

    void resume() {
        if (active) return;
        active = true;
        String pkg = requested;
        showing = null;
        if (pkg != null) show(pkg);
    }

    void show(String pkg) {
        requested = pkg;
        if (!active || pkg == null || pkg.equals(showing)) return;
        showing = pkg;
        int ticket = ++generation;
        image.animate().cancel();
        image.setImageDrawable(null);
        image.setVisibility(View.GONE);
        if (BackdropPolicy.isStaticYouTube(pkg)) {
            image.setForeground(null);
            image.setImageDrawable(new YouTubeBackdrop());
            image.setAlpha(1f);
            image.setVisibility(View.VISIBLE);
            return;
        }
        if (backdrops == null) {
            backdrops = new Backdrops(context, first -> {
                if (active && first.equals(showing)) load(first, generation);
            });
        }
        load(pkg, ticket);
    }

    private void load(String pkg, int ticket) {
        if (backdrops == null || BackdropPolicy.isStaticYouTube(pkg)) return;
        backdrops.load(pkg, (loaded, bitmap, logo, title, link) -> {
            // A slow result must never paint Jellyfin over YouTube or over another app.
            if (!active || ticket != generation || !loaded.equals(showing) || bitmap == null) return;
            image.animate().cancel();
            image.setForeground(scrim);
            image.setImageBitmap(bitmap);
            image.setVisibility(View.VISIBLE);
            image.setAlpha(0f);
            image.animate().alpha(1f).setDuration(350).start();
            Log.i("MetadataBackdrop", "Backdrop ready for " + loaded);
        });
    }

    void hide() {
        requested = null;
        showing = null;
        ++generation;
        image.animate().cancel();
        image.setVisibility(View.GONE);
        image.setImageDrawable(null);
    }

    void pause() {
        active = false;
        showing = null;
        ++generation;
        image.animate().cancel();
        image.setVisibility(View.GONE);
        image.setImageDrawable(null);
        if (backdrops != null) {
            backdrops.close();
            backdrops = null;
        }
    }
}
