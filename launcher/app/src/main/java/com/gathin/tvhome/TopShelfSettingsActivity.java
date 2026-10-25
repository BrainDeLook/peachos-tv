package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Each app owns its source choice; source titles come from the actual Android TV channel provider. */
public final class TopShelfSettingsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff101419);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(64), dp(28), dp(64), dp(28));
        scroll.addView(rows);
        setContentView(scroll);
        TextView title = text(getString(R.string.top_shelf), 30);
        rows.addView(title);
        TextView hint = text(getString(R.string.shelf_settings_hint), 16);
        hint.setTextColor(0xffb8c4d0);
        hint.setPadding(0, dp(12), 0, dp(20));
        rows.addView(hint);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        apps.sort((a, b) -> a.loadLabel(getPackageManager()).toString()
                .compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));
        Set<String> seen = new HashSet<>();
        for (ResolveInfo app : apps) {
            String pkg = app.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            String label = app.loadLabel(getPackageManager()).toString();
            TextView row = text(label + " · " + sourceName(ShelfRepository.source(this, pkg)), 19);
            row.setBackgroundResource(R.drawable.settings_choice_bg);
            row.setFocusable(true);
            row.setClickable(true);
            row.setPadding(dp(18), dp(14), dp(18), dp(14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            rows.addView(row, lp);
            row.setOnClickListener(v -> {
                row.setEnabled(false);
                worker.execute(() -> {
                    ShelfRepository.Result result = ShelfRepository.read(this, pkg, ShelfSelection.AUTO);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        row.setEnabled(true);
                        choose(pkg, label, row, result);
                    });
                });
            });
        }
    }

    private void choose(String pkg, String label, TextView row, ShelfRepository.Result result) {
        List<String> values = new ArrayList<>(), labels = new ArrayList<>();
        values.add(ShelfSelection.OFF); labels.add(getString(R.string.shelf_off));
        values.add(ShelfSelection.AUTO); labels.add(getString(R.string.shelf_auto));
        values.add(ShelfSelection.CONTINUE); labels.add(getString(R.string.shelf_continue));
        if (ShelfSelection.LAMPA.equals(pkg)) {
            values.add(ShelfSelection.HISTORY); labels.add(getString(R.string.shelf_history));
        }
        for (ShelfRepository.Channel channel : result.channels) {
            if (channel.history) continue; // the stable Lampa history choice survives channel recreation
            values.add("channel:" + channel.id);
            labels.add(getString(R.string.shelf_channel, channel.name));
        }
        String current = ShelfRepository.source(this, pkg);
        new AlertDialog.Builder(this).setTitle(label)
                .setSingleChoiceItems(labels.toArray(new String[0]), values.indexOf(current), (dialog, which) -> {
                    String source = values.get(which);
                    ShelfRepository.save(this, pkg, source);
                    row.setText(label + " · " + labels.get(which));
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private String sourceName(String value) {
        if (ShelfSelection.OFF.equals(value)) return getString(R.string.shelf_off);
        if (ShelfSelection.HISTORY.equals(value)) return getString(R.string.shelf_history);
        if (ShelfSelection.CONTINUE.equals(value)) return getString(R.string.shelf_continue);
        if (value.startsWith("channel:")) return getString(R.string.shelf_selected_channel);
        return getString(R.string.shelf_auto);
    }
    private TextView text(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(0xffffffff);
        return text;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
