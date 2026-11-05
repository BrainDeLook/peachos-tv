package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** One vendor-aware source catalogue for Home and the temporary overlay. No persistent callbacks. */
final class InputRepository {
    enum Kind { INPUT, COOCAA, PANEL, LIVE_TV }
    static final class Entry {
        final Kind kind;
        final String label, id;
        final int icon, state;
        final Intent intent;
        final CoocaaSources.Source coocaa;
        Entry(Kind kind, String label, String id, int icon, int state, Intent intent, CoocaaSources.Source coocaa) {
            this.kind = kind; this.label = label; this.id = id; this.icon = icon; this.state = state;
            this.intent = intent; this.coocaa = coocaa;
        }
    }
    static final class Snapshot {
        final List<Entry> entries;
        final boolean temporarilyUnavailable;
        Snapshot(List<Entry> entries, boolean unavailable) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            temporarilyUnavailable = unavailable;
        }
        boolean available() { return !entries.isEmpty(); }
    }
    /** External TV input values are normalized once, before sorting and policy. */
    static final class Input {
        final String id, parent, label, custom;
        final boolean passthrough, system, hardware, analog;
        final int type, state;
        Input(String id, String parent, String label, String custom, boolean passthrough, boolean system, int type, int state) {
            this.id = id; this.parent = parent; this.label = label; this.custom = custom;
            this.passthrough = passthrough; this.system = system; this.type = type; this.state = state;
            hardware = id.matches(".*/HW\\d+$"); analog = id.toLowerCase(Locale.ROOT).contains("analog");
        }
    }

    private final Context context;
    private final Config config;
    InputRepository(Context context, Config config) { this.context = context; this.config = config; }

    Snapshot read() {
        List<Input> inputs = new ArrayList<>();
        boolean unavailable = false;
        try {
            TvInputManager manager = context.getSystemService(TvInputManager.class);
            if (manager != null) {
                for (TvInputInfo info : manager.getTvInputList()) {
                    try {
                        if (info.isHidden(context)) continue;
                        CharSequence label = info.loadLabel(context), custom = info.loadCustomLabel(context);
                        int state = -1;
                        if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI) {
                            try { state = manager.getInputState(info.getId()); }
                            catch (RuntimeException failure) { unavailable = true; }
                        }
                        inputs.add(new Input(info.getId(), info.getParentId(),
                                label == null ? info.getId() : label.toString(), custom == null ? "" : custom.toString(),
                                info.isPassthroughInput(), (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                                info.getType(), state));
                    } catch (RuntimeException failure) {
                        unavailable = true;
                        Log.w("PeachInputs", "An input is unavailable", failure);
                    }
                }
            }
        } catch (RuntimeException failure) {
            unavailable = true;
            Log.w("PeachInputs", "TV input catalogue unavailable", failure);
        }
        Set<String> omitted = new HashSet<>();
        for (Input input : inputs) if (Integer.valueOf(-1).equals(InputLaunch.mappedSource(context, input.id))) omitted.add(input.id);
        List<Entry> rows = arrange(inputs, Build.MANUFACTURER, omitted);
        if (rows.isEmpty() && config.coocaaSources) {
            for (CoocaaSources.Source source : CoocaaSources.list(context)) {
                int icon = "HDMI".equals(source.name) ? R.drawable.ic_input
                        : "VOD".equals(source.name) ? R.drawable.ic_livetv : R.drawable.ic_av;
                rows.add(new Entry(Kind.COOCAA, source.label, "", icon, -1, null, source));
            }
        }
        if (rows.isEmpty() && !config.sourcePanelService.isEmpty())
            rows.add(new Entry(Kind.PANEL, context.getString(R.string.inputs), "", R.drawable.ic_input, -1, null, null));
        try {
            Intent live = liveTvIntent(context, config.liveTvPackage, config.liveTvAction);
            String tuner = preferredTuner(inputs, config.liveTvInputId);
            if (live != null && tuner != null) {
                boolean specified = !config.liveTvInputId.isEmpty();
                Intent tuned = new Intent(Intent.ACTION_VIEW).setDataAndType(specified ? TvContract.buildChannelsUriForInput(tuner)
                        : TvContract.buildChannelUriForPassthroughInput(tuner),
                        specified ? TvContract.Channels.CONTENT_TYPE : TvContract.Channels.CONTENT_ITEM_TYPE).setPackage(config.liveTvPackage);
                if (context.getPackageManager().resolveActivity(tuned, 0) != null) live = tuned;
            }
            if (live != null) rows.add(new Entry(Kind.LIVE_TV, context.getString(R.string.live_tv), "", R.drawable.ic_livetv, -1, live, null));
        } catch (RuntimeException failure) {
            unavailable = true;
            Log.w("PeachInputs", "Live TV entry unavailable", failure);
        }
        return new Snapshot(rows, unavailable);
    }

    static Intent liveTvIntent(Context context, String pkg, String action) {
        if (pkg.isEmpty()) return null;
        Intent launch = context.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch != null || action.isEmpty()) return launch;
        Intent explicit = new Intent(action).setPackage(pkg);
        return context.getPackageManager().resolveActivity(explicit, 0) == null ? null : explicit;
    }

    static String preferredTuner(List<Input> inputs, String specified) {
        Input best = null;
        for (Input input : inputs) {
            if (input.type != TvInputInfo.TYPE_TUNER || !input.system || !specified.isEmpty() && !specified.equals(input.id)) continue;
            if (best == null || input.hardware && !best.hardware
                    || input.hardware == best.hardware && (best.analog && !input.analog
                    || input.analog == best.analog && input.id.compareTo(best.id) < 0)) best = input;
        }
        return best == null ? null : best.id;
    }

    static List<Entry> arrange(List<Input> inputs, String manufacturer, Set<String> omitted) {
        List<Input> ordered = new ArrayList<>(inputs);
        ordered.sort(Comparator.comparingInt((Input input) -> input.type == TvInputInfo.TYPE_HDMI ? 0 : 1)
                .thenComparing(input -> input.label, String.CASE_INSENSITIVE_ORDER));
        Set<String> ids = new HashSet<>();
        for (Input input : ordered) ids.add(input.id);
        Map<String, List<Input>> children = new HashMap<>();
        int ports = 0, connected = 0;
        for (Input input : ordered) {
            if (!input.passthrough || omitted.contains(input.id)) continue;
            if (input.parent != null && ids.contains(input.parent)) children.computeIfAbsent(input.parent, ignored -> new ArrayList<>()).add(input);
            else if (input.type == TvInputInfo.TYPE_HDMI) { ports++; if (input.state == TvInputManager.INPUT_STATE_CONNECTED) connected++; }
        }
        boolean bogus = ports > 1 && ports == connected;
        List<Entry> rows = new ArrayList<>();
        for (Input input : ordered) {
            if (!input.passthrough || omitted.contains(input.id) || input.parent != null && ids.contains(input.parent)) continue;
            boolean hdmi = input.type == TvInputInfo.TYPE_HDMI;
            String label = hdmi && !input.label.toUpperCase(Locale.ROOT).contains("HDMI") ? "HDMI " + input.label : input.label;
            boolean named = !input.custom.isEmpty() && !input.custom.equals(label);
            List<Input> devices = children.get(input.id);
            if (devices != null) {
                for (Input device : devices) {
                    String name = !device.custom.isEmpty() ? device.custom : named ? input.custom : device.label;
                    int state = bogus ? -1 : device.state;
                    if ("TCL".equalsIgnoreCase(manufacturer) && devices.size() == 1
                            && state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY && input.state == TvInputManager.INPUT_STATE_CONNECTED)
                        state = TvInputManager.INPUT_STATE_CONNECTED;
                    rows.add(new Entry(Kind.INPUT, name.isEmpty() ? label : name + " · " + label, device.id, R.drawable.ic_input, state, null, null));
                }
            } else rows.add(new Entry(Kind.INPUT, named ? input.custom + " · " + label : label, input.id,
                    hdmi ? R.drawable.ic_input : R.drawable.ic_av, !hdmi || bogus ? -1 : input.state, null, null));
        }
        return rows;
    }

    void populate(Snapshot snapshot, LinearLayout rows, Consumer<Runnable> activate, Runnable sourceSound) {
        boolean nonHdmi = false;
        for (Entry entry : snapshot.entries) {
            if (rows.getChildCount() > 0 && (entry.kind == Kind.LIVE_TV || entry.icon == R.drawable.ic_av && !nonHdmi)) {
                View line = new View(context);
                line.setBackgroundColor(0x1fffffff);
                float density = context.getResources().getDisplayMetrics().density;
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, (int) density);
                layout.setMargins((int)(12 * density), (int)(6 * density), (int)(12 * density), (int)(6 * density));
                rows.addView(line, layout);
            }
            if (entry.icon == R.drawable.ic_av) nonHdmi = true;
            View row = LayoutInflater.from(context).inflate(R.layout.item_input, rows, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(entry.icon);
            ((TextView) row.findViewById(R.id.title)).setText(entry.label);
            TextView status = row.findViewById(R.id.status);
            if (entry.state == TvInputManager.INPUT_STATE_CONNECTED) status.setText(R.string.connected);
            else if (entry.state == TvInputManager.INPUT_STATE_DISCONNECTED) status.setText(R.string.not_connected);
            else status.setVisibility(View.GONE);
            row.findViewById(R.id.dot).setVisibility(entry.state == TvInputManager.INPUT_STATE_CONNECTED ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> activate.accept(() -> {
                if (entry.kind != Kind.PANEL) sourceSound.run();
                open(entry);
            }));
            rows.addView(row);
        }
    }

    void open(Entry entry) {
        switch (entry.kind) {
            case INPUT:
                InputLaunch.openInput(context, entry.id, config.inputAction, config.inputUriScheme, config.liveTvPackage);
                return;
            case LIVE_TV:
                if (SystemSettings.start(context, entry.intent)) return;
                break;
            case COOCAA:
                if (CoocaaSources.open(context, entry.coocaa)) return;
                break;
            case PANEL:
                Intent panel = new Intent(config.sourcePanelAction);
                ComponentName component = ComponentName.unflattenFromString(config.sourcePanelService);
                if (component != null) panel.setComponent(component);
                int equal = config.sourcePanelExtra.indexOf('=');
                if (equal > 0) {
                    try { panel.putExtra(config.sourcePanelExtra.substring(0, equal).trim(), Integer.parseInt(config.sourcePanelExtra.substring(equal + 1).trim())); }
                    catch (NumberFormatException invalid) { Log.w("PeachInputs", "Invalid source-panel extra"); }
                }
                if (context.getPackageManager().resolveActivity(panel, 0) != null) {
                    if (SystemSettings.start(context, panel)) return;
                } else {
                    try { if (context.startService(panel) != null) return; }
                    catch (SecurityException | IllegalStateException | IllegalArgumentException unavailable) { Log.w("PeachInputs", "Source panel unavailable", unavailable); }
                }
        }
        Toast.makeText(context, R.string.not_available, Toast.LENGTH_SHORT).show();
    }
}
