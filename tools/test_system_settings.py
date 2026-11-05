#!/usr/bin/env python3
"""Fault-inject the actual system-settings resolver/launcher; no device access."""
import argparse
from pathlib import Path
import subprocess
import tempfile
from test_wifi_connection_intent import STUBS as WIFI_STUBS

STUBS = dict(WIFI_STUBS)
STUBS.pop('com/gathin/tvhome/SystemSettings.java')
STUBS['android/content/ComponentName.java'] = '''
package android.content;
public class ComponentName {
    final String pkg,name;
    public ComponentName(String p,String n){pkg=p;name=n;}
    public String getPackageName(){return pkg;}public String getClassName(){return name;}
    public static ComponentName unflattenFromString(String text){int n=text.indexOf('/');return n<1?null:new ComponentName(text.substring(0,n),text.substring(n+1));}
    public boolean equals(Object value){return value instanceof ComponentName c&&pkg.equals(c.pkg)&&name.equals(c.name);}
    public int hashCode(){return pkg.hashCode()+name.hashCode();}
}
'''
STUBS['android/content/Intent.java'] = '''
package android.content;
import android.net.Uri;
public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000;
    String action;ComponentName component;public int flags;
    public Intent(){}public Intent(String a){action=a;}public Intent(String a,Uri ignored){action=a;}
    public Intent(Intent copy){action=copy.action;component=copy.component;flags=copy.flags;}
    public String getAction(){return action;}public ComponentName getComponent(){return component;}
    public Intent setComponent(ComponentName c){component=c;return this;}public Intent addFlags(int f){flags|=f;return this;}
}
'''
STUBS['android/content/ActivityNotFoundException.java'] = 'package android.content; public class ActivityNotFoundException extends RuntimeException {}'
STUBS['android/net/Uri.java'] = 'package android.net; public class Uri {public static Uri parse(String s){return new Uri();}}'
STUBS['android/content/res/Resources.java'] = '''
package android.content.res;
public class Resources {public String[] components={"vendor.old/vendor.old.Removed", "vendor.good/vendor.good.Settings"};public String[] getStringArray(int id){return components;}}
'''
STUBS['com/gathin/tvhome/R.java'] = 'package com.gathin.tvhome; final class R {static class array {static int settings_fallback_components=1;}}'
STUBS['android/provider/Settings.java'] = '''
package android.provider;
public class Settings {public static final String ACTION_SETTINGS="android.settings.SETTINGS",ACTION_WIFI_SETTINGS="android.settings.WIFI_SETTINGS",
 ACTION_BLUETOOTH_SETTINGS="android.settings.BLUETOOTH_SETTINGS",ACTION_APPLICATION_DETAILS_SETTINGS="android.settings.APPLICATION_DETAILS_SETTINGS";}
'''
STUBS['android/content/pm/ApplicationInfo.java'] = 'package android.content.pm; public class ApplicationInfo {public boolean enabled=true;}'
STUBS['android/content/pm/ActivityInfo.java'] = '''
package android.content.pm;
public class ActivityInfo {public boolean exported=true,enabled=true;public String name,packageName,permission;public ApplicationInfo applicationInfo=new ApplicationInfo();}
'''
STUBS['android/content/pm/PackageManager.java'] = '''
package android.content.pm;
import android.content.*;import java.util.*;
public class PackageManager {
 public static final int MATCH_DEFAULT_ONLY=1,PERMISSION_GRANTED=0;
 public final Map<String,ResolveInfo> actions=new HashMap<>(),components=new HashMap<>();
 public ResolveInfo resolveActivity(Intent i,int flags){ComponentName c=i.getComponent();return c==null?actions.get(i.getAction()):components.get(c.getPackageName()+"/"+c.getClassName());}
}
'''
STUBS['android/content/Context.java'] = '''
package android.content;
import android.content.pm.*;import android.content.res.Resources;import java.util.*;
public class Context {
 public final PackageManager packages=new PackageManager(); public final Resources resources=new Resources();
 public final Set<String> denied=new HashSet<>(),permissions=new HashSet<>();public final List<String> launched=new ArrayList<>();
 public PackageManager getPackageManager(){return packages;}public Resources getResources(){return resources;}
 public String getPackageName(){return "com.gathin.tvhome.backdrops";}
 public int checkSelfPermission(String permission){return permissions.contains(permission)?0:-1;}
 public void startActivity(Intent intent){ResolveInfo r=packages.resolveActivity(intent,0);if(r==null)throw new ActivityNotFoundException();
  if(denied.contains(r.activityInfo.name))throw new SecurityException("OEM permission");launched.add(r.activityInfo.name);}
}
'''

HARNESS = '''
package com.gathin.tvhome;
import android.content.*;import android.content.pm.*;import android.provider.Settings;
public class SystemSettingsBoundaryTest {
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static ResolveInfo candidate(String pkg,String name){ResolveInfo r=new ResolveInfo();r.activityInfo.packageName=pkg;r.activityInfo.name=name;return r;}
 static Context context(){Context c=new Context();c.packages.components.put("vendor.good/vendor.good.Settings",candidate("vendor.good","vendor.good.Settings"));return c;}
 public static void main(String[] args){
  Context c=context();Intent selected=SystemSettings.intent(c,Settings.ACTION_WIFI_SETTINGS);
  check("vendor.good".equals(selected.getComponent().getPackageName()),"missing class does not block next vendor");
  ResolveInfo closed=candidate("system","closed.Settings");closed.activityInfo.exported=false;c.packages.actions.put(Settings.ACTION_SETTINGS,closed);
  check(SystemSettings.intent(c,Settings.ACTION_SETTINGS).getComponent()!=null,"unexported standard handler skipped");
  closed.activityInfo.exported=true;closed.activityInfo.permission="signature.only";
  check(SystemSettings.intent(c,Settings.ACTION_SETTINGS).getComponent()!=null,"signature-gated standard handler skipped");
  closed.activityInfo.permission=null;closed.activityInfo.enabled=false;
  check(SystemSettings.intent(c,Settings.ACTION_SETTINGS).getComponent()!=null,"disabled standard handler skipped");
  closed.activityInfo.enabled=true;c.denied.add("closed.Settings");
  Intent original=new Intent(Settings.ACTION_SETTINGS);check(SystemSettings.start(c,original),"launch-time denial falls back");
  check(c.launched.size()==1&&"vendor.good.Settings".equals(c.launched.get(0)),"one successful vendor action");
  check(original.flags==0,"caller intent is not mutated");
  c.launched.clear();check(!SystemSettings.start(c,new Intent("unrelated.app.ACTION")),"unrelated action must not open settings");
  check(c.launched.isEmpty(),"no unintended settings launch");
  Context empty=new Context();empty.resources.components=new String[0];check(!SystemSettings.start(empty,new Intent(Settings.ACTION_SETTINGS)),"missing all handlers is a clean failure");
  Context bluetooth=context();bluetooth.packages.actions.put("com.nes.intent.action.ACCESSORIES",candidate("vendor","vendor.Accessories"));
  check("com.nes.intent.action.ACCESSORIES".equals(SystemSettings.intent(bluetooth,Settings.ACTION_BLUETOOTH_SETTINGS).getAction()),"accessory action preferred to generic vendor");
  Context stub=context();stub.packages.actions.put(Settings.ACTION_SETTINGS,candidate("system","system.CTSDummyActivity"));
  check(SystemSettings.intent(stub,Settings.ACTION_SETTINGS).getComponent()!=null,"FireOS compliance stub excluded");
  System.out.println("SystemSettings: "+checks+" boundary checks passed");
 }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix='peachos-settings-boundary-') as folder:
        folder = Path(folder)
        for name, content in STUBS.items():
            path = folder / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(content, encoding='utf-8')
        source = root / 'launcher/app/src/main/java/com/gathin/tvhome/SystemSettings.java'
        (folder / 'com/gathin/tvhome/SystemSettings.java').write_text(source.read_text(encoding='utf-8'), encoding='utf-8')
        (folder / 'com/gathin/tvhome/SystemSettingsBoundaryTest.java').write_text(HARNESS, encoding='utf-8')
        java = args.java_home.resolve() / 'bin'
        subprocess.run([str(java/'javac.exe'), '-encoding', 'UTF-8', '-d', str(folder), *map(str,folder.rglob('*.java'))], check=True)
        subprocess.run([str(java/'java.exe'), '-cp', str(folder), 'com.gathin.tvhome.SystemSettingsBoundaryTest'], check=True)


if __name__ == '__main__': main()
