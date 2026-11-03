package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Device-only layout fixture. No Wi-Fi radio, permission, account or connection is modified. */
public final class WifiUiPreviewActivity extends Activity implements ControlCenter.Actions {
    private ControlCenter center;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        findViewById(R.id.root).setBackgroundColor(0xff202b3d);
        findViewById(R.id.topButtons).post(() -> {
            center = new ControlCenter(this, this, findViewById(android.R.id.content),
                    findViewById(R.id.root), findViewById(R.id.btnSettings),
                    findViewById(R.id.topButtons), findViewById(R.id.compactClock),
                    getWindow(), null, false, true, false, "", java.util.Collections.emptyMap());
            center.show();
            findViewById(R.id.root).postDelayed(this::networks, 1500);
        });
    }

    private void networks() {
        try {
            // Suppress the real scanner at the boundary where the page would start it.
            set("networkEthernet", false);
            set("networkTransport", "Wi-Fi");
            set("networkOnline", true);
            set("closing", true);
            Field network = ControlCenter.class.getDeclaredField("networkTab");
            network.setAccessible(true); ((android.view.View) network.get(center)).requestFocus();
            set("closing", false);
            List<WifiAccessPoints.Observation> scan = new ArrayList<>();
            for (int i = 0; i < 32; i++) scan.add(new WifiAccessPoints.Observation(
                    i==0 ? "PeachOS_Very_Long_Network_Name_01" : String.format(java.util.Locale.ROOT, "PeachOS Test %02d", i + 1),
                    "02:00:00:00:00:" + i, i % 4 == 0 ? "[ESS]" : "[WPA2-PSK-CCMP][ESS]", -50 - i));
            set("pendingWifi", new WifiScanner.Snapshot(WifiScanner.State.READY,
                    WifiAccessPoints.build(scan, "PeachOS_Very_Long_Network_Name_01", "02:00:00:00:00:0"), "PeachOS_Very_Long_Network_Name_01"));
            Method render = ControlCenter.class.getDeclaredMethod("renderWifiNetworks");
            render.setAccessible(true); render.invoke(center);
            Field selected = ControlCenter.class.getDeclaredField("selectedTab");
            selected.setAccessible(true); ((android.view.View) selected.get(center)).requestFocus();
            android.util.Log.i("WifiFixture", "Synthetic AP page ready; real scanner disabled");
            if (getIntent().getBooleanExtra("selftest", false))
                findViewById(R.id.root).postDelayed(() -> new Thread(this::checkCarousel, "WifiCarouselTest").start(), 500);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private int checks;
    private void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks++;
    }
    private Object field(Object target, String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private <T> T ui(java.util.concurrent.Callable<T> call) throws Exception {
        java.util.concurrent.FutureTask<T> task=new java.util.concurrent.FutureTask<>(call);
        runOnUiThread(task); return task.get(5,java.util.concurrent.TimeUnit.SECONDS);
    }
    private void key(int code) throws Exception {
        ui(() -> {dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code)); dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code)); return null;});
    }
    private void checkCarousel() {
        try {
            Object page=ui(() -> field(center,"currentPage"));
            WifiCarousel carousel=ui(() -> (WifiCarousel)field(page,"wifiCarousel"));
            LinearLayout rows=ui(() -> (LinearLayout)field(page,"wifiRows"));
            android.view.View settings=ui(() -> (android.view.View)field(page,"wifiSettings"));
            android.view.View refresh=ui(() -> (android.view.View)field(page,"wifiAction"));
            int pitch=ui(() -> rows.getChildAt(1).getTop()-rows.getChildAt(0).getTop());
            int capacity=ui(() -> (carousel.getHeight()-rows.getPaddingTop()-rows.getPaddingBottom())/pitch);
            check(capacity>=2,"multiple full rows fit");
            check(ui(() -> rows.getChildCount())==32,"bounded AP pool");
            check(ui(() -> {
                android.graphics.Rect visible = new android.graphics.Rect();
                for (int i=0;i<rows.getChildCount();i++) {
                    android.view.View row=rows.getChildAt(i);
                    if(row.getAlpha()==0f) continue;
                    android.graphics.RectF surface = new android.graphics.RectF(
                            (android.graphics.RectF)field(row.getBackground(),"bounds"));
                    android.graphics.Matrix transform = new android.graphics.Matrix();
                    row.transformMatrixToGlobal(transform); transform.mapRect(surface);
                    android.graphics.RectF exposed = new android.graphics.RectF(surface);
                    if(row.getGlobalVisibleRect(visible) && exposed.intersect(new android.graphics.RectF(visible))
                            && exposed.height()+1f<surface.height()) {
                        android.util.Log.e("WifiFixture","partial row="+i+" height="+visible.height()+"/"+row.getHeight()
                            +" viewport="+carousel.getHeight()+" scroll="+carousel.getScrollY()
                            +" rowTop="+row.getTop()+" rowPadding="+rows.getPaddingTop()+" contentTop="+rows.getTop());
                        return false;
                    }
                }
                return true;
            }),"no partial AP material at rest");
            check(ui(() -> {
                int[] a=new int[2],b=new int[2];refresh.getLocationOnScreen(a);carousel.getLocationOnScreen(b);
                return a[1]+refresh.getHeight()<=b[1];
            }),"actions above carousel");
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            check(ui(() -> rows.getChildAt(0).hasFocus()),"enter first AP from network tab");
            Thread.sleep(300);
            check(ui(() -> focusedSurfaceFits(rows.getChildAt(0),carousel)),"focused first AP fits viewport including zoom");
            check(ui(() -> {
                android.graphics.drawable.Drawable signal=((android.widget.ImageView)((android.view.ViewGroup)rows.getChildAt(0)).getChildAt(0)).getDrawable();
                android.graphics.Rect original=new android.graphics.Rect(signal.getBounds());
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(100,100,android.graphics.Bitmap.Config.ARGB_8888);
                signal.setBounds(0,0,100,100); signal.draw(new android.graphics.Canvas(bitmap));
                int color=bitmap.getPixel(50,83); bitmap.recycle(); signal.setBounds(original);
                return android.graphics.Color.alpha(color)>200 && android.graphics.Color.red(color)<50
                        && android.graphics.Color.blue(color)<50;
            }),"selected Wi-Fi glyph renders dark on white focus");
            WifiNameView name=ui(() -> (WifiNameView)((android.view.ViewGroup)rows.getChildAt(0)).getChildAt(1));
            Thread.sleep(1100);
            check(ui(name::getScrollX)>0,"focused long SSID scrolls");
            check(ui(() -> name.isHorizontalFadingEdgeEnabled() && name.getLeftFadingEdgeStrength()>0f
                    && name.getRightFadingEdgeStrength()>0f),"moving SSID fades at both hidden edges");
            android.animation.ValueAnimator textMotion=ui(() -> (android.animation.ValueAnimator)field(name,"motion"));
            long remaining=ui(() -> textMotion.getDuration()-textMotion.getCurrentPlayTime());
            Thread.sleep(Math.max(0,remaining)+100);
            int end=ui(name::getScrollX);
            check(end>0 && !ui(textMotion::isRunning),"SSID reaches its end");
            Thread.sleep(300);
            check(ui(name::getScrollX)==end,"SSID pauses at its end");
            Thread.sleep(1000);
            check(ui(name::getScrollX)==0,"SSID repeats from its beginning");
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(ui(() -> name.getScrollX()==0 && name.getEllipsize()==android.text.TextUtils.TruncateAt.END),"losing focus restores ellipsis and origin");
            check(ui(() -> !name.isHorizontalFadingEdgeEnabled()),"unfocused SSID disables edge compositing");
            WifiNameView shortName=ui(() -> (WifiNameView)((android.view.ViewGroup)rows.getChildAt(1)).getChildAt(1));
            Thread.sleep(800);
            check(ui(() -> shortName.getScrollX()==0 && field(shortName,"motion")==null),"short SSID allocates no animator");
            check(ui(() -> !textMotion.isRunning()),"unfocused SSID has no active animator");
            key(KeyEvent.KEYCODE_DPAD_UP);
            for(int i=0;i<capacity;i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
            int early=ui(carousel::getScrollY);
            Thread.sleep(50);
            int middle=ui(carousel::getScrollY);
            check(middle>early && middle<pitch,"scroll has intermediate positions, no jump");
            Thread.sleep(600);
            check(Math.abs(ui(carousel::getScrollY)-pitch)<=1,"first scroll settles on full-row boundary");
            for(int i=capacity;i<31;i++) {key(KeyEvent.KEYCODE_DPAD_DOWN);Thread.sleep(45);}
            Thread.sleep(650);
            check(ui(() -> rows.getChildAt(31).hasFocus()),"fast repeat reaches last AP without switching tabs");
            check(Math.abs(ui(carousel::getScrollY)-(32-capacity)*pitch)<=1,"last page has only whole rows");
            check(ui(() -> focusedSurfaceFits(rows.getChildAt(31),carousel)),"focused last AP fits viewport including zoom");
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(ui(() -> rows.getChildAt(31).hasFocus()),"last row stays selected at lower boundary");
            for(int i=31;i>0;i--) {key(KeyEvent.KEYCODE_DPAD_UP);Thread.sleep(45);}
            Thread.sleep(650);
            check(ui(() -> rows.getChildAt(0).hasFocus() && carousel.getScrollY()==0),"reverse repeat returns to first AP");
            key(KeyEvent.KEYCODE_DPAD_UP);
            check(ui(refresh::hasFocus),"Up returns to top actions");
            key(KeyEvent.KEYCODE_DPAD_UP);
            check(ui(() -> ((android.view.View)field(center,"networkTab")).hasFocus()),"Up retains Network tab");
            ui(() -> {rows.getChildAt(0).requestFocus();return null;});
            for(int i=0;i<capacity;i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
            ui(() -> {((android.view.View)field(center,"overviewTab")).requestFocus();return null;});
            check(ui(() -> !((androidx.dynamicanimation.animation.SpringAnimation)field(carousel,"motion")).isRunning()),"leaving tab cancels scroll frames");
            android.view.ViewGroup overview=ui(() -> (android.view.ViewGroup)field(field(center,"currentPage"),"content"));
            android.view.ViewGroup second=(android.view.ViewGroup)overview.getChildAt(1);
            android.view.ViewGroup shortcuts=(android.view.ViewGroup)overview.getChildAt(2);
            android.view.View apps=second.getChildAt(0), clock=second.getChildAt(1);
            android.view.View firstShortcut=((android.view.ViewGroup)shortcuts.getChildAt(0)).getChildAt(0);
            android.view.View lastShortcut=((android.view.ViewGroup)shortcuts.getChildAt(shortcuts.getChildCount()-1)).getChildAt(0);
            ui(() -> {clock.requestFocus();return null;});
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(ui(lastShortcut::hasFocus),"right column Down selects rightmost shortcut");
            for(int i=0;i<3;i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(ui(lastShortcut::hasFocus),"bottom Down retains rightmost shortcut");
            key(KeyEvent.KEYCODE_DPAD_UP);
            check(ui(clock::hasFocus),"rightmost shortcut Up returns to clock");
            ui(() -> {apps.requestFocus();return null;});
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(ui(firstShortcut::hasFocus),"left column Down selects leftmost shortcut");
            key(KeyEvent.KEYCODE_DPAD_UP);
            check(ui(apps::hasFocus),"leftmost shortcut Up returns to apps");
            ui(() -> {set("closing",true); return null;}); // Keep fixture isolated from real scanners.
            @SuppressWarnings("unchecked")
            List<android.view.View> tabs=ui(() -> (List<android.view.View>)field(center,"tabViews"));
            ui(() -> {tabs.get(0).requestFocus(); return null;});
            for(int i=0;i<4;i++) key(KeyEvent.KEYCODE_DPAD_LEFT);
            check(ui(() -> tabs.get(0).hasFocus()),"left boundary stays in first tab");
            for(int i=1;i<tabs.size();i++) {
                key(KeyEvent.KEYCODE_DPAD_RIGHT);
                final int index=i;
                check(ui(() -> tabs.get(index).hasFocus()),"Right selects adjacent tab");
            }
            for(int i=0;i<4;i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
            check(ui(() -> tabs.get(tabs.size()-1).hasFocus()),"right boundary stays in last tab");
            for(int i=tabs.size()-2;i>=0;i--) {
                key(KeyEvent.KEYCODE_DPAD_LEFT);
                final int index=i;
                check(ui(() -> tabs.get(index).hasFocus()),"Left selects adjacent tab");
            }
            ui(() -> {set("closing",false); return null;});
            android.util.Log.i("WifiFixture","PASS "+checks+" native carousel checks");
        } catch(Throwable failure) {android.util.Log.e("WifiFixture","FAIL after "+checks+" checks",failure);}
    }

    private boolean focusedSurfaceFits(android.view.View row, WifiCarousel carousel) throws Exception {
        android.graphics.RectF surface = new android.graphics.RectF(
                (android.graphics.RectF)field(row.getBackground(),"bounds"));
        android.graphics.Matrix transform = new android.graphics.Matrix();
        row.transformMatrixToGlobal(transform); transform.mapRect(surface);
        for(android.view.ViewParent parent=row.getParent();parent instanceof android.view.ViewGroup group;
                parent=group.getParent()) {
            boolean paddingClip=group.getClipToPadding() && (group.getPaddingLeft()!=0 || group.getPaddingTop()!=0
                    || group.getPaddingRight()!=0 || group.getPaddingBottom()!=0);
            if(!group.getClipChildren() && !paddingClip && group!=carousel) continue;
            android.graphics.RectF clip=new android.graphics.RectF(0,0,group.getWidth(),group.getHeight());
            if(paddingClip) clip.set(group.getPaddingLeft(),group.getPaddingTop(),
                    group.getWidth()-group.getPaddingRight(),group.getHeight()-group.getPaddingBottom());
            transform.reset(); group.transformMatrixToGlobal(transform); transform.mapRect(clip);
            if(surface.left<clip.left || surface.right>clip.right || surface.top<clip.top || surface.bottom>clip.bottom)
                return false;
        }
        return true;
    }

    private void set(String name, Object value) throws ReflectiveOperationException {
        Field field = ControlCenter.class.getDeclaredField(name);
        field.setAccessible(true); field.set(center, value);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return center != null && center.isShowing() ? center.dispatchKeyEvent(event) : super.dispatchKeyEvent(event);
    }
    public void settingsGearHeld() { }
    public boolean remoteSettingsHoldEnabled() { return false; }
    public void controlCenterDismissed(ControlCenter closed, boolean returnToGear) { finish(); }
    public void controlInputRows(LinearLayout rows, java.util.function.Consumer<Runnable> activate) { }
    public void controlOpen(Intent target) { }
    public boolean controlBackdropReady() { return true; }
    public String controlBackdropKey() { return "wifi-layout-fixture"; }
    public void controlSleep() { }
}
