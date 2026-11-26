package com.camerax9p.probe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.SeekBar;
import java.util.function.IntConsumer;

/** A zoom scale with native SeekBar accessibility and a vertical layout for landscape. */
final class ZoomRuler extends SeekBar {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean vertical;
    private final int accent;
    private final float inset;
    private final float density;
    private final IntConsumer onDrag;
    private boolean dragging;

    ZoomRuler(Context context, boolean vertical, int accent, IntConsumer onDrag) {
        super(context);
        this.vertical = vertical;
        this.accent = accent;
        this.onDrag = onDrag;
        density = getResources().getDisplayMetrics().density;
        inset = 12 * density;
        setMax(1000);
        setKeyProgressIncrement(40);
        setBackground(null);
        setSplitTrack(false);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        float length = trackLength();
        float center = (vertical ? getWidth() : getHeight()) / 2f;
        for (int tick = 0; tick <= 20; tick++) {
            float at = inset + length * tick / 20;
            float radius = (tick % 5 == 0 ? 8 : 4) * density;
            paint.setColor(tick % 5 == 0 ? 0xffc4cfdd : 0xff8392a5);
            paint.setStrokeWidth(density);
            drawTick(canvas, at, center, radius);
        }
        float fraction = getProgress() / (float) getMax();
        paint.setColor(accent);
        paint.setStrokeWidth(2.5f * density);
        drawTick(canvas, inset + length * (vertical ? 1 - fraction : fraction), center, 12 * density);
    }

    private void drawTick(Canvas canvas, float at, float center, float radius) {
        if (vertical) canvas.drawLine(center - radius, at, center + radius, at, paint);
        else canvas.drawLine(at, center - radius, at, center + radius, paint);
    }

    private float trackLength() {
        return Math.max(1, (vertical ? getHeight() : getWidth()) - 2 * inset);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                setPressed(true);
                trackTouch(event);
            }
            case MotionEvent.ACTION_MOVE -> trackTouch(event);
            case MotionEvent.ACTION_UP -> {
                trackTouch(event);
                endDrag();
                performClick();
            }
            case MotionEvent.ACTION_CANCEL -> endDrag();
            default -> { }
        }
        return true;
    }

    private void trackTouch(MotionEvent event) {
        float position = vertical ? getHeight() - event.getY() : event.getX();
        int progress = Math.round((position - inset) / trackLength() * getMax());
        onDrag.accept(Math.max(0, Math.min(getMax(), progress)));
    }

    private void endDrag() {
        dragging = false;
        setPressed(false);
        getParent().requestDisallowInterceptTouchEvent(false);
    }

    boolean isDragging() { return dragging; }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (vertical) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) key = KeyEvent.KEYCODE_DPAD_RIGHT;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) key = KeyEvent.KEYCODE_DPAD_LEFT;
        }
        return super.onKeyDown(key, event);
    }
}
