package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import java.nio.ByteBuffer;

/** One GPU downsample. Only the 160-pixel result reaches the CPU bitmap heap. */
final class ControlScreenshot {
    @androidx.annotation.RequiresApi(30)
    static Bitmap small(AccessibilityService.ScreenshotResult screenshot) {
        try (HardwareBuffer buffer = screenshot.getHardwareBuffer()) {
            Bitmap source = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
            if (source == null) return null;
            int width = 160, height = Math.max(1, Math.round(160f * source.getHeight() / source.getWidth()));
            HardwareRenderer renderer = new HardwareRenderer();
            RenderNode node = new RenderNode("PeachControlSnapshot");
            try (ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_CPU_READ_OFTEN)) {
                node.setPosition(0, 0, width, height);
                android.graphics.RecordingCanvas canvas = node.beginRecording(width, height);
                canvas.drawBitmap(source, null, new Rect(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
                node.endRecording();
                renderer.setSurface(reader.getSurface()); renderer.setContentRoot(node);
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null) return null;
                    Image.Plane plane = image.getPlanes()[0];
                    ByteBuffer input = plane.getBuffer(), pixels = ByteBuffer.allocateDirect(width * height * 4);
                    for (int y = 0; y < height; y++) {
                        input.limit(input.capacity()); input.position(y * plane.getRowStride());
                        input.limit(input.position() + width * 4); pixels.put(input);
                    }
                    pixels.flip();
                    Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    result.copyPixelsFromBuffer(pixels);
                    // Some Android 11/12 firmwares report success while omitting a protected video layer.
                    // Do not cover live video with a captured black rectangle in the controls' region.
                    int[] colors = new int[width * height]; result.getPixels(colors, 0, width, 0, 0, width, height);
                    int readable = 0, sampled = 0;
                    for (int y = height / 8; y < height * 3 / 5; y++) for (int x = width * 7 / 10; x < width; x++) {
                        int rgb = colors[y * width + x];
                        if (((rgb >>> 16) & 255) + ((rgb >>> 8) & 255) + (rgb & 255) > 30) readable++;
                        sampled++;
                    }
                    if (readable * 100 < sampled) { result.recycle(); return null; }
                    return result;
                }
            } finally {
                renderer.destroy(); node.discardDisplayList(); source.recycle();
            }
        }
    }
    private ControlScreenshot() { }
}
