package com.gathin.tvhome;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ShelfDiskCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void survivesANewCacheInstanceAndUsesWholeUrl() throws Exception {
        java.io.File root = temporary.newFolder();
        ShelfDiskCache first = new ShelfDiskCache(root, 1024);
        first.put("https://example/image?version=1", new byte[]{1,2,3}, 1000);
        ShelfDiskCache second = new ShelfDiskCache(root, 1024);
        assertArrayEquals(new byte[]{1,2,3}, second.get("https://example/image?version=1", 2000));
        assertNull(second.get("https://example/image?version=2", 2000));
    }
    @Test public void enforcesDiskBudgetAndExpiresOldEntries() throws Exception {
        java.io.File root = temporary.newFolder();
        ShelfDiskCache cache = new ShelfDiskCache(root, 6);
        cache.put("old", new byte[]{1,2,3,4}, 1000);
        cache.put("new", new byte[]{5,6,7,8}, 2000);
        assertNull(cache.get("old", 3000));
        assertArrayEquals(new byte[]{5,6,7,8}, cache.get("new", 3000));
        assertNull(cache.get("new", 4L * 24 * 60 * 60 * 1000));
    }
}
