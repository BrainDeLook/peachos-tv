package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.ContentObserver;
import android.media.tv.TvContract;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** One shared provider scan for the settings screen; only the publishing app's real sources are offered. */
public final class TopShelfSettingsActivity extends Activity {
    private final ExecutorService worker = new java.util.concurrent.ThreadPoolExecutor(0, 1, 2,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(1),
            new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::load;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            main.removeCallbacks(refresh);
            main.postDelayed(refresh, 300);
        }
    };
    private LinearLayout rows;
    private TextView status;
    private ScrollView scroll;
    private Future<?> pending;
    private int generation;
    private boolean resumed;
    private AlertDialog chooser;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff101419);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(64), dp(28), dp(64), dp(28));
        scroll.addView(content);
        setContentView(scroll);
        content.addView(text(getString(R.string.top_shelf), 30));
        TextView hint = text(getString(R.string.shelf_settings_hint), 16);
        hint.setTextColor(0xffb8c4d0);
        hint.setPadding(0, dp(12), 0, dp(12)); content.addView(hint);
        TextView sync = text(getString(R.string.shelf_sync), 18);
        sync.setBackgroundResource(R.drawable.settings_choice_bg);
        sync.setPadding(dp(18), dp(12), dp(18), dp(12));
        sync.setFocusable(true); sync.setClickable(true);
        sync.setOnClickListener(v -> { ChannelSetup.refresh(this); load(); });
        content.addView(sync);
        status = text(getString(R.string.shelf_loading), 15);
        status.setTextColor(0xffb8c4d0); status.setPadding(0, dp(10), 0, dp(12));
        content.addView(status);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); content.addView(rows);
        sync.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        getContentResolver().registerContentObserver(TvContract.Channels.CONTENT_URI, true, observer);
        getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
        load();
    }

    private void load() {
        if (!resumed) return;
        if (pending != null) pending.cancel(true);
        int ticket = ++generation;
        status.setText(R.string.shelf_loading);
        pending = worker.submit(() -> {
            Map<String, String> labels = new LinkedHashMap<>();
            for (android.content.pm.ResolveInfo app : getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                String pkg = app.activityInfo.packageName;
                if (!pkg.equals(getPackageName())) labels.putIfAbsent(pkg, app.loadLabel(getPackageManager()).toString());
            }
            Map<String, ShelfRepository.Result> catalogue = ShelfRepository.catalogue(this, labels.keySet());
            List<String> packages = new ArrayList<>(labels.keySet());
            packages.sort((a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
            main.post(() -> {
                if (!resumed || ticket != generation) return;
                if (chooser != null) chooser.dismiss();
                Object focus = rows.findFocus() == null ? null : rows.findFocus().getTag();
                int offset = scroll.getScrollY(); rows.removeAllViews();
                for (String pkg : packages) {
                    String label = labels.get(pkg);
                    ShelfRepository.Result result = catalogue.get(pkg);
                    TextView row = text(label + " · " + sourceName(ShelfRepository.source(this, pkg), result), 19);
                    row.setTag(pkg); row.setBackgroundResource(R.drawable.settings_choice_bg);
                    row.setFocusable(true); row.setClickable(true);
                    row.setPadding(dp(18), dp(14), dp(18), dp(14));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8);
                    rows.addView(row, lp);
                    row.setOnClickListener(v -> choose(pkg, label, row, result));
                    if (pkg.equals(focus)) row.requestFocus();
                }
                status.setText(R.string.shelf_synced);
                scroll.post(() -> { if (resumed) scroll.scrollTo(0, offset); });
            });
        });
    }

    private void choose(String pkg, String label, TextView row, ShelfRepository.Result result) {
        List<String> values = new ArrayList<>(), labels = new ArrayList<>();
        values.add(ShelfSelection.OFF); labels.add(getString(R.string.shelf_off));
        if (result.continueWatching || !result.channels.isEmpty()) {
            values.add(ShelfSelection.AUTO); labels.add(getString(R.string.shelf_auto));
        }
        if (result.continueWatching) {
            values.add(ShelfSelection.CONTINUE); labels.add(getString(R.string.shelf_continue));
        }
        for (ShelfRepository.Channel channel : result.channels) {
            if (channel.history) {
                if (!values.contains(ShelfSelection.HISTORY)) {
                    values.add(ShelfSelection.HISTORY); labels.add(getString(R.string.shelf_history));
                }
            } else {
                values.add(channel.source()); labels.add(channel.name.isEmpty()
                        ? getString(R.string.shelf_unnamed_channel, channel.id) : channel.name);
            }
        }
        String current = ShelfRepository.source(this, pkg);
        int checked = values.indexOf(current);
        if (checked < 0) for (ShelfRepository.Channel channel : result.channels) {
            if (ShelfSelection.matchesChannel(current, channel.id, channel.providerId)) checked = values.indexOf(channel.source());
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(label)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    String source = values.get(which); ShelfRepository.save(this, pkg, source);
                    row.setText(label + " · " + sourceName(source, result)); dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null);
        if (!result.continueWatching && result.channels.isEmpty()) status.setText(result.unavailable
                ? getString(R.string.shelf_provider_unavailable) : getString(R.string.shelf_no_channels));
        chooser = builder.create(); chooser.setOnDismissListener(dialog -> chooser = null); chooser.show();
    }

    private String sourceName(String value, ShelfRepository.Result result) {
        if (ShelfSelection.OFF.equals(value)) return getString(R.string.shelf_off);
        if (ShelfSelection.AUTO.equals(value)) return getString(result.continueWatching || !result.channels.isEmpty()
                ? R.string.shelf_auto : result.unavailable ? R.string.shelf_provider_unavailable : R.string.shelf_no_sources);
        if (ShelfSelection.CONTINUE.equals(value) && result.continueWatching) return getString(R.string.shelf_continue);
        for (ShelfRepository.Channel channel : result.channels) {
            if (ShelfSelection.HISTORY.equals(value) && channel.history) return getString(R.string.shelf_history);
            if (ShelfSelection.matchesChannel(value, channel.id, channel.providerId)) return channel.name.isEmpty()
                    ? getString(R.string.shelf_unnamed_channel, channel.id) : channel.name;
        }
        return getString(R.string.shelf_source_unavailable);
    }
    private TextView text(String value, int size) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(0xffffffff); return text;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onPause() {
        resumed = false; generation++; main.removeCallbacks(refresh);
        getContentResolver().unregisterContentObserver(observer);
        if (pending != null) pending.cancel(true);
        if (chooser != null) chooser.dismiss();
        super.onPause();
    }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy(); }
}
