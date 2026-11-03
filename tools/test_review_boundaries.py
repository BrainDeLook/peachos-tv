#!/usr/bin/env python3
"""Fault-inject the actual network lifecycle blocks and wallpaper decode method; no device changes."""
import argparse
from pathlib import Path
import subprocess
import tempfile
from test_control_network import extract_method

HARNESS = r'''
package com.gathin.tvhome;
import java.io.File;
public class ReviewBoundaryTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static class NetworkCapabilities {static int TRANSPORT_VPN=4,NET_CAPABILITY_NOT_VPN=15;}
    static class NetworkRequest {
        static class Builder {
            Builder addTransportType(int ignored){return this;}
            Builder removeCapability(int ignored){return this;}
            NetworkRequest build(){return new NetworkRequest();}
        }
    }
    static class ConnectivityManager {
        int failRegister,failRelease,released; boolean normal,vpn;
        void registerDefaultNetworkCallback(Object callback){if((failRegister&1)!=0)throw new SecurityException();normal=true;}
        void registerNetworkCallback(NetworkRequest request,Object callback){if((failRegister&2)!=0)throw new IllegalStateException();vpn=true;}
        void unregisterNetworkCallback(Object callback){
            int bit=callback==NetworkLifecycle.networkCallback?1:2;released|=bit;
            if((failRelease&bit)!=0)throw new IllegalArgumentException();
        }
    }
    static class NetworkLifecycle {
        static final Object networkCallback=new Object(),vpnCallback=new Object();
        boolean networkCallbackRegistered,vpnCallbackRegistered;
        void register(ConnectivityManager cm){REGISTER}
        void close(ConnectivityManager cm){RELEASE}
    }
    static class Bitmap {
        enum Config{RGB_565,HARDWARE}
        static boolean copyOom,copyNull,copyUnsupported; boolean recycled,hardware;
        Bitmap copy(Config config,boolean mutable){if(copyOom)throw new OutOfMemoryError();if(copyUnsupported)throw new IllegalArgumentException();if(copyNull)return null;Bitmap gpu=new Bitmap();gpu.hardware=true;return gpu;}
        void recycle(){recycled=true;}
        boolean isRecycled(){return recycled;}
    }
    static class BitmapFactory {
        static int mode;static Bitmap software;
        static class Options {
            boolean inJustDecodeBounds,inScaled,inMutable;int outWidth,outHeight,inSampleSize,inDensity,inTargetDensity;Bitmap.Config inPreferredConfig;
        }
        static Bitmap decodeFile(String path,Options options){
            if(mode==1)throw new OutOfMemoryError();
            if(options.inJustDecodeBounds){options.outWidth=mode==2?-1:1920;options.outHeight=1080;return null;}
            if(mode==3)return null;
            software=new Bitmap();return software;
        }
    }
    static class Canvas {
        static boolean fail;Canvas(Bitmap bitmap){}
        void drawColor(int ignored){if(fail)throw new OutOfMemoryError();}
    }
    static class Wallpaper {
        int width=1920,height=1080,dimColor=0;
        Bitmap.Config bitmapConfig=Bitmap.Config.RGB_565;
        RESULT
        DECODE
    }
    public static void main(String[] args){
        for(int register=0;register<4;register++)for(int release=0;release<4;release++){
            NetworkLifecycle life=new NetworkLifecycle();ConnectivityManager cm=new ConnectivityManager();
            cm.failRegister=register;cm.failRelease=release;life.register(cm);
            int expected=(cm.normal?1:0)|(cm.vpn?2:0);
            check(life.networkCallbackRegistered==cm.normal && life.vpnCallbackRegistered==cm.vpn,"track only successful registrations");
            life.close(cm);check(cm.released==expected,"release all and only registered observers despite failures");
            check(!life.networkCallbackRegistered&&!life.vpnCallbackRegistered,"clear registration state");
            cm.released=0;life.close(cm);check(cm.released==0,"no duplicate release");
        }
        NetworkLifecycle absent=new NetworkLifecycle();absent.register(null);absent.close(null);
        check(!absent.networkCallbackRegistered&&!absent.vpnCallbackRegistered,"absent network service");
        Wallpaper wallpaper=new Wallpaper();File file=new File("unused.jpg");
        Wallpaper.DecodeResult result=wallpaper.decode(file);
        check(result.bitmap.hardware && BitmapFactory.software.recycled,"normal GPU copy releases software image");
        Bitmap.copyOom=true;result=wallpaper.decode(file);
        check(result.bitmap==BitmapFactory.software&&!result.bitmap.recycled&&!result.memoryPressure,"GPU OOM retains valid software fallback");
        Bitmap.copyOom=false;Bitmap.copyUnsupported=true;result=wallpaper.decode(file);
        check(result.bitmap==BitmapFactory.software&&!result.bitmap.recycled&&!result.memoryPressure,"unsupported hardware copy preserves valid image");
        Bitmap.copyUnsupported=false;Bitmap.copyNull=true;result=wallpaper.decode(file);
        check(result.bitmap==BitmapFactory.software&&!result.memoryPressure,"missing GPU copy retains software fallback");
        Bitmap.copyNull=false;BitmapFactory.mode=1;result=wallpaper.decode(file);
        check(result.bitmap==null&&result.memoryPressure,"decode OOM is retryable, not unreadable");
        BitmapFactory.mode=2;result=wallpaper.decode(file);
        check(result==Wallpaper.DecodeResult.UNREADABLE,"invalid image is unreadable");
        BitmapFactory.mode=3;result=wallpaper.decode(file);
        check(result==Wallpaper.DecodeResult.UNREADABLE,"null decode is unreadable");
        BitmapFactory.mode=0;Canvas.fail=true;wallpaper.dimColor=1;result=wallpaper.decode(file);
        check(result.memoryPressure&&BitmapFactory.software.recycled,"OOM after decode releases unpublished bitmap");
        System.out.println("PASS "+checks+" actual-code fault-injection checks");
    }
}
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = root / 'launcher/app/src/main/java/com/gathin/tvhome'
    activity = (source / 'MainActivity.java').read_text(encoding='utf-8')
    create = extract_method(activity, 'protected void onCreate(')
    destroy = extract_method(activity, 'protected void onDestroy(')
    marker = 'ConnectivityManager cm = getSystemService(ConnectivityManager.class);'
    register = extract_method(create[create.index(marker):], 'if (cm != null)')
    release = extract_method(destroy[destroy.index(marker):], 'if (cm != null)')
    wallpaper = (source / 'WallpaperPlayer.java').read_text(encoding='utf-8')
    harness = HARNESS.replace('REGISTER', register).replace('RELEASE', release)
    harness = harness.replace('RESULT', extract_method(wallpaper, 'private static final class DecodeResult'))
    harness = harness.replace('DECODE', extract_method(wallpaper, 'private DecodeResult decode('))
    with tempfile.TemporaryDirectory(prefix='peach-review-') as folder:
        target = Path(folder)
        (target / 'ReviewBoundaryTest.java').write_text(harness, encoding='utf-8')
        log = target / 'android/util/Log.java'
        log.parent.mkdir(parents=True)
        log.write_text('package android.util; public class Log { public static int w(String t,String m,Throwable x){return 0;} }', encoding='utf-8')
        java = args.java_home.resolve() / 'bin'
        subprocess.run([str(java / 'javac'), '-encoding', 'UTF-8', '-d', folder, str(log),
                        str(source / 'WallpaperSize.java'), str(target / 'ReviewBoundaryTest.java')], check=True)
        subprocess.run([str(java / 'java'), '-cp', folder, 'com.gathin.tvhome.ReviewBoundaryTest'], check=True)

if __name__ == '__main__':
    main()
