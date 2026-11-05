package com.gathin.tvhome;

import android.view.KeyEvent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import java.util.function.IntPredicate;

/** One main-thread owner for a physical gesture, even when both legacy helpers receive it. */
final class RemoteEventCoordinator {
    static final int HOME = 0, INPUT = 1;
    interface Client {
        boolean filtering();
        boolean handle(KeyEvent event);
        void cancelGestures();
    }
    private static final Client[] clients = new Client[2];
    private static final Router router = new Router();
    private static Lifecycle lifecycle;

    static void connect(Context context, int slot, Client client) {
        clients[slot] = client;
        router.changed(slot);
        if (lifecycle == null) {
            lifecycle = new Lifecycle(context.getApplicationContext());
            lifecycle.start();
        }
    }

    static void disconnect(int slot, Client client) {
        if (clients[slot] != client) return;
        clients[slot] = null;
        router.changed(slot);
        if (clients[HOME] == null && clients[INPUT] == null && lifecycle != null) {
            lifecycle.stop(); lifecycle = null;
        }
    }

    static boolean dispatch(KeyEvent event) {
        Event stamp = new Event(event.getDeviceId(), event.getSource(), event.getKeyCode(), event.getScanCode(),
                event.getAction(), event.getRepeatCount(), event.getDownTime(), event.getEventTime());
        return router.dispatch(stamp, slot -> clients[slot] != null && clients[slot].filtering(),
                slot -> clients[slot].handle(event));
    }

    static void cancelGestures() {
        router.cancelGestures();
        SettingsKeyBinding.abort();
        VolumeKeys.stop();
        for (Client client : clients) if (client != null) client.cancelGestures();
    }

    /** Primitive copies only: Android owns the original KeyEvent, which is never retained. */
    static final class Event {
        final int device, source, code, scan, action, repeat;
        final long down, time;
        Event(int device, int source, int code, int scan, int action, int repeat, long down, long time) {
            this.device = device; this.source = source; this.code = code; this.scan = scan;
            this.action = action; this.repeat = repeat; this.down = down; this.time = time;
        }
        boolean sameEvent(Event other) {
            return sameGesture(other) && action == other.action && repeat == other.repeat && time == other.time;
        }
        boolean sameGesture(Event other) {
            return device == other.device && source == other.source && code == other.code && scan == other.scan && down == other.down;
        }
    }

    static final class Router {
        static final long MAX_GESTURE_MS = 30_000;
        private final Event[] delivered = new Event[32], gestures = new Event[16];
        private final boolean[] results = new boolean[32];
        private final int[] owners = new int[16], ownerGenerations = new int[16], generations = new int[2];
        private int nextEvent, nextGesture;

        void changed(int slot) { generations[slot]++; }
        void cancelGestures() { for (int slot = HOME; slot <= INPUT; slot++) changed(slot); }

        boolean dispatch(Event event, IntPredicate available, IntPredicate deliver) {
            for (int i = 0; i < delivered.length; i++)
                if (delivered[i] != null && delivered[i].sameEvent(event)) return results[i];
            int gesture = -1;
            for (int i = 0; i < gestures.length; i++) {
                Event previous = gestures[i];
                if (previous != null && (event.time - previous.down > MAX_GESTURE_MS || event.time < previous.down)) gestures[i] = null;
                else if (previous != null && previous.sameGesture(event)) gesture = i;
            }
            boolean consumed = false;
            if (gesture >= 0) {
                int owner = owners[gesture];
                // A replacement service never inherits half of its predecessor's held key.
                consumed = owner >= 0;
                if (consumed && ownerGenerations[gesture] == generations[owner] && available.test(owner)) deliver.test(owner);
                // Compatible Home observes the native HOME pair without consuming it. Its UP
                // schedules a fallback when an OEM does not emit the expected window event.
                if (!consumed && event.code == KeyEvent.KEYCODE_HOME
                        && ownerGenerations[gesture] == generations[HOME] && available.test(HOME)) deliver.test(HOME);
                if (event.action == KeyEvent.ACTION_UP) gestures[gesture] = null;
            } else if (event.action == KeyEvent.ACTION_DOWN && event.repeat == 0) {
                int owner = -1;
                for (int slot = HOME; slot <= INPUT; slot++)
                    if (available.test(slot) && deliver.test(slot)) { owner = slot; consumed = true; break; }
                int at = nextGesture; nextGesture = (nextGesture + 1) % gestures.length;
                gestures[at] = event; owners[at] = owner;
                ownerGenerations[at] = generations[owner < 0 ? HOME : owner];
            }
            // Orphan UP/repeat passes through; it must not launch an action without its DOWN.
            int at = nextEvent; nextEvent = (nextEvent + 1) % delivered.length;
            delivered[at] = event; results[at] = consumed;
            return consumed;
        }
    }

    /** Event-driven cleanup only; no foreground polling or wakeups while the remote is idle. */
    private static final class Lifecycle extends BroadcastReceiver implements InputManager.InputDeviceListener {
        private final Context context;
        private final InputManager input;
        private boolean receiverRegistered, inputRegistered;
        Lifecycle(Context context) { this.context = context; input = context.getSystemService(InputManager.class); }
        void start() {
            try {
                context.registerReceiver(this, new IntentFilter(Intent.ACTION_SCREEN_OFF));
                receiverRegistered = true;
            } catch (RuntimeException unavailable) { android.util.Log.w("PeachRemote", "Screen state updates unavailable", unavailable); }
            if (input != null) {
                try { input.registerInputDeviceListener(this, null); inputRegistered = true; }
                catch (RuntimeException unavailable) { android.util.Log.w("PeachRemote", "Input device updates unavailable", unavailable); }
            }
        }
        void stop() {
            if (receiverRegistered) context.unregisterReceiver(this);
            if (inputRegistered) input.unregisterInputDeviceListener(this);
        }
        @Override public void onReceive(Context ignored, Intent intent) { cancelGestures(); }
        @Override public void onInputDeviceRemoved(int device) { cancelGestures(); }
        @Override public void onInputDeviceAdded(int device) { }
        @Override public void onInputDeviceChanged(int device) { }
    }
}
