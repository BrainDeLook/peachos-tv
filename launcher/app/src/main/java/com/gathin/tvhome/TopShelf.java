package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Focusable top shelf. Queries and image decoding never run on Home's main thread. */
final class TopShelf {
    interface Navigation { void dock(); void header(); }
    private final Activity activity;
    private final View hero;
    private final View dim;
    private final Navigation navigation;
    private final LinearLayout panel, cards;
    private final TextView heading;
    private final HorizontalScrollView scroll;
    private final List<View> targets = new ArrayList<>();
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(10 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private ExecutorService queries, downloads;
    private volatile String requested;
    private String label;
    private volatile int generation;
    private int selected;
    private volatile boolean active;
    private float dockTop;

    TopShelf(Activity activity, FrameLayout root, View hero, Navigation navigation) {
        this.activity = activity; this.hero = hero; this.navigation = navigation;
        dim = new View(activity);
        dim.setBackgroundColor(0x99000000);
        dim.setVisibility(View.GONE);
        dim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(dim, root.indexOfChild(root.findViewById(R.id.metadataBackdrop)) + 1,
                new FrameLayout.LayoutParams(-1, -1));
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setVisibility(View.GONE);
        heading = new TextView(activity);
        heading.setTextColor(0xffffffff); heading.setTextSize(19);
        heading.setSingleLine(true); heading.setPadding(dp(8), 0, 0, dp(5));
        panel.addView(heading);
        scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false); scroll.setClipToPadding(false);
        scroll.setPadding(dp(7), dp(7), dp(7), dp(7));
        cards = new LinearLayout(activity);
        cards.setOrientation(LinearLayout.HORIZONTAL); cards.setClipChildren(false);
        scroll.addView(cards);
        panel.addView(scroll);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        lp.leftMargin = dp(56); lp.rightMargin = dp(56);
        root.addView(panel, lp);
    }

    void resume() {
        if (active) return;
        active = true;
        queries = Executors.newSingleThreadExecutor();
        downloads = Executors.newFixedThreadPool(2);
        String pkg = requested;
        requested = null;
        if (pkg != null) show(pkg, label, dockTop);
    }

    void show(String pkg, String appLabel, float top) {
        dockTop = top; label = appLabel;
        if (pkg == null || pkg.equals(requested)) { position(); return; }
        requested = pkg;
        int ticket = ++generation;
        clear();
        if (!active || ShelfSelection.OFF.equals(ShelfRepository.source(activity, pkg))) return;
        // A short debounce avoids building lists for every app crossed while holding Right.
        panel.postDelayed(() -> {
            if (!valid(ticket, pkg)) return;
            queries.execute(() -> {
                ShelfRepository.Result result = ShelfRepository.read(activity, pkg, ShelfRepository.source(activity, pkg));
                activity.runOnUiThread(() -> {
                    if (valid(ticket, pkg)) render(pkg, result, ticket);
                });
            });
        }, 180);
    }

    private void render(String pkg, ShelfRepository.Result result, int ticket) {
        if (result.items.isEmpty()) return; // keep the original hero, never substitute unrelated recommendations
        String source = ShelfRepository.source(activity, pkg);
        String section = activity.getString(ShelfSelection.HISTORY.equals(source) ? R.string.shelf_history
                : ShelfSelection.CONTINUE.equals(source) ? R.string.shelf_continue : R.string.shelf_cards);
        if (source.startsWith("channel:")) for (ShelfRepository.Channel channel : result.channels) {
            if (source.equals("channel:" + channel.id)) section = channel.name;
        }
        heading.setText(ShelfSelection.AUTO.equals(source) ? label : section);
        cards.removeAllViews(); targets.clear(); selected = 0;
        int height = Math.max(dp(80), Math.min(dp(168), (int) dockTop - dp(210)));
        for (ShelfRepository.Item item : result.items) {
            LinearLayout tile = new LinearLayout(activity);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(3), dp(3), dp(3), dp(3));
            tile.setClipChildren(false);
            tile.setFocusable(true); tile.setClickable(true);
            tile.setContentDescription(item.title);
            ImageView poster = new ImageView(activity);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable placeholder = new GradientDrawable();
            placeholder.setColor(0xff25303d); placeholder.setCornerRadius(dp(8));
            poster.setBackground(placeholder);
            poster.setClipToOutline(true);
            tile.addView(poster, new LinearLayout.LayoutParams(-1, height));
            TextView title = new TextView(activity);
            title.setText(item.title); title.setTextSize(11); title.setTextColor(0xffd9dfe6);
            title.setAlpha(0f);
            title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(dp(3), dp(5), dp(3), 0);
            tile.addView(title, new LinearLayout.LayoutParams(-1, dp(26)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(118), -2);
            lp.rightMargin = dp(18); cards.addView(tile, lp); targets.add(tile);
            int index = targets.size() - 1;
            final int[] navigationKey = {-1};
            tile.setOnFocusChangeListener((v, focus) -> {
                tile.animate().scaleX(focus ? 1.045f : 1f).scaleY(focus ? 1.045f : 1f)
                        .translationZ(focus ? dp(8) : 0).setDuration(140).start();
                title.setTextColor(focus ? 0xffffffff : 0xffc0c6cc);
                title.animate().alpha(focus ? 1f : 0f).setDuration(140).start();
                if (focus) { selected = index; scroll.smoothScrollTo(Math.max(0, tile.getLeft() - dp(14)), 0); }
            });
            tile.setOnKeyListener((v, key, event) -> {
                if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) navigationKey[0] = key;
                    else if (navigationKey[0] == key) { navigationKey[0] = -1; navigation.dock(); }
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    // The Up release that entered the shelf belongs to the dock, not to this card.
                    if (event.getAction() == KeyEvent.ACTION_DOWN) navigationKey[0] = key;
                    else if (navigationKey[0] == key) { navigationKey[0] = -1; navigation.header(); }
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int next = index + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        if (next >= 0 && next < targets.size()) targets.get(next).requestFocus();
                    }
                    return true;
                }
                return false;
            });
            tile.setOnClickListener(v -> open(pkg, item));
            loadImage(item.image, poster, ticket, pkg);
        }
        if (hero.getVisibility() != View.GONE) hero.setVisibility(View.INVISIBLE);
        dim.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
        scroll.scrollTo(0, 0);
        panel.post(this::position);
    }

    boolean focus() {
        return panel.getVisibility() == View.VISIBLE && !targets.isEmpty()
                && targets.get(Math.min(selected, targets.size() - 1)).requestFocus();
    }
    boolean hasFocus() { return panel.hasFocus(); }
    private void position() {
        if (panel.getVisibility() == View.VISIBLE) panel.setY(Math.max(dp(116), dockTop - panel.getHeight() - dp(22)));
    }
    private boolean valid(int ticket, String pkg) { return active && ticket == generation && pkg.equals(requested); }

    void hide() { requested = null; generation++; clear(); }
    void pause() {
        active = false; generation++; clear();
        if (queries != null) queries.shutdownNow();
        if (downloads != null) downloads.shutdownNow();
        images.evictAll();
    }
    private void clear() {
        panel.setVisibility(View.GONE); cards.removeAllViews(); targets.clear();
        dim.setVisibility(View.GONE);
        if (hero.getVisibility() != View.GONE) hero.setVisibility(View.VISIBLE);
    }

    private void open(String pkg, ShelfRepository.Item item) {
        try {
            if (item.link.isEmpty()) throw new IllegalArgumentException("No app link");
            Intent intent = Intent.parseUri(item.link, Intent.URI_INTENT_SCHEME);
            if (intent.getComponent() != null && !pkg.equals(intent.getComponent().getPackageName())) {
                throw new IllegalArgumentException("Different app");
            }
            intent.setSelector(null); intent.setClipData(null); intent.setPackage(pkg);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.getAction() == null) intent.setAction(Intent.ACTION_VIEW);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.shelf_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void loadImage(String url, ImageView target, int ticket, String pkg) {
        if (url.isEmpty()) return;
        Bitmap cached = images.get(url);
        if (cached != null) { target.setImageBitmap(cached); return; }
        downloads.execute(() -> {
            if (!valid(ticket, pkg) || Thread.currentThread().isInterrupted()) return;
            Bitmap bitmap = null;
            try {
                byte[] bytes;
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    bytes = Http.get(url, 5 * 1024 * 1024);
                } else if (url.startsWith("content://") || url.startsWith("android.resource://")) {
                    try (InputStream in = activity.getContentResolver().openInputStream(Uri.parse(url));
                         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        if (in == null) return;
                        byte[] buffer = new byte[16384]; int count;
                        while ((count = in.read(buffer)) != -1) {
                            if (out.size() + count > 5 * 1024 * 1024) return;
                            out.write(buffer, 0, count);
                        }
                        bytes = out.toByteArray();
                    }
                } else return;
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
                BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = 1;
                while (bounds.outWidth / opts.inSampleSize > 480 || bounds.outHeight / opts.inSampleSize > 720) opts.inSampleSize *= 2;
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            } catch (Exception e) {
                // No titles, private URLs or tokens in the log. Keep text and focus usable offline.
                Uri uri = Uri.parse(url);
                android.util.Log.w("TopShelf", "Poster unavailable for " + pkg + " via "
                        + uri.getScheme() + "://" + uri.getHost() + ": " + e.getClass().getSimpleName());
            }
            Bitmap ready = bitmap;
            activity.runOnUiThread(() -> {
                if (ready != null && valid(ticket, pkg)) { images.put(url, ready); target.setImageBitmap(ready); }
            });
        });
    }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
