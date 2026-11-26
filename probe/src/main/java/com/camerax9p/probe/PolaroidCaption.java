package com.camerax9p.probe;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

final class PolaroidCaption {
    private PolaroidCaption() {}

    static void draw(Canvas canvas, String text, int left, int top, int right, int bottom) {
        if (text.isEmpty() || top >= bottom) return;
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(0xff27221f);
        ink.setTypeface(Typeface.create("casual", Typeface.NORMAL));
        ink.setTextAlign(Paint.Align.CENTER);
        ink.setTextSize(Math.min((bottom - top) * .4f, (right - left) * .065f));
        float available = (right - left) * .88f;
        float measured = ink.measureText(text);
        if (measured > available) ink.setTextSize(ink.getTextSize() * available / measured);
        float baseline = (top + bottom - ink.ascent() - ink.descent()) / 2;
        canvas.drawText(text, (left + right) / 2f, baseline, ink);
    }
}
