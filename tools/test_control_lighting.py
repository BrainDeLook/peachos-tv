#!/usr/bin/env python3
"""Exercise actual ControlBackdrop bounds/cache/draw methods with counted graphics facades."""
import argparse
from pathlib import Path
import subprocess
import tempfile

from test_control_network import extract_method


HARNESS = r"""
package com.gathin.tvhome;
import java.util.*;
public class ControlLightingTest extends Drawable {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static class Rect { int left,top,right,bottom; Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;} int width(){return right-left;} int height(){return bottom-top;} }
    static class RectF { float left,top,right,bottom; void set(Rect r){left=r.left;top=r.top;right=r.right;bottom=r.bottom;} void inset(float x,float y){left+=x;top+=y;right-=x;bottom-=y;} float width(){return right-left;} float height(){return bottom-top;} boolean isEmpty(){return width()<=0||height()<=0;} }
    static class Path { boolean empty=true; }
    static class ContinuousCorners { static void set(Path p,float l,float t,float r,float b,float radius){p.empty=r<=l||b<=t;} }
    static class Matrix { void setScale(float x,float y){} void postTranslate(float x,float y){} }
    static class Shader { enum TileMode{CLAMP} static int creations; void setLocalMatrix(Matrix matrix){} }
    static class LinearGradient extends Shader {
        LinearGradient(float x,float y,float r,float b,int[] colors,float[] stops,TileMode mode){creations++;}
        LinearGradient(float x,float y,float r,float b,int a,int c,TileMode mode){creations++;}
    }
    static class RadialGradient extends Shader { RadialGradient(float x,float y,float radius,int[] colors,float[] stops,TileMode mode){creations++;} }
    static class Paint { int alpha=255,color;Shader shader;Paint(){}Paint(Paint p){alpha=p.alpha;color=p.color;shader=p.shader;}void setShader(Shader s){shader=s;}Shader getShader(){return shader;}void setAlpha(int a){alpha=a;}int getAlpha(){return alpha;}void setColor(int c){color=c;} }
    static class Bitmap {
        enum Config{ARGB_8888,ALPHA_8} static final int DENSITY_NONE=0;static int allocations;final int width,height;final Config config;Shader baked;
        Bitmap(int w,int h,Config c){width=w;height=h;config=c;allocations++;}
        static Bitmap createBitmap(int w,int h,Config c){return new Bitmap(w,h,c);}int getAllocationByteCount(){return width*height*(config==Config.ARGB_8888?4:1);}void setDensity(int d){}
    }
    static class Canvas {
        final Bitmap target;final List<Bitmap> bitmaps=new ArrayList<>();final List<Integer> alphas=new ArrayList<>();int paths;
        Canvas(){target=null;}Canvas(Bitmap b){target=b;}void translate(float x,float y){}void clipPath(Path p){}int save(){return 1;}void restoreToCount(int count){}
        void drawPath(Path p,Paint paint){paths++;if(target!=null)target.baked=paint.shader;}
        void drawBitmap(Bitmap b,int x,int y,Paint paint){bitmaps.add(b);alphas.add(paint.alpha);}
    }
    static class View { void getLocationOnScreen(int[] point){point[0]=point[1]=0;} }
    static class Color { static final int WHITE=-1,BLACK=0xff000000; }
    __KEY__
    __LIGHTING__
    __LIMIT__
    private static final android.util.LruCache<LightingKey,Lighting> LIGHTING_CACHE=new android.util.LruCache<>(2*1024*1024);
    final float density,radius,screenScale=1f;
    final boolean translucent,hasBackdrop;
    final RectF bounds=new RectF();final Path mask=new Path();final Paint background=new Paint(),surface=new Paint(),focus=new Paint(),shade=new Paint(),tint=new Paint(),selection=new Paint(),raster=new Paint();
    final Paint[] edge={new Paint(),new Paint(),new Paint()};static final int[] EDGE_ALPHA={112,52,24};
    final Matrix matrix=new Matrix();final View tile=new View();final int[] position=new int[2];int sampledX=Integer.MIN_VALUE,sampledY=Integer.MIN_VALUE;
    boolean positionFrozen,whiteFocus=true;float focusAmount=.6f;int alpha=200;Lighting lighting;int lightingLeft,lightingTop;
    ControlLightingTest(float d,float r,boolean t,boolean b){density=d;radius=r;translucent=t;hasBackdrop=b;if(b)background.setShader(new Shader());}
    __BOUNDS__
    __PREPARE__
    __DRAW__
    static void assertDraw(ControlLightingTest material, Lighting expected, String context) {
        Canvas canvas=new Canvas(); material.draw(canvas);
        check(canvas.bitmaps.size()==6,context+" uses six cached layers");
        check(canvas.bitmaps.equals(List.of(expected.layers[0],expected.layers[1],expected.layers[2],expected.layers[5],expected.layers[4],expected.layers[3])),context+" layer identity/order");
        check(canvas.alphas.equals(List.of(255,148,164,14,31,67)),context+" dynamic alpha preserved");
    }
    public static void main(String[] args) {
        Rect box=new Rect(0,0,100,40);
        ControlLightingTest first=new ControlLightingTest(1,12,true,true);first.onBoundsChange(box);
        Lighting cached=first.lighting;
        check(cached!=null&&Bitmap.allocations==6&&Shader.creations==4,"cold miss creates original four shaders/six rasters");
        for(Bitmap bitmap:cached.layers)check(bitmap.baked!=null,"each cached layer retains original shader");
        assertDraw(first,cached,"cold");
        ControlLightingTest second=new ControlLightingTest(1,12,true,true);second.onBoundsChange(box);
        check(second.lighting==cached&&Bitmap.allocations==6&&Shader.creations==4,"reopening hits before shader/raster construction");
        check(second.surface.getShader()==null&&second.focus.getShader()==null,"new cache consumer does not need gradient shaders");
        assertDraw(second,cached,"warm");
        second.onBoundsChange(new Rect(20,30,120,70));
        check(second.lighting==cached&&second.lightingLeft==20&&second.lightingTop==30&&Shader.creations==4,"shifted origin reuses normalized rasters and updates draw origin");
        second.onBoundsChange(new Rect(0,0,110,40));
        check(second.lighting!=cached&&Bitmap.allocations==12&&Shader.creations==8,"size change misses cache");
        ControlLightingTest radius=new ControlLightingTest(1,13,true,true);radius.onBoundsChange(box);
        check(radius.lighting!=cached&&Shader.creations==12,"radius change misses cache");
        ControlLightingTest density=new ControlLightingTest(2,12,true,true);density.onBoundsChange(box);
        check(density.lighting!=cached&&Shader.creations==16,"density change misses cache");
        second.onBoundsChange(new Rect(0,0,1000,1000));
        check(second.lighting==null&&Bitmap.allocations==24&&Shader.creations==20,"oversize clears stale raster and uses original shaders");
        Canvas direct=new Canvas();second.draw(direct);check(direct.bitmaps.isEmpty()&&direct.paths==9,"oversize uses original direct path draw");
        second.onBoundsChange(new Rect(0,0,0,40));check(second.lighting==null&&Bitmap.allocations==24,"zero dimensions do not rasterize");
        second.onBoundsChange(box);check(second.lighting==cached&&Bitmap.allocations==24,"return to eligible dimensions reuses cache");
        ControlLightingTest fallback=new ControlLightingTest(1,12,true,false);int before=Shader.creations;fallback.onBoundsChange(box);
        check(fallback.lighting==cached&&Shader.creations==before+1&&fallback.background.getShader()!=null,"unreadable video keeps fallback background gradient even on cache hit");
        assertDraw(fallback,cached,"fallback");
        ControlLightingTest home=new ControlLightingTest(1,12,false,true);before=Shader.creations;home.onBoundsChange(box);
        check(home.lighting==null&&Shader.creations==before+4,"Home original GPU shaders unchanged");
        LIGHTING_CACHE.evictAll();before=Shader.creations;second.onBoundsChange(box);
        check(second.lighting!=cached&&Shader.creations==before+4&&Bitmap.allocations==30,"trimmed cache reconstructs original shaders and rasters");
        System.out.println("ControlBackdrop: "+checks+" actual-method checks passed");
    }
}
class Drawable { protected void onBoundsChange(ControlLightingTest.Rect rect){} public void draw(ControlLightingTest.Canvas canvas){} }
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = (root / "launcher/app/src/main/java/com/gathin/tvhome/ControlBackdrop.java").read_text(encoding="utf-8")
    substitutions = {
        "__KEY__": extract_method(source, "private static final class LightingKey"),
        "__LIGHTING__": extract_method(source, "private static final class Lighting {"),
        "__LIMIT__": next(line.strip() for line in source.splitlines() if "private static final long MAX_LIGHTING_ENTRY_BYTES =" in line),
        "__BOUNDS__": extract_method(source, "@Override protected void onBoundsChange"),
        "__PREPARE__": extract_method(source, "private Lighting prepareLighting"),
        "__DRAW__": extract_method(source, "@Override public void draw"),
    }
    java = args.java_home.resolve() / "bin"
    with tempfile.TemporaryDirectory(prefix="peachos-control-lighting-") as directory:
        temp = Path(directory)
        harness = HARNESS
        for marker, code in substitutions.items():
            harness = harness.replace(marker, code)
        facades = {
            "ControlLightingTest.java": harness,
            "android/util/LruCache.java": "package android.util; public class LruCache<K,V> { private final java.util.Map<K,V> data=new java.util.HashMap<>();public LruCache(int size){}public V get(K key){return data.get(key);}public void put(K key,V value){data.put(key,value);}public void evictAll(){data.clear();}}",
            "android/util/Log.java": "package android.util;public class Log {public static int d(String tag,String message){return 0;}}",
            "android/os/SystemClock.java": "package android.os;public class SystemClock {public static long uptimeMillis(){return 0;}}",
        }
        paths = []
        for name, contents in facades.items():
            path = temp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding="utf-8")
            paths.append(str(path))
        subprocess.run([str(java / "javac.exe"), "-encoding", "UTF-8", "-d", str(temp), *paths], check=True)
        subprocess.run([str(java / "java.exe"), "-cp", str(temp), "com.gathin.tvhome.ControlLightingTest"], check=True)


if __name__ == "__main__":
    main()
