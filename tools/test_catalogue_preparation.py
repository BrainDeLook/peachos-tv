#!/usr/bin/env python3
"""Exercise AppAdapter's actual visible-row preparation and first-focus colour lookup."""
import argparse
from pathlib import Path
import subprocess
import tempfile
from test_control_network import extract_method


HARNESS = r'''
package com.gathin.tvhome;
import java.util.*;
import java.util.concurrent.CancellationException;
public class CataloguePreparationTest {
    static int checks, vividCalls, dominantCalls, backdropCalls;
    static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    static class Drawable {
        final int color;
        Drawable(int color) { this.color = color; }
    }
    static class Bitmap { int preparations; void prepareToDraw() { preparations++; } }
    static class BitmapDrawable extends Drawable {
        final Bitmap bitmap = new Bitmap();
        BitmapDrawable(int color) { super(color); }
        Bitmap getBitmap() { return bitmap; }
    }
    static class Entry {
        static final int EMPTY = 3;
        int kind, ambient; int[] backdrop; Drawable banner, icon;
        Entry(Drawable banner, Drawable icon, int ambient) {
            this.banner = banner; this.icon = icon; this.ambient = ambient;
        }
    }
    static class Adapter {
        final boolean uniformTiles;
        final List<Entry> items;
        Adapter(boolean uniformTiles, Entry... entries) {
            this.uniformTiles = uniformTiles; items = Arrays.asList(entries);
        }
        static int vividColor(Drawable art) {
            vividCalls++; return art == null ? 0xff444444 : art.color;
        }
        static int dominantColor(Drawable art) {
            dominantCalls++; return art == null ? 0xff6e6e73 : art.color ^ 0x00010101;
        }
        static int[] backdrop(Drawable art, int color) {
            backdropCalls++; return new int[]{color, color};
        }
        void prepare(int visible) {
            List<Entry> fresh = items;
            PREPARE_LOOP
        }
        LOOKUP
    }
    public static void main(String[] args) {
        BitmapDrawable banner = new BitmapDrawable(0xff112233);
        BitmapDrawable icon = new BitmapDrawable(0xff445566);
        Entry app = new Entry(banner, icon, 0);
        Entry card = new Entry(banner, null, 0xffabcdef);
        Entry empty = new Entry(null, null, 0); empty.kind = Entry.EMPTY;
        Entry offscreen = new Entry(null, icon, 0);
        Adapter ordinary = new Adapter(false, app, card, empty, offscreen);
        ordinary.prepare(3);
        check(vividCalls == 1 && dominantCalls == 0, "sample only a visible unprepared banner");
        check(app.ambient == banner.color, "banner remains the colour source");
        check(card.ambient == 0xffabcdef, "preserve the publisher's explicit card accent");
        check(empty.ambient == 0 && offscreen.ambient == 0, "no sampling empty slots or offscreen apps");
        check(banner.bitmap.preparations == 2 && icon.bitmap.preparations == 0,
                "only existing visible artwork is prepared for GPU upload");
        int sampled = vividCalls + dominantCalls;
        check(ordinary.ambientColorAt(0) == banner.color, "first focus gets the same prepared colour");
        check(ordinary.ambientColorAt(1) == card.ambient && sampled == vividCalls + dominantCalls,
                "prepared entries never rasterize again on focus");
        check(ordinary.ambientColorAt(3) == icon.color && vividCalls == 2,
                "offscreen ordinary icon keeps the lazy vivid-colour fallback");
        check(ordinary.ambientColorAt(-1) == 0xff444444 && ordinary.ambientColorAt(4) == 0xff444444,
                "invalid positions keep the neutral fallback");
        Entry uniformIcon = new Entry(null, icon, 0);
        Entry uniformBanner = new Entry(banner, null, 0);
        Entry uniformCard = new Entry(null, icon, 0xff987654);
        Adapter uniform = new Adapter(true, uniformIcon, uniformBanner, uniformCard);
        uniform.prepare(3);
        check(dominantCalls == 1 && vividCalls == 3, "uniform icons retain dominant sampling; banners retain vivid sampling");
        check(uniformIcon.ambient == (icon.color ^ 0x00010101), "uniform colour is unchanged");
        check(uniformIcon.backdrop[0] == uniformIcon.ambient && uniformCard.backdrop[0] == uniformCard.ambient,
                "uniform fill uses the prepared colour or explicit card accent");
        check(backdropCalls == 2 && uniformBanner.backdrop == null, "no uniform fill is introduced under banners");
        sampled = vividCalls + dominantCalls;
        uniform.ambientColorAt(0); uniform.ambientColorAt(1); uniform.ambientColorAt(2);
        check(sampled == vividCalls + dominantCalls, "all visible uniform entries avoid sampling on first focus");
        Entry cancelled = new Entry(banner, null, 0);
        Thread.currentThread().interrupt();
        try {
            new Adapter(false, cancelled).prepare(1);
            throw new AssertionError("cancelled catalogue must stop before sampling");
        } catch (CancellationException expected) {
            check(cancelled.ambient == 0, "cancelled work does not prepare another entry");
        } finally { Thread.interrupted(); }
        System.out.println("PASS " + checks + " actual-source catalogue preparation checks");
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = (root / 'launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java').read_text(encoding='utf-8')
    load = extract_method(source, 'Snapshot load(')
    prepare = extract_method(load[load.index('int visible ='):], 'for (int i = 0; i < visible; i++)')
    lookup = extract_method(source, 'int ambientColorAt(')
    harness = HARNESS.replace('PREPARE_LOOP', prepare).replace('LOOKUP', lookup)
    with tempfile.TemporaryDirectory(prefix='peach-catalogue-') as folder:
        target = Path(folder) / 'CataloguePreparationTest.java'
        target.write_text(harness, encoding='utf-8')
        java = args.java_home.resolve() / 'bin'
        subprocess.run([str(java / 'javac'), '-encoding', 'UTF-8', '-d', folder, str(target)], check=True)
        subprocess.run([str(java / 'java'), '-cp', folder, 'com.gathin.tvhome.CataloguePreparationTest'], check=True)


if __name__ == '__main__':
    main()
