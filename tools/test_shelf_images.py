#!/usr/bin/env python3
"""Fault-inject the actual ShelfImages.load method; no Android device or real allocations required."""
import argparse
from pathlib import Path
import subprocess
import tempfile

from test_control_network import extract_method


HARNESS = r"""
package com.gathin.tvhome;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

final class ShelfImageBoundaryTest {
    static int checks;
    static String fault;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void fail(String step){
        if(step.equals(fault))throw new OutOfMemoryError(step);
        if((step+"-runtime").equals(fault))throw new IllegalArgumentException(step);
    }
    static final class Bitmap {
        enum Config {RGB_565}
        enum CompressFormat {JPEG}
        int width=1024,height=768;
        int getWidth(){return width;} int getHeight(){return height;}
        void recycle(){}
        void prepareToDraw(){fail("prepare");}
        boolean compress(CompressFormat format,int quality,OutputStream output){
            fail("compress");try{output.write(2);}catch(IOException e){throw new UncheckedIOException(e);}return true;
        }
        static Bitmap createScaledBitmap(Bitmap source,int width,int height,boolean filter){
            fail("scale");Bitmap result=new Bitmap();result.width=width;result.height=height;return result;
        }
    }
    static final class BitmapFactory {
        static final class Options {Bitmap.Config inPreferredConfig;boolean inJustDecodeBounds;int outWidth,outHeight,inSampleSize;}
        static Bitmap decodeByteArray(byte[] bytes,int offset,int length,Options options){
            if(options.inJustDecodeBounds){fail("bounds");options.outWidth=1024;options.outHeight=768;return null;}
            fail(bytes[0]==2?"cached-decode":"source-decode");return new Bitmap();
        }
    }
    static final class Memory extends HashMap<String,Bitmap> {
        int evictions;
        void evictAll(){clear();evictions++;}
    }
    static final class Disk {
        byte[] bytes;
        byte[] get(String url,long now){fail("disk-read");return bytes;}
        void put(String url,byte[] value,long now){bytes=value;}
    }
    static final class Uri {
        final java.net.URI value;
        Uri(String text){value=java.net.URI.create(text);}
        static Uri parse(String text){return new Uri(text);}
        String getScheme(){return value.getScheme();}String getHost(){return value.getHost();}
    }
    static final class Context {
        Context getContentResolver(){return this;}
        InputStream openInputStream(Uri uri){return new ByteArrayInputStream(new byte[]{1});}
    }
    static final class Http {
        static int requests;
        static byte[] get(String url,int limit)throws IOException{
            requests++;if("network-io".equals(fault))throw new IOException("offline");return new byte[]{1};
        }
        static byte[] readAll(InputStream input,int limit)throws IOException{return input.readAllBytes();}
    }
    static final class Log {static void w(String tag,String message){}}
    final Memory memory=new Memory();final Map<String,Long> failures=new HashMap<>();
    final Disk disk=new Disk();final Context context=new Context();
    final AtomicInteger network=new AtomicInteger(),diskHits=new AtomicInteger(),memoryHits=new AtomicInteger();
    __ACTUAL_LOAD__

    static final String URL="https://art.example/image?private=not-logged";
    static ShelfImageBoundaryTest fresh(String nextFault,boolean cached){
        fault=nextFault;Http.requests=0;
        ShelfImageBoundaryTest loader=new ShelfImageBoundaryTest();
        if(cached)loader.disk.bytes=new byte[]{2};
        loader.memory.put("visible-neighbour",new Bitmap());
        return loader;
    }
    public static void main(String[] args){
        ShelfImageBoundaryTest normal=fresh(null,false);
        Bitmap first=normal.load(URL);
        check(first!=null&&first.width==512,"existing 512px scaling is preserved");
        check(normal.load(URL)==first&&Http.requests==1,"repeat load uses the existing memory cache");
        ShelfImageBoundaryTest disk=fresh(null,true);
        check(disk.load(URL)!=null&&Http.requests==0&&disk.diskHits.get()==1,"disk hit avoids network");

        for(String point:new String[]{"disk-read","cached-decode","prepare"}){
            ShelfImageBoundaryTest loader=fresh(point,true);
            check(loader.load(URL)==null,"cached "+point+" OOM returns normally");
            check(loader.memory.isEmpty()&&loader.memory.evictions==1,"cached "+point+" releases LRU references");
            check(Http.requests==0&&loader.failures.isEmpty(),"cached "+point+" is not a network/image failure");
            fault=null;
            check(loader.load(URL)!=null,"cached "+point+" can recover without restarting Home");
        }
        for(String point:new String[]{"bounds","source-decode","scale","compress","prepare"}){
            ShelfImageBoundaryTest loader=fresh(point,false);
            check(loader.load(URL)==null,"source "+point+" OOM returns normally");
            check(loader.memory.isEmpty()&&loader.memory.evictions==1,"source "+point+" releases LRU references");
            check(loader.failures.isEmpty(),"temporary memory pressure does not blacklist artwork");
            fault=null;
            check(loader.load(URL)!=null,"source "+point+" recovers without restarting Home");
        }
        ShelfImageBoundaryTest broken=fresh("cached-decode-runtime",true);
        check(broken.load(URL)==null&&broken.failures.containsKey(URL),"bad cached decode is contained");
        ShelfImageBoundaryTest offline=fresh("network-io",false);
        check(offline.load(URL)==null&&offline.load(URL)==null&&Http.requests==1,"existing one-minute network failure throttle is preserved");
        ShelfImageBoundaryTest cancelled=fresh("network-io",false);
        Thread.currentThread().interrupt();
        try{check(cancelled.load(URL)==null&&cancelled.failures.isEmpty(),"cancelled request does not blacklist artwork");}
        finally{Thread.interrupted();}
        System.out.println("ShelfImages.load: "+checks+" checks passed (actual method; deterministic decode/upload faults)");
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = (root / "launcher/app/src/main/java/com/gathin/tvhome/ShelfImages.java").read_text(encoding="utf-8")
    method = extract_method(source, "Bitmap load(String url)")
    java = args.java_home.resolve() / "bin"
    with tempfile.TemporaryDirectory(prefix="peachos-shelf-images-") as directory:
        temp = Path(directory)
        harness = temp / "ShelfImageBoundaryTest.java"
        harness.write_text(HARNESS.replace("__ACTUAL_LOAD__", method), encoding="utf-8")
        clock = temp / "android/os/SystemClock.java"
        clock.parent.mkdir(parents=True)
        clock.write_text("package android.os; public final class SystemClock {public static long elapsedRealtime(){return 1000;}}", encoding="utf-8")
        subprocess.run([str(java / "javac.exe"), "-encoding", "UTF-8", "-d", str(temp), str(harness), str(clock)], check=True)
        subprocess.run([str(java / "java.exe"), "-cp", str(temp), "com.gathin.tvhome.ShelfImageBoundaryTest"], check=True)


if __name__ == "__main__":
    main()
