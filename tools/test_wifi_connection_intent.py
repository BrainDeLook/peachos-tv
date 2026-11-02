#!/usr/bin/env python3
"""Exercise the actual Wi-Fi connection intent boundary against host Android facades."""
import argparse
from pathlib import Path
import subprocess
import tempfile


STUBS = {
    "android/content/ComponentName.java": """
package android.content;
public class ComponentName {
    private final String pkg,name;
    public ComponentName(String pkg,String name){this.pkg=pkg;this.name=name;}
    public String getPackageName(){return pkg;} public String getClassName(){return name;}
}
""",
    "android/content/Intent.java": """
package android.content;
import java.util.*;
public class Intent {
    private final String action; private ComponentName component;
    public final Map<String,Object> extras=new HashMap<>();
    public Intent(){this.action=null;}
    public Intent(String action){this.action=action;}
    public Intent(Intent other){action=other.action;component=other.component;extras.putAll(other.extras);}
    public String getAction(){return action;}
    public Intent setComponent(ComponentName component){this.component=component;return this;}
    public ComponentName getComponent(){return component;}
    public Intent putExtra(String key,String value){extras.put(key,value);return this;}
    public Intent putExtra(String key,int value){extras.put(key,value);return this;}
}
""",
    "android/content/Context.java": """
package android.content;
import android.content.pm.*;
import java.util.*;
public class Context {
    public final PackageManager packages=new PackageManager();
    public final Set<String> permissions=new HashSet<>();
    public int permissionChecks;
    public PackageManager getPackageManager(){return packages;}
    public int checkSelfPermission(String permission){
        permissionChecks++; return permissions.contains(permission)?PackageManager.PERMISSION_GRANTED:-1;
    }
}
""",
    "android/content/pm/ApplicationInfo.java": """
package android.content.pm;
public class ApplicationInfo {
    public static final int FLAG_SYSTEM=1,FLAG_UPDATED_SYSTEM_APP=128;
    public int flags;
}
""",
    "android/content/pm/ActivityInfo.java": """
package android.content.pm;
public class ActivityInfo {
    public boolean exported; public String packageName,name,permission;
    public ApplicationInfo applicationInfo=new ApplicationInfo();
}
""",
    "android/content/pm/ResolveInfo.java": """
package android.content.pm;
public class ResolveInfo { public ActivityInfo activityInfo=new ActivityInfo(); }
""",
    "android/content/pm/PackageManager.java": """
package android.content.pm;
import android.content.ComponentName;
import android.content.Intent;
import java.util.*;
public class PackageManager {
    public static final int PERMISSION_GRANTED=0,MATCH_DEFAULT_ONLY=65536;
    public final List<ResolveInfo> candidates=new ArrayList<>();
    public final List<ResolveInfo> explicitCandidates=new ArrayList<>();
    public int queries,implicitQueries,explicitQueries,flags; public String action;
    public ComponentName explicitTarget;
    public List<ResolveInfo> queryIntentActivities(Intent intent,int flags){
        queries++;this.flags=flags;action=intent.getAction();
        ComponentName target=intent.getComponent();
        if(target==null){implicitQueries++;return candidates;}
        explicitQueries++;explicitTarget=target;
        List<ResolveInfo> matching=new ArrayList<>();
        for(ResolveInfo candidate:explicitCandidates){
            ActivityInfo activity=candidate.activityInfo;
            if(target.getPackageName().equals(activity.packageName)&&target.getClassName().equals(activity.name))
                matching.add(candidate);
        }
        return matching;
    }
}
""",
    "android/provider/Settings.java": """
package android.provider;
public class Settings { public static final String ACTION_WIFI_SETTINGS="android.settings.WIFI_SETTINGS"; }
""",
    "com/gathin/tvhome/SystemSettings.java": """
package com.gathin.tvhome;
import android.content.*;
final class SystemSettings {
    static int calls;
    static Intent intent(Context context,String action){calls++;return new Intent(action);}
}
""",
}

HARNESS = r"""
package com.gathin.tvhome;
import android.content.*;
import android.content.pm.*;
import android.provider.Settings;
import java.util.*;

public final class WifiConnectionIntentTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static ResolveInfo candidate(String pkg,boolean exported,int flags,String permission){
        ResolveInfo result=new ResolveInfo();
        result.activityInfo.packageName=pkg;result.activityInfo.name=pkg+".WifiConnectionActivity";
        result.activityInfo.exported=exported;result.activityInfo.applicationInfo.flags=flags;
        result.activityInfo.permission=permission;return result;
    }
    private static WifiAccessPoints.Entry network(String ssid,String capabilities){
        return WifiAccessPoints.build(Collections.singletonList(
            new WifiAccessPoints.Observation(ssid,"AA:01",capabilities,-60)),null,null).get(0);
    }
    private static void fallback(Intent intent,String message){
        check(Settings.ACTION_WIFI_SETTINGS.equals(intent.getAction()),message+": system settings action");
        check(intent.getComponent()==null,message+": no untrusted explicit component");
        check(intent.extras.isEmpty(),message+": no unsupported configuration extras");
    }
    public static void main(String[] args){
        Context exact=new Context();
        exact.packages.candidates.add(candidate("system.tvsettings",true,ApplicationInfo.FLAG_SYSTEM,null));
        String ssid="  Домашняя \"Wi-Fi\"  ";
        Intent selected=WifiConnectionIntent.forNetwork(exact,network(ssid,"[WPA2-PSK-CCMP][ESS]"));
        check("com.android.settings.wifi.action.WIFI_CONNECTION_SETTINGS".equals(selected.getAction()),"TV connection action");
        check(selected.getComponent().getPackageName().equals("system.tvsettings"),"explicit trusted target");
        check(selected.getComponent().getClassName().equals("system.tvsettings.WifiConnectionActivity"),"resolved class retained");
        check(ssid.equals(selected.extras.get("wifi_ssid")),"SSID spaces quotes case and Unicode preserved");
        check(Integer.valueOf(2).equals(selected.extras.get("wifi_security_name")),"PSK uses TV security 2");
        check(selected.extras.size()==2,"only selected SSID and security handed to OS");
        check(!selected.extras.containsKey("wifi_password")&&!selected.extras.containsKey("password"),"saved password remains OS-owned");
        check(exact.packages.flags==PackageManager.MATCH_DEFAULT_ONLY,"only default intent handlers queried");
        check(exact.permissionChecks==0,"unrestricted system activity needs no permission query");

        String[] capabilities={"[ESS]","[WEP][ESS]","[RSN-PSK+SAE-CCMP][ESS]"};
        for(int security=0;security<capabilities.length;security++){
            Intent intent=WifiConnectionIntent.forNetwork(exact,network("Saved SSID",capabilities[security]));
            check(Integer.valueOf(security).equals(intent.extras.get("wifi_security_name")),"supported security contract "+security);
            check(intent.extras.size()==2,"saved or new network uses same native connection flow");
        }

        Context filtered=new Context();
        filtered.packages.candidates.add(candidate("third.party",true,0,null));
        filtered.packages.candidates.add(candidate("system.private",false,ApplicationInfo.FLAG_SYSTEM,null));
        filtered.packages.candidates.add(candidate("system.restricted",true,ApplicationInfo.FLAG_SYSTEM,"privileged"));
        filtered.packages.candidates.add(candidate("system.updated",true,ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,null));
        Intent trusted=WifiConnectionIntent.forNetwork(filtered,network("Home","[WPA2-PSK-CCMP]"));
        check(trusted.getComponent().getPackageName().equals("system.updated"),"third-party private and denied targets skipped");
        check(filtered.permissionChecks==1,"permission checked only on otherwise usable handler");
        filtered.permissions.add("privileged");
        Intent permitted=WifiConnectionIntent.forNetwork(filtered,network("Home","[WPA2-PSK-CCMP]"));
        check(permitted.getComponent().getPackageName().equals("system.restricted"),"granted protected handler accepted");

        Context none=new Context();
        fallback(WifiConnectionIntent.forNetwork(none,network("Home","[ESS]")),"no connector");
        none.packages.candidates.add(candidate("third.party",true,0,null));
        none.packages.candidates.add(candidate("system.private",false,ApplicationInfo.FLAG_SYSTEM,null));
        none.packages.candidates.add(candidate("system.restricted",true,ApplicationInfo.FLAG_SYSTEM,"privileged"));
        fallback(WifiConnectionIntent.forNetwork(none,network("Home","[WEP]")),"no usable connector");

        for(String capability:Arrays.asList("[WPA2-EAP-CCMP]","[RSN-SAE-CCMP]","[RSN-OWE-CCMP]",
                "[RSN-EAP_SUITE_B_192-GCMP-256]","[RSN-CCMP]")){
            int queries=exact.packages.queries;
            fallback(WifiConnectionIntent.forNetwork(exact,network("Protected",capability)),capability);
            check(exact.packages.queries==queries,"unsupported authentication bypasses password-only connector");
        }
        check(SystemSettings.calls==7,"every fallback passes through established vendor-aware settings helper");

        String tclPackage="com.android.tv.settings";
        String tclClass="com.android.tv.settings.connectivity.WifiConnectionActivity";
        Context tcl=new Context();
        ResolveInfo tclActivity=candidate(tclPackage,true,ApplicationInfo.FLAG_SYSTEM,null);
        tclActivity.activityInfo.name=tclClass;
        tcl.packages.explicitCandidates.add(tclActivity);
        Intent direct=WifiConnectionIntent.forNetwork(tcl,network(ssid,"[WPA2-PSK-CCMP]"));
        check(tcl.packages.implicitQueries==1&&tcl.packages.explicitQueries==1,"TCL no-filter connector found only by explicit existence query");
        check(tcl.packages.explicitTarget.getPackageName().equals(tclPackage),"known TCL system package queried");
        check(tcl.packages.explicitTarget.getClassName().equals(tclClass),"known TCL connector class queried");
        check(direct.getComponent().getPackageName().equals(tclPackage),"TCL explicit connector selected");
        check(direct.getComponent().getClassName().equals(tclClass),"TCL concrete class retained");
        check(ssid.equals(direct.extras.get("wifi_ssid")),"TCL exact selected SSID retained");
        check(Integer.valueOf(2).equals(direct.extras.get("wifi_security_name")),"TCL receives verified security integer");
        check(direct.extras.size()==2,"TCL path never supplies a password or guesses credentials");

        int fallbackCalls=SystemSettings.calls;
        tclActivity.activityInfo.exported=false;
        fallback(WifiConnectionIntent.forNetwork(tcl,network("Home","[ESS]")),"non-exported TCL connector");
        tclActivity.activityInfo.exported=true;tclActivity.activityInfo.applicationInfo.flags=0;
        fallback(WifiConnectionIntent.forNetwork(tcl,network("Home","[ESS]")),"non-system TCL connector");
        tclActivity.activityInfo.applicationInfo.flags=ApplicationInfo.FLAG_SYSTEM;
        tclActivity.activityInfo.permission="tcl.privileged";
        fallback(WifiConnectionIntent.forNetwork(tcl,network("Home","[ESS]")),"permission-denied TCL connector");
        tcl.permissions.add("tcl.privileged");
        Intent allowedTcl=WifiConnectionIntent.forNetwork(tcl,network("Home","[ESS]"));
        check(allowedTcl.getComponent().getClassName().equals(tclClass),"permission-granted TCL connector accepted");
        tclActivity.activityInfo.name=tclPackage+".UnrelatedActivity";
        fallback(WifiConnectionIntent.forNetwork(tcl,network("Home","[ESS]")),"absent known class despite installed settings package");
        check(SystemSettings.calls==fallbackCalls+4,"all rejected TCL candidates use established generic fallback");
        check(exact.packages.explicitQueries==0,"working implicit connector never triggers an extra explicit package lookup");
        System.out.println("WifiConnectionIntent: "+checks+" checks passed (real intent builder, trusted target and authentication contracts)");
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    java = args.java_home.resolve() / "bin"
    with tempfile.TemporaryDirectory(prefix="peachos-wifi-connect-") as directory:
        temp = Path(directory)
        sources = []
        for relative, text in STUBS.items():
            path = temp / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            sources.append(str(path))
        harness = temp / "com/gathin/tvhome/WifiConnectionIntentTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        sources.append(str(harness))
        for name in ("WifiAccessPoints.java", "WifiConnectionIntent.java"):
            sources.append(str(root / "launcher/app/src/main/java/com/gathin/tvhome" / name))
        subprocess.run([str(java / "javac.exe"), "-encoding", "UTF-8", "-d", str(temp), *sources], check=True)
        subprocess.run([str(java / "java.exe"), "-cp", str(temp), "com.gathin.tvhome.WifiConnectionIntentTest"], check=True)


if __name__ == "__main__":
    main()
