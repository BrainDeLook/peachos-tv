package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Focusable top shelf. Queries and image decoding never run on Home's main thread. */
final class TopShelf {
    private static final android.view.animation.DecelerateInterpolator ROW_EASING =
            new android.view.animation.DecelerateInterpolator();
    private static final android.util.IntProperty<ImageView> IMAGE_ALPHA = new android.util.IntProperty<ImageView>("imageAlpha") {
        @Override public Integer get(ImageView image) { return image.getImageAlpha(); }
        @Override public void setValue(ImageView image, int alpha) { image.setImageAlpha(alpha); }
    };
    interface Navigation { void dock(); void header(); void fallback(float top); boolean settling(); void appLaunched(); }
    private final Activity activity;
    private final View hero;
    private final View dim;
    private final Navigation navigation;
    private final LinearLayout panel, cards;
    private final TextView heading;
    private final SpringAnimation headingSpring;
    private float headingTarget = Float.NaN;
    private final HorizontalScrollView scroll;
    private final List<View> targets = new ArrayList<>();
    private final ShelfImages images;
    private final android.graphics.Typeface bodyFace;
    private final boolean animateRows;
    private ExecutorService queries;
    private final ThreadPoolExecutor downloads = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), task -> new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "shelf-images"));
    private final List<Binding> bindings = new ArrayList<>();
    private static final class Snapshot {
        final ShelfRepository.Result result;
        final long at = android.os.SystemClock.elapsedRealtime();
        Snapshot(ShelfRepository.Result result) { this.result = result; }
    }
    private final Map<String, Snapshot> lists = new LinkedHashMap<String, Snapshot>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Snapshot> entry) { return size() > 6; }
    };
    private static final class Binding {
        FrameLayout tile;
        ImageView image;
        android.graphics.drawable.Drawable placeholder;
        TextView title;
        ShelfRepository.Item item;
        int rowHeight, maxWidth;
        boolean loading, loaded;
        volatile boolean wanted;
        long failedAt;
        android.animation.ObjectAnimator reveal;
    }
    private volatile String requested;
    private String label;
    private volatile int generation;
    private int selected;
    private volatile boolean active;
    private volatile boolean suspended;
    private boolean releaseFinishedImages;
    private boolean cardMode;
    private boolean rowExiting, awaitingRow;
    private String renderedPkg;
    private float dockTop;
    private float dimTarget;
    private final Runnable releaseImages = this::releaseDistantImages;
    private boolean refreshQueued;
    private final Runnable refreshImages = () -> { refreshQueued = false; updateVisible(); };

    TopShelf(Activity activity, FrameLayout root, View hero, Navigation navigation, boolean animateRows) {
        this.activity = activity; this.hero = hero; this.navigation = navigation;
        this.animateRows = animateRows;
        images = new ShelfImages(activity);
        bodyFace = activity.getResources().getFont(R.font.inter_regular);
        dim = new View(activity);
        // Flatten the static gradients once: scrolling and fades then draw one small texture.
        android.util.DisplayMetrics screen = activity.getResources().getDisplayMetrics();
        int backdropWidth = 320;
        int backdropHeight = Math.round(backdropWidth * screen.heightPixels / (float) screen.widthPixels);
        Bitmap backdrop = Bitmap.createBitmap(backdropWidth, backdropHeight, Bitmap.Config.ARGB_8888);
        Canvas backdropCanvas = new Canvas(backdrop);
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff2a232a, 0xff262027, 0xff18212d, 0xff20181b});
        base.setBounds(0, 0, backdropWidth, backdropHeight);
        base.draw(backdropCanvas);
        GradientDrawable warmth = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2a7d3218, 0x007d3218});
        warmth.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        warmth.setGradientCenter(.5f, .14f);
        warmth.setGradientRadius(backdropWidth * .48f);
        warmth.setBounds(0, 0, backdropWidth, backdropHeight);
        warmth.draw(backdropCanvas);
        BitmapDrawable backdropDrawable = new BitmapDrawable(activity.getResources(), backdrop);
        backdropDrawable.setGravity(Gravity.FILL);
        backdropDrawable.setFilterBitmap(true);
        dim.setBackground(backdropDrawable);
        dim.setVisibility(View.GONE);
        dim.setAlpha(0f);
        dim.forceHasOverlappingRendering(false);
        dim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(dim, root.indexOfChild(root.findViewById(R.id.metadataBackdrop)) + 1,
                new FrameLayout.LayoutParams(-1, -1));
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        // Cards, captions and heading occupy separate areas; fade their existing render nodes directly.
        panel.forceHasOverlappingRendering(false);
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> position());
        panel.setVisibility(View.GONE);
        heading = new TextView(activity);
        heading.setTypeface(activity.getResources().getFont(R.font.inter_semibold));
        heading.setTextColor(0x66ffffff); heading.setTextSize(24);
        heading.setIncludeFontPadding(false);
        heading.setSingleLine(true); heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setPadding(dp(45), 0, dp(45), 0);
        // Keep moving glyphs intact while a taller neighbour scrolls beneath their rebound.
        heading.setTranslationZ(dp(12)); // Focused artwork uses 8dp of translationZ.
        headingSpring = new SpringAnimation(heading, SpringAnimation.TRANSLATION_Y);
        headingSpring.setSpring(new SpringForce().setStiffness(520f).setDampingRatio(.62f));
        headingSpring.setMinimumVisibleChange(.25f);
        panel.addView(heading);
        scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false); scroll.setClipToPadding(false);
        scroll.forceHasOverlappingRendering(false);
        // Focus zoom extends above the inner cards row into the scroll view's existing padding.
        // Do not clip to that child's bounds; the root still clips at the physical screen edges.
        scroll.setClipChildren(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setHorizontalFadingEdgeEnabled(false);
        scroll.setPadding(0, dp(8), 0, dp(8));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> scheduleVisible());
        cards = new LinearLayout(activity);
        cards.setOrientation(LinearLayout.HORIZONTAL); cards.setClipChildren(false); cards.setClipToPadding(false);
        cards.forceHasOverlappingRendering(false);
        // Content has an initial inset; the viewport itself reaches both physical screen edges.
        cards.setPadding(dp(40), 0, dp(40), 0);
        cards.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> scheduleVisible());
        scroll.addView(cards);
        LinearLayout.LayoutParams scrollLayout = new LinearLayout.LayoutParams(-1, -2);
        scrollLayout.topMargin = -dp(6);
        panel.addView(scroll, scrollLayout);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        root.addView(panel, lp);
    }

    void resume() {
        if (active) return;
        active = true;
        releaseFinishedImages = false;
        queries = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "shelf-metadata"));
        lists.clear(); // refresh history after returning from playback; thumbnails remain on disk
        String pkg = requested;
        requested = null;
        if (pkg != null) show(pkg, label, dockTop);
    }

    void show(String pkg, String appLabel, float top) {
        dockTop = top; label = appLabel;
        if (pkg == null) return;
        if (pkg.equals(requested)) {
            if (suspended) {
                suspended = false;
                if (!bindings.isEmpty() && pkg.equals(renderedPkg)) {
                    if (hero.getVisibility() != View.GONE) hero.setVisibility(View.INVISIBLE);
                    awaitingRow = false;
                    setDim(true);
                    presentRow(generation, pkg);
                } else {
                    requested = null;
                    show(pkg, appLabel, top);
                }
            }
            position(); return;
        }
        requested = pkg;
        suspended = false;
        String source = ShelfRepository.source(activity, pkg);
        String key = pkg + "\n" + source;
        Snapshot cached = lists.get(key);
        boolean fresh = cached != null && android.os.SystemClock.elapsedRealtime() - cached.at < 60_000;
        // Unknown results stay hidden while loading; a known empty list uses the ordinary app title.
        cardMode = !ShelfSelection.OFF.equals(source) && (!fresh || !cached.result.items.isEmpty());
        int ticket = ++generation;
        awaitingRow = true;
        dismissRow(ticket, pkg);
        if (hero.getVisibility() != View.GONE) {
            hero.animate().cancel();
            hero.setVisibility(cardMode ? View.INVISIBLE : View.VISIBLE);
        }
        images.stats();
        setDim(active && !ShelfSelection.OFF.equals(source));
        if (!active || ShelfSelection.OFF.equals(source)) return;
        if (fresh) {
            whenSettled(ticket, pkg, () -> render(pkg, cached.result, ticket));
            return;
        }
        // A short debounce avoids building lists for every app crossed while holding Right.
        panel.postDelayed(() -> {
            if (!valid(ticket, pkg)) return;
            queries.execute(() -> {
                if (!valid(ticket, pkg)) return;
                ShelfRepository.Result result = ShelfRepository.read(activity, pkg, source);
                activity.runOnUiThread(() -> {
                    if (valid(ticket, pkg)) {
                        lists.put(key, new Snapshot(result));
                        whenSettled(ticket, pkg, () -> render(pkg, result, ticket));
                    }
                });
            });
        }, 120);
    }

    private void dismissRow(int ticket, String pkg) {
        panel.animate().cancel();
        refreshQueued = false;
        panel.removeCallbacks(refreshImages); panel.removeCallbacks(releaseImages);
        downloads.getQueue().clear();
        if (!animateRows || panel.getVisibility() != View.VISIBLE || bindings.isEmpty() || panel.getAlpha() == 0f) {
            clear(); return;
        }
        rowExiting = true;
        panel.animate().alpha(0f).setDuration(120)
                .setInterpolator(ROW_EASING)
                .withEndAction(() -> {
                    if (!valid(ticket, pkg)) return;
                    clear();
                    if (!cardMode) navigation.fallback(dockTop);
                }).start();
    }

    private void whenSettled(int ticket, String pkg, Runnable action) {
        panel.postDelayed(() -> {
            if (!valid(ticket, pkg)) return;
            if (navigation.settling() || rowExiting) whenSettled(ticket, pkg, action);
            else action.run();
        }, 32);
    }

    private void render(String pkg, ShelfRepository.Result result, int ticket) {
        awaitingRow = false;
        if (result.items.isEmpty()) {
            cardMode = false;
            if (hero.getVisibility() != View.GONE) hero.setVisibility(View.VISIBLE);
            navigation.fallback(dockTop);
            return; // no posters: restore this app's title, never unrelated recommendations
        }
        cardMode = true;
        renderedPkg = pkg;
        String source = ShelfRepository.source(activity, pkg);
        String section = activity.getString(ShelfSelection.HISTORY.equals(source) ? R.string.shelf_history
                : ShelfSelection.CONTINUE.equals(source) ? R.string.shelf_continue : R.string.shelf_cards);
        if (source.startsWith("channel:")) for (ShelfRepository.Channel channel : result.channels) {
            if (source.equals("channel:" + channel.id)) section = channel.name;
        }
        heading.setText(ShelfSelection.AUTO.equals(source) ? label : section);
        headingSpring.cancel(); headingTarget = Float.NaN;
        cards.removeAllViews(); targets.clear(); bindings.clear(); selected = 0;
        int viewport = activity.getResources().getDisplayMetrics().widthPixels;
        // Five full portrait cards, with the next one peeking in at the physical right edge.
        int widthForFive = (viewport - dp(80) - 4 * dp(20)) / 5;
        TextView captionMeasure = new TextView(activity);
        captionMeasure.setTextSize(11);
        captionMeasure.setTypeface(bodyFace);
        int captionHeight = (int) Math.ceil(captionMeasure.getPaint().getFontSpacing() * 2) + dp(2);
        int headingHeight = (int) Math.ceil(heading.getPaint().getFontMetrics().descent
                - heading.getPaint().getFontMetrics().ascent) + heading.getPaddingBottom();
        int available = (int) dockTop - dp(28) - topClearance() - headingHeight - dp(38)
                - captionHeight - dp(14);
        int height = Math.max(dp(80), Math.min(Math.round((widthForFive - dp(10)) * 1.5f),
                Math.min(dp(252), (int) (available / 1.0225f))));
        android.view.ViewOutlineProvider posterOutline = new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
            }
        };
        for (ShelfRepository.Item item : result.items) {
            FrameLayout tile = new FrameLayout(activity);
            tile.setClipChildren(false);
            // Artwork and caption have separate bounds, including the artwork's focus zoom clearance.
            tile.forceHasOverlappingRendering(false);
            tile.setFocusable(true); tile.setClickable(true);
            tile.setContentDescription(item.title);
            ImageView poster = new ImageView(activity);
            // Fill the rounded bounds even when a provider's aspect hint is slightly inaccurate.
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            android.graphics.drawable.ColorDrawable placeholder = new android.graphics.drawable.ColorDrawable(0xff25303d);
            poster.setImageDrawable(placeholder);
            poster.setOutlineProvider(posterOutline);
            poster.setClipToOutline(true);
            // A poster draws one image, rather than an overlapping placeholder background plus bitmap.
            poster.forceHasOverlappingRendering(false);
            tile.addView(poster, new FrameLayout.LayoutParams(-1, height, android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL));
            TextView title = new TextView(activity);
            title.setText(item.title); title.setTextSize(11); title.setTextColor(0xf2ffffff);
            title.setTypeface(bodyFace);
            title.setAlpha(0f);
            title.setIncludeFontPadding(false);
            title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(title, new FrameLayout.LayoutParams(-1, captionHeight,
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(widthForFive,
                    height + captionHeight + dp(14) + Math.round(height * .0225f));
            lp.rightMargin = dp(20); cards.addView(tile, lp); targets.add(tile);
            Binding binding = new Binding();
            binding.tile = tile; binding.image = poster; binding.placeholder = placeholder; binding.title = title; binding.item = item;
            binding.rowHeight = height; binding.maxWidth = Math.round(viewport * .35f);
            bindings.add(binding);
            size(binding, item.aspect);
            int index = targets.size() - 1;
            final int[] navigationKey = {-1};
            tile.setOnFocusChangeListener((v, focus) -> {
                poster.animate().scaleX(focus ? 1.045f : 1f).scaleY(focus ? 1.045f : 1f)
                        .translationZ(focus ? dp(8) : 0).setDuration(140).start();
                title.setTextColor(focus ? 0xf2ffffff : 0xb3ffffff);
                title.animate().alpha(focus ? 1f : 0f).setDuration(140).start();
                if (focus) {
                    selected = index;
                    position();
                    scroll.smoothScrollTo(Math.max(0, tile.getLeft() - dp(40)), 0);
                }
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
            tile.setOnClickListener(v -> {
                if (active && !suspended && !awaitingRow && pkg.equals(requested) && pkg.equals(renderedPkg)) open(pkg, item);
            });
        }
        if (hero.getVisibility() != View.GONE) hero.setVisibility(View.INVISIBLE);
        setDim(true);
        scroll.scrollTo(0, 0);
        presentRow(ticket, pkg);
    }

    private void presentRow(int ticket, String pkg) {
        panel.animate().cancel();
        panel.setAlpha(animateRows ? 0f : 1f);
        panel.setVisibility(View.VISIBLE);
        panel.post(() -> {
            if (!valid(ticket, pkg)) return;
            position(); scheduleVisible();
            if (animateRows) panel.animate().alpha(1f).setDuration(220)
                    .setInterpolator(ROW_EASING).start();
        });
    }

    boolean focus() {
        return !awaitingRow && requested != null && requested.equals(renderedPkg)
                && panel.getVisibility() == View.VISIBLE && !targets.isEmpty()
                && targets.get(Math.min(selected, targets.size() - 1)).requestFocus();
    }
    boolean hasFocus() { return panel.hasFocus(); }
    boolean replacesHero() { return (cardMode || rowExiting) && !suspended; }
    boolean coversBackdrop() { return dim.isShown() && dim.getAlpha() == 1f; }
    private void position() {
        // Keep a calm gap above the dock, including room for a focused poster's caption and zoom.
        if (panel.getVisibility() != View.VISIBLE) return;
        panel.setY(dockTop - panel.getHeight() - dp(38));
        if (bindings.isEmpty()) return;
        // Follow the selected artwork, including an aspect corrected after decoding.
        FrameLayout.LayoutParams artwork = (FrameLayout.LayoutParams) bindings.get(selected).image.getLayoutParams();
        // Leave a small gap for the lower rebound instead of letting the label hit the artwork.
        float offset = artwork.topMargin - dp(7);
        if (headingTarget == offset) return;
        boolean firstPosition = Float.isNaN(headingTarget);
        headingTarget = offset;
        if (!animateRows || firstPosition || panel.getAlpha() == 0f) {
            headingSpring.cancel();
            heading.setTranslationY(offset);
        } else {
            // Retarget the same spring so rapid opposite moves retain position and velocity.
            // Bound residual momentum too: repeated reversals must not throw text outside its gap.
            float current = heading.getTranslationY();
            headingSpring.setMinValue(Math.min(current, offset) - dp(2));
            headingSpring.setMaxValue(Math.max(current, offset) + dp(2));
            headingSpring.animateToFinalPosition(offset);
        }
    }
    private void setDim(boolean enabled) {
        float target = enabled ? 1f : 0f;
        if (dimTarget == target) return;
        dimTarget = target;
        dim.animate().cancel();
        if (!animateRows) {
            dim.setAlpha(target);
            dim.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        dim.setVisibility(View.VISIBLE);
        dim.animate().alpha(target).setDuration(320).withEndAction(() -> {
            if (target == 0f && dimTarget == target) dim.setVisibility(View.INVISIBLE);
        }).start();
    }
    private int topClearance() {
        View buttons = activity.findViewById(R.id.topButtons);
        return Math.max(dp(44), buttons == null ? 0 : buttons.getBottom() + dp(8));
    }
    private boolean valid(int ticket, String pkg) { return active && !suspended && ticket == generation && pkg.equals(requested); }

    void hide() {
        if (suspended) return;
        headingSpring.cancel(); headingTarget = Float.NaN;
        suspended = true; generation++;
        panel.animate().cancel(); rowExiting = false;
        downloads.getQueue().clear();
        for (Binding b : bindings) {
            b.loading = false;
            if (b.reveal != null) { b.reveal.cancel(); b.reveal = null; }
            if (b.loaded) b.image.setImageAlpha(255);
        }
        refreshQueued = false;
        panel.removeCallbacks(refreshImages); panel.removeCallbacks(releaseImages);
        panel.setVisibility(View.INVISIBLE); setDim(false);
        if (hero.getVisibility() != View.GONE) hero.setVisibility(View.VISIBLE);
    }
    void pause() {
        active = false; generation++; clear();
        dim.animate().cancel(); dimTarget = 0f; dim.setAlpha(0f); dim.setVisibility(View.GONE);
        if (queries != null) queries.shutdownNow();
        downloads.getQueue().clear();
        // Keep only the existing 2 MiB LRU for a quick Home return; all poster views are released.
        // Real background pressure and close() still release it, including an in-flight result.
        lists.clear();
        images.stats();
    }
    void close() { pause(); downloads.shutdownNow(); images.trim(); }
    void trim(int level) {
        if (MemoryTrimPolicy.shouldRelease(level, false)) {
            images.trim();
            if (!active) releaseFinishedImages = true;
        }
    }
    private void clear() {
        headingSpring.cancel(); headingTarget = Float.NaN;
        panel.animate().cancel(); rowExiting = false;
        refreshQueued = false;
        panel.removeCallbacks(refreshImages); panel.removeCallbacks(releaseImages);
        downloads.getQueue().clear();
        for (Binding b : bindings) if (b.reveal != null) b.reveal.cancel();
        bindings.clear();
        renderedPkg = null;
        panel.setAlpha(0f);
        panel.setVisibility(View.GONE); cards.removeAllViews(); targets.clear();
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
            if (!activity.getPackageName().equals(pkg)) navigation.appLaunched();
        } catch (Exception e) {
            Toast.makeText(activity, R.string.shelf_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void size(Binding binding, float aspect) {
        int[] dimensions = ShelfGeometry.size(aspect, binding.rowHeight, binding.maxWidth);
        binding.item.aspect = aspect;
        int viewport = activity.getResources().getDisplayMetrics().widthPixels;
        int portraitSlot = (viewport - dp(80) - 4 * dp(20)) / 5;
        binding.tile.getLayoutParams().width = Math.max(portraitSlot, dimensions[0] + dp(6));
        FrameLayout.LayoutParams artwork = (FrameLayout.LayoutParams) binding.image.getLayoutParams();
        artwork.width = dimensions[0]; artwork.height = dimensions[1];
        artwork.topMargin = dp(3) + (binding.rowHeight - dimensions[1]) / 2;
        FrameLayout.LayoutParams caption = (FrameLayout.LayoutParams) binding.title.getLayoutParams();
        caption.width = dimensions[0];
        caption.topMargin = artwork.topMargin + dimensions[1] + Math.round(dimensions[1] * .0225f) + dp(7);
        binding.tile.requestLayout();
        position();
    }
    private boolean inWindow(Binding b, int spare) {
        if (b.tile.getWidth() == 0) return false; // before layout every unmeasured tile has left/right == 0
        return b.tile.getRight() >= scroll.getScrollX() - spare
                && b.tile.getLeft() <= scroll.getScrollX() + scroll.getWidth() + spare;
    }
    private void scheduleVisible() {
        if (refreshQueued || !active || suspended || awaitingRow || rowExiting
                || panel.getVisibility() != View.VISIBLE) return;
        refreshQueued = true;
        panel.postOnAnimation(refreshImages);
    }
    private void updateVisible() {
        if (!active || awaitingRow || rowExiting || panel.getVisibility() != View.VISIBLE || scroll.getWidth() == 0) return;
        position();
        panel.removeCallbacks(refreshImages);
        if (navigation.settling()) { refreshQueued = true; panel.postDelayed(refreshImages, 32); return; }
        panel.removeCallbacks(releaseImages);
        panel.postDelayed(releaseImages, 300);
        final int ticket = generation;
        final String pkg = requested;
        for (Binding b : bindings) b.wanted = inWindow(b, scroll.getWidth() / 2);
        // Visible artwork gets ahead of speculative neighbours in the single worker's queue.
        for (int pass = 0; pass < 2; pass++) for (Binding b : bindings) {
            if (!b.wanted || inWindow(b, 0) != (pass == 0)) continue;
            if (b.loaded || b.loading || b.item.image.isEmpty()
                    || (b.failedAt != 0 && android.os.SystemClock.elapsedRealtime() - b.failedAt < 60_000)) continue;
            Bitmap cached = images.peek(b.item.image);
            if (cached != null) { bind(b, cached); continue; }
            b.loading = true;
            downloads.execute(() -> {
                if (!valid(ticket, pkg) || !b.wanted) {
                    activity.runOnUiThread(() -> {
                        b.loading = false;
                        if (valid(ticket, pkg) && b.wanted) scheduleVisible();
                    });
                    return;
                }
                Bitmap ready = images.load(b.item.image);
                activity.runOnUiThread(() -> {
                    b.loading = false;
                    if (ready == null) b.failedAt = android.os.SystemClock.elapsedRealtime();
                    if (releaseFinishedImages || downloads.isShutdown()) images.trim();
                    if (ready != null && valid(ticket, pkg) && inWindow(b, scroll.getWidth())) bind(b, ready);
                });
            });
        }
    }
    private void releaseDistantImages() {
        if (!active || suspended) return;
        for (Binding b : bindings) {
            if (b.loaded && !inWindow(b, scroll.getWidth())) {
                if (b.reveal != null) b.reveal.cancel();
                b.image.setImageDrawable(b.placeholder); b.image.setImageAlpha(255); b.loaded = false;
            }
        }
    }
    private void bind(Binding b, Bitmap bitmap) {
        boolean first = !b.loaded;
        b.loaded = true;
        float actual = bitmap.getWidth() / (float) bitmap.getHeight();
        if (Math.abs(actual - b.item.aspect) > .02f) {
            size(b, actual);
            scheduleVisible();
        }
        b.image.setImageBitmap(bitmap);
        if (first) {
            if (b.reveal != null) b.reveal.cancel();
            b.image.setImageAlpha(animateRows ? 0 : 255);
            if (animateRows) {
                // Image paint alpha avoids a bitmap snapshot or a temporary per-image hardware layer.
                b.reveal = android.animation.ObjectAnimator.ofInt(b.image, IMAGE_ALPHA, 0, 255);
                b.reveal.setDuration(160); b.reveal.start();
            }
        }
    }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
