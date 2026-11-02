#!/usr/bin/env python3
"""Run the real Wi-Fi observer against deterministic host Android service facades."""
import argparse
from pathlib import Path
import subprocess
import tempfile


STUBS = {
    "android/Manifest.java": """
package android;
public final class Manifest { public static class permission {
  public static final String ACCESS_FINE_LOCATION="fine",ACCESS_COARSE_LOCATION="coarse";
} }
""",
    "android/content/pm/PackageManager.java": """
package android.content.pm;
public class PackageManager { public static final int PERMISSION_GRANTED=0; }
""",
    "android/content/ContentResolver.java": """
package android.content;
public class ContentResolver { public int locationMode=1; }
""",
    "android/content/Intent.java": """
package android.content;
public class Intent {
  private String action;private boolean updated;
  public Intent(){}public Intent(String action){this.action=action;}
  public String getAction(){return action;}
  public Intent putExtra(String key,boolean value){updated=value;return this;}
  public boolean getBooleanExtra(String key,boolean fallback){return updated;}
}
""",
    "android/content/IntentFilter.java": """
package android.content;
public class IntentFilter { public void addAction(String value) { } }
""",
    "android/content/BroadcastReceiver.java": """
package android.content;
public abstract class BroadcastReceiver { public abstract void onReceive(Context c,Intent i); }
""",
    "android/content/Context.java": """
package android.content;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
public class Context {
  public static final int RECEIVER_EXPORTED=2;
  public final ContentResolver resolver=new ContentResolver();
  public Context app=this;
  public WifiManager wifi=new WifiManager();
  public LocationManager location=new LocationManager();
  public int permission, coarsePermission=-1, registers, unregisters, flags=-1;
  public BroadcastReceiver receiver;
  public Context getApplicationContext() { return app; }
  public <T> T getSystemService(Class<T> type) {
    return type.cast(type==WifiManager.class?wifi:location);
  }
  public int checkSelfPermission(String name) { return name.equals("fine")?permission:coarsePermission; }
  public ContentResolver getContentResolver() { return resolver; }
  public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter) {
    this.receiver=receiver; registers++; flags=0; return null;
  }
  public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,int flags) {
    registerReceiver(receiver,filter); this.flags=flags; return null;
  }
  public void unregisterReceiver(BroadcastReceiver receiver) {
    if(this.receiver!=receiver) throw new AssertionError("unbalanced receiver");
    this.receiver=null; unregisters++;
  }
  public void broadcast() { if(receiver!=null)receiver.onReceive(this,new Intent()); }
  public void scanBroadcast(boolean updated) {
    if(receiver!=null)receiver.onReceive(this,new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
      .putExtra(WifiManager.EXTRA_RESULTS_UPDATED,updated));
  }
}
""",
    "android/location/LocationManager.java": """
package android.location;
public class LocationManager {
  public static final String MODE_CHANGED_ACTION="location";
  public volatile boolean enabled=true;
  public boolean isLocationEnabled() { return enabled; }
}
""",
    "android/net/wifi/ScanResult.java": """
package android.net.wifi;
public class ScanResult {
  public String SSID,BSSID,capabilities; public int level;
  public ScanResult(String ssid,String bssid,String caps,int rssi) {
    SSID=ssid;BSSID=bssid;capabilities=caps;level=rssi;
  }
}
""",
    "android/net/wifi/SupplicantState.java": """
package android.net.wifi;
public enum SupplicantState { COMPLETED,DISCONNECTED,ASSOCIATING }
""",
    "android/net/wifi/WifiInfo.java": """
package android.net.wifi;
public class WifiInfo {
  public static final int SECURITY_TYPE_OPEN=0,SECURITY_TYPE_WEP=1,SECURITY_TYPE_PSK=2,
    SECURITY_TYPE_EAP=3,SECURITY_TYPE_SAE=4,SECURITY_TYPE_OWE=6;
  public String ssid="<unknown ssid>",bssid="02:00:00:00:00:00";
  public int rssi=-60,security=SECURITY_TYPE_PSK;
  public SupplicantState state=SupplicantState.DISCONNECTED;
  public String getSSID(){return ssid;} public String getBSSID(){return bssid;}
  public int getRssi(){return rssi;} public int getCurrentSecurityType(){return security;}
  public SupplicantState getSupplicantState(){return state;}
}
""",
    "android/net/wifi/WifiManager.java": """
package android.net.wifi;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public class WifiManager {
  public static final String SCAN_RESULTS_AVAILABLE_ACTION="scan",WIFI_STATE_CHANGED_ACTION="wifi",
    NETWORK_STATE_CHANGED_ACTION="network",UNKNOWN_SSID="<unknown ssid>",EXTRA_RESULTS_UPDATED="updated";
  public volatile boolean enabled=true;
  public volatile RuntimeException readFailure;
  public volatile List<ScanResult> results=List.of();
  public final WifiInfo info=new WifiInfo();
  public final AtomicInteger scans=new AtomicInteger(),reads=new AtomicInteger(),enabledReads=new AtomicInteger();
  public volatile CountDownLatch blocked,entered;
  public boolean isWifiEnabled(){ offMain();enabledReads.incrementAndGet();return enabled; }
  public List<ScanResult> getScanResults(){
    offMain();reads.incrementAndGet();
    CountDownLatch gate=blocked;
    if(gate!=null){ entered.countDown(); try { gate.await(3,TimeUnit.SECONDS); }
      catch(InterruptedException error){throw new AssertionError(error);} }
    if(readFailure!=null)throw readFailure;
    return results;
  }
  public WifiInfo getConnectionInfo(){offMain();return info;}
  public boolean startScan(){offMain();scans.incrementAndGet();return true;}
  private static void offMain(){
    if(!Thread.currentThread().getName().equals("PeachWifi"))
      throw new AssertionError("Wi-Fi Binder access on UI thread");
  }
}
""",
    "android/os/Build.java": """
package android.os;
public class Build { public static class VERSION { public static int SDK_INT=34; } }
""",
    "android/os/Looper.java": """
package android.os;
public class Looper { public static Looper getMainLooper(){return new Looper();} }
""",
    "android/os/Handler.java": """
package android.os;
import java.util.concurrent.*;
import java.util.*;
public class Handler {
  private record Scheduled(Runnable task,long due){}
  private static final List<Handler> handlers=new CopyOnWriteArrayList<>();
  private final Queue<Runnable> queue=new ConcurrentLinkedQueue<>();
  private final List<Scheduled> delayed=new CopyOnWriteArrayList<>();
  public Handler(Looper looper){handlers.add(this);}
  public boolean post(Runnable task){queue.add(task);return true;}
  public boolean postDelayed(Runnable task,long delay){delayed.add(new Scheduled(task,SystemClock.now+delay));return true;}
  public void removeCallbacks(Runnable task){queue.removeIf(value->value==task);delayed.removeIf(value->value.task==task);}
  public void removeCallbacksAndMessages(Object token){queue.clear();delayed.clear();}
  public static int delayedCount(){return handlers.stream().mapToInt(h->h.delayed.size()).sum();}
  public static void drain(){for(Handler h:handlers){
    Runnable r;while((r=h.queue.poll())!=null)r.run();
    for(Scheduled scheduled:h.delayed)if(scheduled.due<=SystemClock.now&&h.delayed.remove(scheduled))scheduled.task.run();
  }}
}
""",
    "android/os/SystemClock.java": """
package android.os;
public class SystemClock { public static volatile long now=100000;public static long elapsedRealtime(){return now;} }
""",
    "android/os/Process.java": """
package android.os;
public class Process { public static final int THREAD_PRIORITY_BACKGROUND=10;public static void setThreadPriority(int priority){} }
""",
    "android/provider/Settings.java": """
package android.provider;
import android.content.ContentResolver;
public class Settings { public static class Secure {
  public static final String LOCATION_MODE="mode"; public static final int LOCATION_MODE_OFF=0;
  public static int getInt(ContentResolver resolver,String key,int fallback){return resolver.locationMode;}
} }
""",
}

HARNESS = r"""
package com.gathin.tvhome;
import android.content.Context;
import android.net.wifi.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public final class WifiScannerTest {
  private static int checks;
  private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
  private static void await(BooleanSupplier ready,String message)throws Exception{
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
    while(System.nanoTime()<end){Handler.drain();if(ready.getAsBoolean())return;Thread.sleep(2);}
    throw new AssertionError("timeout: "+message);
  }
  private static void settle()throws Exception{for(int i=0;i<20;i++){Thread.sleep(2);Handler.drain();}}
  private static final class Session {
    final Context app=new Context(),activity=new Context();
    final List<WifiScanner.Snapshot> seen=new ArrayList<>();
    final WifiScanner scanner;
    Session(){activity.app=app;scanner=new WifiScanner(activity,seen::add);}
    WifiScanner.Snapshot latest(){return seen.get(seen.size()-1);}
    void start()throws Exception{scanner.start();await(()->!seen.isEmpty(),"initial snapshot");}
  }
  public static void main(String[] args)throws Exception{
    Session basic=new Session();
    basic.app.wifi.results=List.of(new ScanResult("Home","aa","[WPA2-PSK-CCMP]",-60));
    basic.app.wifi.info.ssid="\"Home\"";basic.app.wifi.info.bssid="aa";
    basic.app.wifi.info.state=SupplicantState.COMPLETED;
    basic.start();
    check(basic.latest().state==WifiScanner.State.READY,"ready");
    check(basic.latest().connectedSsid.equals("Home"),"quoted current SSID normalized");
    check(basic.latest().networks.get(0).connected,"current AP marked");
    check(basic.app.wifi.scans.get()==1,"one scan at first opening");
    check(basic.activity.registers==0&&basic.app.registers==1,"application context only");
    check(basic.app.flags==Context.RECEIVER_EXPORTED,"SDK33 privileged Wi-Fi broadcasts accepted");
    basic.scanner.start();basic.scanner.requestScan();basic.app.broadcast();settle();
    check(basic.app.registers==1,"idempotent start");
    check(basic.app.wifi.scans.get()==1,"manual scan honors global cooldown");
    check(basic.seen.size()==1,"identical snapshot not delivered again");
    basic.app.wifi.results=List.of(new ScanResult("Home","aa","[WPA2-PSK-CCMP]",-99));
    basic.app.broadcast();await(()->basic.seen.size()==2,"signal update");
    check(basic.latest().networks.get(0).bars==0,"meaningful signal change delivered");
    basic.scanner.stop();int reads=basic.app.wifi.reads.get();basic.app.broadcast();settle();
    check(basic.app.wifi.reads.get()==reads,"no reads after stop");
    check(basic.app.unregisters==1,"observer released");
    basic.scanner.stop();check(basic.app.unregisters==1,"idempotent stop");
    basic.scanner.start();await(()->basic.seen.size()==3,"restart snapshot");
    check(basic.app.wifi.scans.get()==1,"reopening cannot bypass scan budget");
    SystemClock.now+=30_000;basic.scanner.requestScan();
    await(()->basic.app.wifi.scans.get()==2,"scan after cooldown");basic.scanner.stop();

    Session permission=new Session();permission.app.permission=-1;permission.start();
    check(permission.latest().state==WifiScanner.State.PERMISSION_REQUIRED,"fine permission required on SDK34");
    check(permission.app.wifi.reads.get()==0&&permission.app.wifi.scans.get()==0,"no denied scan Binder call");
    permission.app.permission=0;permission.app.location.enabled=false;permission.app.broadcast();
    await(()->permission.latest().state==WifiScanner.State.LOCATION_REQUIRED,"location disabled");
    check(permission.app.wifi.reads.get()==0,"location gate before reading scans");
    permission.app.location.enabled=true;permission.app.broadcast();
    await(()->permission.latest().state==WifiScanner.State.READY,"permissions restored");permission.scanner.stop();

    Session disabled=new Session();disabled.app.wifi.enabled=false;disabled.app.permission=-1;disabled.start();
    check(disabled.latest().state==WifiScanner.State.DISABLED,"disabled radio state");
    check(disabled.app.wifi.reads.get()==0,"disabled radio not scanned");disabled.scanner.stop();
    Context missing=new Context();missing.wifi=null;List<WifiScanner.Snapshot> missingSeen=new ArrayList<>();
    WifiScanner absent=new WifiScanner(missing,missingSeen::add);absent.start();
    await(()->!missingSeen.isEmpty(),"no Wi-Fi hardware");
    check(missingSeen.get(0).state==WifiScanner.State.NO_WIFI,"absent Wi-Fi supported");absent.stop();

    Session denied=new Session();denied.app.wifi.readFailure=new SecurityException("revoked");denied.start();
    check(denied.latest().state==WifiScanner.State.PERMISSION_REQUIRED,"revocation race reported");denied.scanner.stop();
    Session failure=new Session();failure.app.wifi.readFailure=new IllegalStateException("firmware");failure.start();
    check(failure.latest().state==WifiScanner.State.ERROR,"firmware failure reported");failure.scanner.stop();

    Session synthetic=new Session();synthetic.app.wifi.info.ssid="\"OnlyCurrent\"";
    synthetic.app.wifi.info.bssid="bb";synthetic.app.wifi.info.state=SupplicantState.COMPLETED;
    synthetic.start();check(synthetic.latest().networks.size()==1,"current AP remains visible without scan row");
    check(synthetic.latest().networks.get(0).connected,"synthetic current marked");
    check(synthetic.latest().networks.get(0).security==WifiAccessPoints.Security.PSK,"current security retained");
    synthetic.scanner.stop();
    Session redacted=new Session();redacted.app.wifi.info.ssid="\" Home \"";
    redacted.app.wifi.info.state=SupplicantState.COMPLETED;
    redacted.app.wifi.results=List.of(new ScanResult(" Home ","cc","PSK",-70));redacted.start();
    check(redacted.latest().connectedSsid.equals(" Home "),"SSID spaces significant");
    check(redacted.latest().networks.get(0).connected,"redacted BSSID falls back to SSID");redacted.scanner.stop();
    Session unknown=new Session();unknown.app.wifi.info.state=SupplicantState.COMPLETED;unknown.start();
    check(unknown.latest().connectedSsid.isEmpty()&&unknown.latest().networks.isEmpty(),"unknown SSID never rendered");
    unknown.scanner.stop();

    Session stopped=new Session();stopped.app.wifi.blocked=new CountDownLatch(1);
    stopped.app.wifi.entered=new CountDownLatch(1);stopped.scanner.start();
    check(stopped.app.wifi.entered.await(2,TimeUnit.SECONDS),"in-flight read started");
    stopped.scanner.stop();stopped.app.wifi.blocked.countDown();settle();
    check(stopped.seen.isEmpty(),"in-flight result cannot reach closed tab");
    stopped.scanner.start();await(()->stopped.seen.size()==1,"restart after in-flight cancellation");stopped.scanner.stop();

    Session burst=new Session();burst.app.wifi.blocked=new CountDownLatch(1);
    burst.app.wifi.entered=new CountDownLatch(1);burst.scanner.start();
    check(burst.app.wifi.entered.await(2,TimeUnit.SECONDS),"burst read started");
    for(int i=0;i<100;i++)burst.app.broadcast();burst.app.wifi.blocked.countDown();
    await(()->burst.app.wifi.reads.get()==2&&!burst.seen.isEmpty(),"coalesced burst");settle();
    check(burst.app.wifi.reads.get()==2,"100 events coalesce to one follow-up read");
    check(burst.seen.size()==1,"burst does not redraw unchanged UI");burst.scanner.stop();

    Session holder=new Session();holder.app.wifi.blocked=new CountDownLatch(1);
    holder.app.wifi.entered=new CountDownLatch(1);holder.scanner.start();
    check(holder.app.wifi.entered.await(2,TimeUnit.SECONDS),"queue blocker started");
    Session queued=new Session();queued.scanner.start();queued.scanner.stop();
    Session newest=new Session();newest.scanner.start();holder.scanner.stop();holder.app.wifi.blocked.countDown();
    await(()->!newest.seen.isEmpty(),"new observer bypasses canceled queued work");
    check(queued.seen.isEmpty()&&queued.app.wifi.reads.get()==0,"queued read removed on stop");newest.scanner.stop();

    Build.VERSION.SDK_INT=26;
    Session old=new Session();old.app.resolver.locationMode=0;old.start();
    check(old.latest().state==WifiScanner.State.LOCATION_REQUIRED,"API26 location fallback");
    check(old.app.flags==0,"API26 legacy receiver registration");old.scanner.stop();
    for(int sdk:new int[]{26,27,28}){
      Build.VERSION.SDK_INT=sdk;
      Session coarse=new Session();coarse.app.permission=-1;coarse.app.coarsePermission=0;coarse.start();
      check(coarse.latest().state==WifiScanner.State.READY,"API"+sdk+" accepts coarse location");
      coarse.scanner.stop();
    }
    Build.VERSION.SDK_INT=29;
    Session modernCoarse=new Session();modernCoarse.app.permission=-1;modernCoarse.app.coarsePermission=0;
    modernCoarse.start();check(modernCoarse.latest().state==WifiScanner.State.PERMISSION_REQUIRED,
      "API29 rejects coarse-only permission for target34");modernCoarse.scanner.stop();

    Build.VERSION.SDK_INT=34;SystemClock.now+=30_000;
    Session live=new Session();live.start();
    check(live.app.wifi.scans.get()==1&&Handler.delayedCount()==1,"one initial scan and stale watchdog");
    SystemClock.now+=44_999;Handler.drain();settle();
    check(live.app.wifi.scans.get()==1,"no periodic reads before scan goes stale");
    SystemClock.now++;Handler.drain();await(()->live.app.wifi.scans.get()==2,"45-second stale refresh");settle();
    check(Handler.delayedCount()==1,"stale fallback rearms once after ready result");
    SystemClock.now+=30_000;live.app.scanBroadcast(true);settle();
    SystemClock.now+=15_000;Handler.drain();settle();
    check(live.app.wifi.scans.get()==2,"successful passive scan postpones original deadline");
    SystemClock.now+=29_999;Handler.drain();settle();
    check(live.app.wifi.scans.get()==2,"passive scan gets full 45-second freshness window");
    SystemClock.now++;Handler.drain();await(()->live.app.wifi.scans.get()==3,"stale after passive freshness expires");settle();
    check(live.seen.size()==1,"watchdog does not redraw identical available networks");
    SystemClock.now+=30_000;live.app.scanBroadcast(false);settle();
    SystemClock.now+=15_000;Handler.drain();await(()->live.app.wifi.scans.get()==4,"failed passive scan does not delay refresh");settle();
    live.app.permission=-1;live.app.broadcast();
    await(()->live.latest().state==WifiScanner.State.PERMISSION_REQUIRED,"live permission revoked");
    check(Handler.delayedCount()==0,"permission failure removes stale watchdog");
    int deniedReads=live.app.wifi.enabledReads.get();SystemClock.now+=90_000;Handler.drain();settle();
    check(live.app.wifi.enabledReads.get()==deniedReads,"no timer reads while permission required");
    live.app.permission=0;live.app.broadcast();await(()->live.latest().state==WifiScanner.State.READY,"live permission restored");
    check(Handler.delayedCount()==1,"ready state restores only one watchdog");
    live.app.location.enabled=false;live.app.broadcast();
    await(()->live.latest().state==WifiScanner.State.LOCATION_REQUIRED,"live location disabled");
    check(Handler.delayedCount()==0,"location state does not poll");
    live.app.location.enabled=true;live.app.broadcast();await(()->live.latest().state==WifiScanner.State.READY,"live location restored");
    live.app.wifi.enabled=false;live.app.broadcast();await(()->live.latest().state==WifiScanner.State.DISABLED,"live radio disabled");
    check(Handler.delayedCount()==0,"disabled radio cancels watchdog");
    live.app.wifi.enabled=true;live.app.broadcast();await(()->live.latest().state==WifiScanner.State.READY,"live radio restored");
    live.scanner.stop();int closedReads=live.app.wifi.enabledReads.get();
    check(Handler.delayedCount()==0,"tab close cancels delayed scan");
    SystemClock.now+=90_000;Handler.drain();settle();
    check(live.app.wifi.enabledReads.get()==closedReads,"no watchdog work outside visible tab");
    System.out.println("WifiScanner: "+checks+" checks passed (real worker, lifecycle, permission and scan-budget paths)");
  }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    java = args.java_home.resolve() / "bin"
    with tempfile.TemporaryDirectory(prefix="peachos-wifi-") as directory:
        temp = Path(directory)
        sources = []
        for relative, text in STUBS.items():
            path = temp / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            sources.append(str(path))
        harness = temp / "com/gathin/tvhome/WifiScannerTest.java"
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_text(HARNESS, encoding="utf-8")
        sources.append(str(harness))
        for name in ("WifiAccessPoints.java", "WifiScanner.java"):
            sources.append(str(root / "launcher/app/src/main/java/com/gathin/tvhome" / name))
        subprocess.run([str(java / "javac.exe"), "-encoding", "UTF-8", "-d", str(temp), *sources], check=True)
        subprocess.run([str(java / "java.exe"), "-cp", str(temp), "com.gathin.tvhome.WifiScannerTest"], check=True)


if __name__ == "__main__":
    main()
