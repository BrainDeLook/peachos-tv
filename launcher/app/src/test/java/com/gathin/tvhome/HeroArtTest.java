package com.gathin.tvhome;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class HeroArtTest {
    @Test public void slidingBlurIsPixelIdenticalToTheOriginalClampedPasses() {
        Random random = new Random(78631);
        for (int width : new int[]{1, 2, 3, 7, HeroArt.W}) {
            for (int height : new int[]{1, 2, 5, HeroArt.H}) {
                int[] pixels = new int[width * height];
                for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
                for (int radius : new int[]{0, 1, 2, 4}) {
                    int[] expected = pixels.clone(), actual = pixels.clone();
                    int[] scratch = new int[pixels.length];
                    for (int pass = 0; pass < 3; pass++) {
                        expected = original(expected, width, height, radius, true);
                        HeroArt.boxBlur(actual, scratch, width, height, radius, true);
                        assertArrayEquals(expected, scratch);
                        expected = original(expected, width, height, radius, false);
                        HeroArt.boxBlur(scratch, actual, width, height, radius, false);
                        assertArrayEquals(expected, actual);
                    }
                }
            }
        }
    }

    /** Previous five-tap implementation, retained only as the visual-equivalence oracle. */
    private static int[] original(int[] src, int width, int height, int radius, boolean horizontal) {
        int[] dst = new int[src.length];
        int lines = horizontal ? height : width, length = horizontal ? width : height;
        int span = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int j = Math.max(0, Math.min(length - 1, i + k));
                    int p = horizontal ? src[line * width + j] : src[j * width + line];
                    r += p >>> 16 & 255; g += p >>> 8 & 255; b += p & 255;
                }
                dst[horizontal ? line * width + i : i * width + line] =
                        0xff000000 | (r / span << 16) | (g / span << 8) | b / span;
            }
        }
        return dst;
    }
}
