package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.ContentObserver;
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
    private android.os.CancellationSignal cancellation;
    private int generation;
    private boolean resumed;
    private TvMetadata.Observation observation;
    private boolean permissionRequested;
    private TextView sync;
    private AlertDialog chooser;
    private static final int CHANNEL_PERMISSION = 31;

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
        sync = text(getString(R.string.shelf_sync), 18);
        sync.setBackgroundResource(R.drawable.settings_choice_bg);
        sync.setPadding(dp(18), dp(12), dp(18), dp(12));
        sync.setFocusable(true); sync.setClickable(true);
        sync.setOnClickListener(v -> {
            TvMetadata.Status access = TvMetadata.access(this);
            if (access == TvMetadata.Status.PERMISSION_REQUIRED) {
                if (permissionRequested && !shouldShowRequestPermissionRationale(Backdrops.PERMISSION)) {
                    new AlertDialog.Builder(this).setMessage(R.string.shelf_permission_required)
                            .setPositiveButton(R.string.settings, (dialog, which) -> {
                                Intent info = SystemSettings.appInfo(this, getPackageName());
                                if (info != null) startActivity(info);
                                else android.widget.Toast.makeText(this, R.string.not_available,
                                        android.widget.Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton(android.R.string.cancel, null).show();
                } else {
                    permissionRequested = true;
                    requestPermissions(new String[]{Backdrops.PERMISSION}, CHANNEL_PERMISSION);
                }
                return;
            }
            if (access != TvMetadata.Status.READY) { load(); return; }
            if (observation != null && !observation.complete()) { observation.close(); observation = null; }
            ChannelSetup.refresh(this); load();
        });
        content.addView(sync);
        status = text(getString(R.string.shelf_loading), 15);
        status.setTextColor(0xffb8c4d0); status.setPadding(0, dp(10), 0, dp(12));
        content.addView(status);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); content.addView(rows);
        sync.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        load();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != CHANNEL_PERMISSION) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
            ChannelSetup.refresh(this);
        load();
    }

    private void load() {
        if (!resumed) return;
        cancelLoad();
        TvMetadata.Status access = TvMetadata.access(this);
        if (access != TvMetadata.Status.READY) {
            generation++;
            if (observation != null) { observation.close(); observation = null; }
            rows.removeAllViews();
            status.setText(TvMetadata.message(access));
            sync.setText(access == TvMetadata.Status.PERMISSION_REQUIRED ? R.string.shelf_permission_allow : R.string.shelf_sync);
            return;
        }
        sync.setText(R.string.shelf_sync);
        if (observation == null) observation = TvMetadata.observe(this, access, observer,
                TvMetadata.Table.CHANNELS, TvMetadata.Table.WATCH_NEXT);
        android.os.CancellationSignal signal = new android.os.CancellationSignal();
        cancellation = signal;
        int ticket = ++generation;
        status.setText(R.string.shelf_loading);
        pending = worker.submit(() -> {
            try {
            Map<String, String> labels = new LinkedHashMap<>();
            for (android.content.pm.ResolveInfo app : getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                signal.throwIfCanceled();
                String pkg = app.activityInfo.packageName;
                if (!pkg.equals(getPackageName())) labels.putIfAbsent(pkg, app.loadLabel(getPackageManager()).toString());
            }
            Map<String, ShelfRepository.Result> catalogue = ShelfRepository.catalogue(this, labels.keySet(), signal);
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
                TvMetadata.Status issue = TvMetadata.Status.READY;
                boolean hasSources = false;
                for (ShelfRepository.Result result : catalogue.values()) {
                    hasSources |= result.continueWatching || !result.channels.isEmpty();
                    if (result.unavailable()) issue = result.issue();
                }
                if (issue != TvMetadata.Status.READY) status.setText(hasSources
                        ? getString(R.string.shelf_synced_partial, getString(TvMetadata.message(issue)))
                        : getString(TvMetadata.message(issue)));
                else status.setText(observation != null && observation.complete()
                        ? R.string.shelf_synced : R.string.shelf_live_updates_unavailable);
                scroll.post(() -> { if (resumed) scroll.scrollTo(0, offset); });
            });
            } catch (android.os.OperationCanceledException cancelled) {
                // A newer scan or a paused screen owns the UI now.
            } catch (RuntimeException unavailable) {
                // Package/provider services can restart while the catalogue is being read.
                main.post(() -> {
                    if (resumed && ticket == generation) status.setText(R.string.shelf_temporarily_unavailable);
                });
            }
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
        if (!result.continueWatching && result.channels.isEmpty()) status.setText(result.unavailable()
                ? getString(TvMetadata.message(result.issue())) : getString(R.string.shelf_no_channels));
        chooser = builder.create(); chooser.setOnDismissListener(dialog -> chooser = null); chooser.show();
    }

    private String sourceName(String value, ShelfRepository.Result result) {
        if (ShelfSelection.OFF.equals(value)) return getString(R.string.shelf_off);
        if (ShelfSelection.AUTO.equals(value)) return getString(result.continueWatching || !result.channels.isEmpty()
                ? R.string.shelf_auto : result.unavailable() ? TvMetadata.message(result.issue()) : R.string.shelf_no_sources);
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
    private void cancelLoad() {
        if (pending != null) { pending.cancel(true); pending = null; }
        if (cancellation != null) { cancellation.cancel(); cancellation = null; }
    }
    @Override protected void onPause() {
        resumed = false; generation++; main.removeCallbacks(refresh);
        if (observation != null) { observation.close(); observation = null; }
        cancelLoad();
        if (chooser != null) chooser.dismiss();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (observation != null) { observation.close(); observation = null; }
        cancelLoad(); main.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy();
    }
}
