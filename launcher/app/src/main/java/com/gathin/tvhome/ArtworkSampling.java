package com.gathin.tvhome;

/** Decode only the resolution needed to fill the destination, retaining enough pixels for its crop. */
final class ArtworkSampling {
    static int forTarget(int width, int height, int targetWidth, int targetHeight) {
        int sample = 1;
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2;
        return sample;
    }

    private ArtworkSampling() { }
}
