package com.camerax9p.probe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.TextureView;
import android.view.View;

/** Optional SDR preview aid. Samples a small copy of the displayed image a few times a second. */
final class ZebraOverlay extends View {
    private static final int SAMPLE_LONG_EDGE = 160;
    private static final int LUMA_95_PERCENT = 242;
    private final TextureView preview;
    private final Rect frame = new Rect();
    private final Rect destination = new Rect();
    private final Paint paint = new Paint();
    private Bitmap sample;
    private Bitmap mask;
    private int[] pixels;
    private boolean running;

    ZebraOverlay(TextureView preview) {
        super(preview.getContext());
        this.preview = preview;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private final Runnable sampleNext = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refreshMask();
            postDelayed(this, 200);
        }
    };

    void setRunning(boolean enabled) {
        if (running == enabled) return;
        running = enabled;
        if (enabled) post(sampleNext);
        else {
            removeCallbacks(sampleNext);
            invalidate();
        }
    }

    void setFrame(Rect bounds) {
        frame.set(bounds);
        invalidate();
    }

    private void refreshMask() {
        int fullWidth = preview.getWidth(), fullHeight = preview.getHeight();
        if (!preview.isAvailable() || fullWidth <= 0 || fullHeight <= 0 || frame.isEmpty()) return;
        float scale = SAMPLE_LONG_EDGE / (float) Math.max(fullWidth, fullHeight);
        int width = Math.max(1, Math.round(fullWidth * scale)), height = Math.max(1, Math.round(fullHeight * scale));
        if (sample == null || sample.getWidth() != width || sample.getHeight() != height) {
            sample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            pixels = new int[width * height];
        }
        if (preview.getBitmap(sample) == null) return;
        sample.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int pixel = pixels[index];
                int luma = (54 * Color.red(pixel) + 183 * Color.green(pixel) + 19 * Color.blue(pixel)) >> 8;
                pixels[index] = luma >= LUMA_95_PERCENT && (x + y) % 6 < 2 ? 0xd9000000 : Color.TRANSPARENT;
            }
        }
        mask.setPixels(pixels, 0, width, 0, 0, width, height);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!running || mask == null) return;
        canvas.save();
        canvas.clipRect(frame);
        destination.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(mask, null, destination, paint);
        canvas.restore();
    }

    @Override protected void onDetachedFromWindow() {
        setRunning(false);
        super.onDetachedFromWindow();
    }
}
