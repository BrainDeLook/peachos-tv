package com.gathin.tvhome;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** URL-keyed encoded thumbnails only. No app databases, titles, original images or unlimited growth. */
final class ShelfDiskCache {
    private static final long MAX_AGE = 3L * 24 * 60 * 60 * 1000;
    private static final int MAX_FILE = 1024 * 1024;
    private final File root;
    private final long limit;
    ShelfDiskCache(File root, long limit) { this.root = root; this.limit = limit; }

    byte[] get(String url, long now) {
        File file = file(url);
        if (!file.isFile()) return null;
        try {
            if (file.length() > MAX_FILE || now - file.lastModified() > MAX_AGE) {
                file.delete(); return null;
            }
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) { return null; }
    }
    void put(String url, byte[] bytes, long now) {
        if (bytes.length > MAX_FILE || bytes.length > limit) return;
        root.mkdirs();
        File temporary = null;
        try {
            temporary = File.createTempFile("thumb", ".tmp", root);
            Files.write(temporary.toPath(), bytes);
            File target = file(url);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            target.setLastModified(now);
            prune();
        } catch (IOException ignored) {
            if (temporary != null) temporary.delete();
        }
    }
    private File file(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return new File(root, name + ".jpg");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void prune() {
        File[] files = root.listFiles((dir, name) -> name.endsWith(".jpg") || name.endsWith(".tmp"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total <= limit) break;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }
}
