package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Device-only fixture: real Android JSON, AtomicFile, preferences, Handler and configuration parser. */
public final class BootstrapTestActivity extends Activity {
    private int checks;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                if (getIntent().getBooleanExtra("refresh", false)) runRefreshCheck();
                else runChecks();
                Log.i("PeachBootstrapTest", "PASS " + checks + " checks");
            } catch (Throwable failure) {
                Log.e("PeachBootstrapTest", "FAIL after " + checks + " checks", failure);
            }
            main.post(this::finish);
        }, "BootstrapTest").start();
    }

    private static void writeText(java.nio.file.Path path, String text) throws Exception { Files.write(path, text.getBytes(StandardCharsets.UTF_8)); }
    private static String readText(java.nio.file.Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
        checks++;
    }

    private static void resetSnapshot() throws Exception {
        Field current = ThemeSnapshot.class.getDeclaredField("current"); current.setAccessible(true); current.set(null, null);
        Field sequence = ThemeSnapshot.class.getDeclaredField("publishedSequence"); sequence.setAccessible(true); sequence.setLong(null, 0);
    }

    private <T> T onMain(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call); main.post(task); return task.get(10, TimeUnit.SECONDS);
    }

    private void await(CountDownLatch latch, String stage) throws Exception {
        check(latch.await(10, TimeUnit.SECONDS), stage);
    }

    private static Object field(Class<?> type, Object target, String name) throws Exception {
        Field field=type.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    private MainActivity home() throws Exception {
        return (MainActivity)((java.lang.ref.WeakReference<?>)field(MainActivity.class,null,"foregroundHome")).get();
    }

    private void runRefreshCheck() throws Exception {
        resetSnapshot();
        File theme = new File(getExternalFilesDir(null),Config.FILE_NAME);
        writeText(theme.toPath(), "{\"columns\":6,\"background_color\":\"#223344\"}");
        Config initial=Config.load(this);
        check(initial.themeVerified,"seed real Home cache");
        writeText(theme.toPath(), "{\"columns\":6,\"background_color\":\"#334455\"}");
        // Hold the real external-read boundary until Home is drawn and a non-first app is selected.
        MainActivity first;
        String selected;
        synchronized(Config.class) {
            onMain(() -> {startActivity(new android.content.Intent(this,MainActivity.class));return null;});
            long deadline=SystemClock.uptimeMillis()+10000;
            do {
                Thread.sleep(100);
                first=onMain(() -> home());
            } while ((first==null || !onMain(() -> (boolean)field(MainActivity.class,home(),"catalogueDrawReported")))
                    && SystemClock.uptimeMillis()<deadline);
            check(first!=null && onMain(() -> (boolean)field(MainActivity.class,home(),"catalogueDrawReported")),"cached Home drawn while verification blocked");
            onMain(() -> {
                MainActivity activity=home();
                activity.dispatchKeyEvent(new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                activity.dispatchKeyEvent(new android.view.KeyEvent(1,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                return null;
            });
            Thread.sleep(250);
            selected=onMain(() -> (String)field(MainActivity.class,null,"lastFocusedKey"));
            check(selected!=null,"selected app captured before refresh");
        }
        long deadline=SystemClock.uptimeMillis()+10000;
        MainActivity previous=first;
        while(SystemClock.uptimeMillis()<deadline) {
            if(onMain(() -> home()!=null && home()!=previous
                    && (boolean)field(MainActivity.class,home(),"catalogueDrawReported")
                    && home().hasWindowFocus())) break;
            Thread.sleep(100);
        }
        check(onMain(() -> home()!=previous),"actual theme change recreates Home");
        check(onMain(() -> ((Config)field(MainActivity.class,home(),"homeConfig")).backgroundColor)==android.graphics.Color.parseColor("#334455"),"fresh theme applied");
        check(selected.equals(onMain(() -> (String)field(MainActivity.class,null,"lastFocusedKey"))),"selected app survives theme replacement");
        MainActivity refreshed=onMain(() -> home());
        Thread.sleep(1000);
        check(onMain(() -> home()==refreshed),"unchanged verification does not recreate again");
    }

    private void runChecks() throws Exception {
        Isolated context = new Isolated(this);
        File file = new File(context.getFilesDir(), ThemeSnapshot.FILE_NAME);
        resetSnapshot(); Files.deleteIfExists(file.toPath());
        check(Config.loadCached(context) == null, "first install has no cache");
        Files.write(file.toPath(), "broken{".getBytes(StandardCharsets.UTF_8));
        check(Config.loadCached(context) == null, "corrupt cache falls back");
        Files.write(file.toPath(), new byte[256 * 1024 + 1]);
        check(Config.loadCached(context) == null, "oversized cache rejected");

        writeText(new File(context.external, Config.FILE_NAME).toPath(),
                "{\"columns\":5,\"background_color\":\"#223344\",\"wallpaper_rotation_minutes\":2}");
        Files.write(new File(context.external, Config.WALLPAPER_NAME).toPath(), new byte[]{1,2,3});
        File images = new File(context.external, "wallpapers"); images.mkdirs();
        Files.write(new File(images,"example.jpg").toPath(), new byte[]{1,2});
        Config full = Config.load(context);
        check(full.themeVerified && file.isFile(), "full load persists snapshot");
        check(full.columns == 5 && full.wallpapers.size() == 1, "external settings and collection read");
        String valid = readText(file.toPath());
        int calls = context.externalCalls;
        context.rejectExternal = true;
        resetSnapshot();
        Config cached = Config.loadCached(context);
        check(cached != null && !cached.themeVerified && cached.columns == 5, "disk cache restores theme");
        check(context.externalCalls == calls, "cached config makes no external directory request");
        check(cached.themeSnapshot.key.equals(full.themeSnapshot.key), "serialized cache has same semantic key");
        context.getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit().putInt("columns", 7).commit();
        check(Config.loadCached(context).columns == 7, "local preferences still override cache");
        check(context.externalCalls == calls, "local preferences do not scan external storage");

        for (String changed : new String[]{
                new JSONObject(valid).put("version", 900).toString(),
                new JSONObject(valid).put("owner", "other.package").toString(),
                new JSONObject(valid).put("uid", Process.myUid()+1).toString(),
                new JSONObject(valid).put("directory", "relative").toString()}) {
            resetSnapshot(); writeText(file.toPath(), changed);
            check(Config.loadCached(context) == null, "incompatible identity/schema rejected");
        }
        JSONObject bad = new JSONObject(valid);
        bad.getJSONArray("images").getJSONObject(0).put("name", "../escape.jpg");
        resetSnapshot(); writeText(file.toPath(), bad.toString());
        check(Config.loadCached(context) == null, "image path traversal rejected");
        bad = new JSONObject(valid); bad.getJSONArray("images").getJSONObject(0).put("length", -1);
        resetSnapshot(); writeText(file.toPath(), bad.toString());
        check(Config.loadCached(context) == null, "invalid metadata rejected");
        resetSnapshot(); writeText(file.toPath(), new JSONObject(valid).toString(3));
        check(Config.loadCached(context).themeSnapshot.key.equals(full.themeSnapshot.key), "formatting does not change theme");

        // Cached paths stay available even while their external files are inaccessible/deleted.
        Files.delete(new File(images,"example.jpg").toPath());
        check(Config.loadCached(context).wallpapers.size() == 1, "cache path does not stat images");
        context.rejectExternal = false;
        Config changed = Config.load(context);
        check(changed.themeVerified && !changed.themeSnapshot.key.equals(full.themeSnapshot.key), "removed image detected in background");
        check(!ThemeSnapshot.publish(file, full.themeSnapshot, -1), "older scan cannot overwrite new snapshot");
        check(ThemeSnapshot.isCurrent(changed.themeSnapshot), "newer snapshot retained");

        writeText(new File(context.external, Config.FILE_NAME).toPath(), "malformed{");
        Config malformed = Config.load(context);
        check(!malformed.themeVerified && malformed.columns == changed.columns, "malformed external theme retains fallback choices");
        check(ThemeSnapshot.isCurrent(changed.themeSnapshot), "malformed external theme cannot poison snapshot");
        Files.delete(new File(context.external, Config.FILE_NAME).toPath());
        Config removed = Config.load(context);
        check(removed.themeVerified && removed.themeSnapshot.overlay == null, "explicit theme removal accepted");

        // One external scan serves simultaneous Home/helper subscriptions; cancelling an owner removes its callback.
        context.entered = new CountDownLatch(1); context.release = new CountDownLatch(1);
        int before = context.externalCalls;
        AtomicInteger callbacks = new AtomicInteger(); CountDownLatch done = new CountDownLatch(1);
        ConfigRefresh.Request first = onMain(() -> ConfigRefresh.request(context, Long.MAX_VALUE, result -> callbacks.addAndGet(100)));
        await(context.entered,"refresh entered external boundary");
        onMain(() -> { ConfigRefresh.request(context, Long.MAX_VALUE, result -> {callbacks.incrementAndGet();done.countDown();}); first.cancel(); return null; });
        context.release.countDown(); await(done,"coalesced refresh completed");
        check(callbacks.get() == 1 && context.externalCalls == before + 1, "cancel one subscriber without duplicate scan");
        CountDownLatch reused = new CountDownLatch(1);
        onMain(() -> ConfigRefresh.request(context, 0, result -> reused.countDown()));
        await(reused,"late startup subscriber reuses completed result");
        check(context.externalCalls == before + 1, "reuse does not touch external storage");

        context.entered = new CountDownLatch(1); context.release = new CountDownLatch(1);
        ConfigRefresh.Request cancelled = onMain(() -> ConfigRefresh.request(context, Long.MAX_VALUE, result -> callbacks.addAndGet(1000)));
        await(context.entered,"cancellable scan entered");
        onMain(() -> {cancelled.cancel(); return null;});
        check(!cancelled.active(), "destroyed owner has no callback");
        context.release.countDown();
        CountDownLatch afterCancel = new CountDownLatch(1);
        onMain(() -> ConfigRefresh.request(context, Long.MAX_VALUE, result -> afterCancel.countDown()));
        await(afterCancel,"refresh after cancellation succeeds");
        onMain(() -> null);
        check(callbacks.get() == 1, "late cancelled result never delivered");
        Thread.sleep(2500);
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().equals("PeachThemeRefresh")), "idle refresh worker exits");
    }

    private static final class Isolated extends ContextWrapper {
        final File internal, external;
        final String prefix = "bootstrap_" + SystemClock.uptimeMillis() + "_";
        volatile boolean rejectExternal;
        volatile int externalCalls;
        volatile CountDownLatch entered, release;
        Isolated(Context base) {
            super(base);
            internal = new File(base.getFilesDir(),prefix); internal.mkdirs();
            external = new File(internal,"external"); external.mkdirs();
        }
        @Override public File getFilesDir() {return internal;}
        @Override public Context getApplicationContext() {return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode) {return super.getSharedPreferences(prefix+name,mode);}
        @Override public File getExternalFilesDir(String type) {
            externalCalls++;
            if (rejectExternal) throw new AssertionError("cached path touched external storage");
            CountDownLatch ready=entered, gate=release;
            if (ready != null) {
                ready.countDown();
                boolean released=false;
                while (!released) {
                    try {released=gate.await(10,TimeUnit.SECONDS);}
                    catch (InterruptedException expectedCancellation) { /* simulate non-interruptible storage RPC */ }
                    if (!released && gate.getCount()!=0) throw new AssertionError("storage gate timeout");
                }
            }
            return external;
        }
    }
}

