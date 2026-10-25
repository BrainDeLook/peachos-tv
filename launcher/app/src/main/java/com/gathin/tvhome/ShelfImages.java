package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/** One worker calls load; UI calls peek/trim. Bounded decoded cache, disk thumbnails 32 MiB. */
final class ShelfImages {
    private final Context context;
    private final ShelfDiskCache disk;
    private final LruCache<String, Bitmap> memory;
    private final java.util.concurrent.atomic.AtomicInteger network = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger diskHits = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger memoryHits = new java.util.concurrent.atomic.AtomicInteger();
    private final LruCache<String, Long> failures = new LruCache<>(64);
    ShelfImages(Context context) {
        this.context = context.getApplicationContext();
        android.app.ActivityManager manager = context.getSystemService(android.app.ActivityManager.class);
        // Two nearby rows exceed 2 MiB and evict each other on every app switch. Another 2 MiB
        // avoids repeated disk reads/decodes on ordinary devices; retain the smaller low-RAM budget.
        int budget = manager != null && manager.isLowRamDevice() ? 2 : 4;
        memory = new LruCache<String, Bitmap>(budget * 1024 * 1024) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
        };
        disk = new ShelfDiskCache(new File(context.getCacheDir(), "shelf-thumbnails"), 32L * 1024 * 1024);
    }
    Bitmap peek(String url) { Bitmap found = memory.get(url); if (found != null) memoryHits.incrementAndGet(); return found; }
    void trim() { memory.evictAll(); }
    void stats() { Log.i("ShelfImages", "network=" + network + " diskHits=" + diskHits + " memoryHits=" + memoryHits); }

    Bitmap load(String url) {
        Bitmap bitmap = memory.get(url);
        if (bitmap != null) { memoryHits.incrementAndGet(); return bitmap; }
        Long failed = failures.get(url);
        if (failed != null && android.os.SystemClock.elapsedRealtime() - failed < 60_000) return null;
        byte[] cached = disk.get(url, System.currentTimeMillis());
        if (cached != null) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            bitmap = BitmapFactory.decodeByteArray(cached, 0, cached.length, options);
            if (bitmap != null) { diskHits.incrementAndGet(); memory.put(url, bitmap); return bitmap; }
        }
        try {
            byte[] bytes;
            if (url.startsWith("https://") || url.startsWith("http://")) {
                network.incrementAndGet();
                bytes = Http.get(url, 4 * 1024 * 1024);
            } else if (url.startsWith("content://") || url.startsWith("android.resource://")) {
                try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(url))) {
                    if (in == null) return null;
                    bytes = Http.readAll(in, 4 * 1024 * 1024);
                }
            } else return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            while (bounds.outWidth / options.inSampleSize > 1024 || bounds.outHeight / options.inSampleSize > 1024) options.inSampleSize *= 2;
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) return null;
            int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longest > 512) {
                Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * 512 / longest),
                        Math.max(1, bitmap.getHeight() * 512 / longest), true);
                if (small != bitmap) bitmap.recycle();
                bitmap = small;
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, encoded);
            disk.put(url, encoded.toByteArray(), System.currentTimeMillis());
            memory.put(url, bitmap);
            return bitmap;
        } catch (Exception e) {
            failures.put(url, android.os.SystemClock.elapsedRealtime());
            // Hosts only: private artwork URLs may include credentials.
            Uri uri = Uri.parse(url);
            Log.w("ShelfImages", "Unavailable via " + uri.getScheme() + "://" + uri.getHost()
                    + ": " + e.getClass().getSimpleName());
            return null;
        }
    }
}
