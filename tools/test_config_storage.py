"""Compile the real Config.dir boundary against deterministic Android storage states."""
import argparse, re, subprocess, tempfile
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java-home',type=Path,required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
source=(root/'launcher/app/src/main/java/com/gathin/tvhome/Config.java').read_text(encoding='utf-8')
method=re.search(r'    static File dir\(Context ctx\) \{.*?\n    }',source,re.S).group()
files={
 'android/content/Context.java':'''package android.content;
import java.io.File;
public class Context { public int queries; public File directory;
 public File getExternalFilesDir(String type){queries++;return directory;} }
''',
 'android/os/Environment.java':'''package android.os;
public final class Environment { public static final String MEDIA_MOUNTED="mounted";
 public static String state; public static String getExternalStorageState(){return state;} }
''',
 'com/gathin/tvhome/Config.java':'''package com.gathin.tvhome;
import java.io.File;import android.content.Context;import android.os.Environment;
class Config {\n'''+method+'\n}',
 'com/gathin/tvhome/StorageTest.java':'''package com.gathin.tvhome;
import java.io.File;import android.content.Context;import android.os.Environment;
public final class StorageTest {
 public static void main(String[] args){
  int checks=0;
  for(String state:new String[]{"unknown","removed","unmounted","checking","nofs","mounted_ro","shared","bad_removal","unmountable","ejecting"}){
   Context context=new Context();Environment.state=state;
   if(Config.dir(context)!=null||context.queries!=0)throw new AssertionError(state);
   checks++;
  }
  Context mounted=new Context();mounted.directory=new File("theme");Environment.state="mounted";
  if(Config.dir(mounted)!=mounted.directory||mounted.queries!=1)throw new AssertionError("mounted path");checks++;
  Context unavailable=new Context();
  if(Config.dir(unavailable)!=null||unavailable.queries!=1)throw new AssertionError("unavailable path");checks++;
  System.out.println("Config storage: "+checks+" checks passed (real boundary, no directory request before mount)");
 }
}
'''}
with tempfile.TemporaryDirectory(prefix='peach-config-storage-') as temporary:
 directory=Path(temporary)
 for name,content in files.items():
  path=directory/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8')
 subprocess.run([str(args.java_home/'bin/javac.exe'),'-d',str(directory),*[str(directory/name) for name in files]],check=True)
 subprocess.run([str(args.java_home/'bin/java.exe'),'-cp',str(directory),'com.gathin.tvhome.StorageTest'],check=True)
