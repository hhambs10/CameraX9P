package com.camerax9p.probe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.View;

/** A roll indicator for the live viewfinder. */
final class LevelOverlay extends View implements SensorEventListener {
    private final SensorManager sensors;
    private final Sensor sensor;
    private final int activeColor;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect frame = new Rect();
    private boolean running, hasSample, level;
    private float gravityX, gravityY, roll;

    LevelOverlay(Context context, int activeColor) {
        super(context);
        this.activeColor = activeColor;
        sensors = context.getSystemService(SensorManager.class);
        Sensor gravity = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensor = gravity != null || sensors == null ? gravity : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setVisibility(GONE);
    }

    boolean supported() { return sensor != null; }

    void setFrame(Rect bounds) {
        frame.set(bounds);
        invalidate();
    }

    void setRunning(boolean enabled) {
        if (enabled == running || !supported()) return;
        if (enabled) {
            running = sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
        } else {
            sensors.unregisterListener(this);
            running = false;
            hasSample = false;
            level = false;
            setVisibility(GONE);
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!running) return;
        float blend = hasSample && sensor.getType() == Sensor.TYPE_ACCELEROMETER ? .2f : 1f;
        gravityX += blend * (event.values[0] - gravityX);
        gravityY += blend * (event.values[1] - gravityY);
        hasSample = true;
        if (Math.hypot(gravityX, gravityY) < .18 * SensorManager.GRAVITY_EARTH) {
            level = false;
            setVisibility(GONE);
            return;
        }
        roll = (float) Math.IEEEremainder(Math.toDegrees(Math.atan2(gravityX, gravityY))
            + LivePreview.displayRotation(this), 180);
        boolean aligned = Math.abs(roll) <= (level ? 1.6f : 1f);
        if (aligned != level || getVisibility() != VISIBLE) {
            setContentDescription(aligned ? getContext().getString(R.string.level_aligned) : getContext().getString(R.string.level_tilted));
        }
        level = aligned;
        setVisibility(VISIBLE);
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (frame.isEmpty()) return;
        float density = getResources().getDisplayMetrics().density;
        float halfLength = Math.min(84 * density, frame.width() * .4f);
        int restore = canvas.save();
        canvas.clipRect(frame);
        canvas.translate(frame.exactCenterX(), frame.exactCenterY());
        if (!level) canvas.rotate(roll);
        paint.setColor(0x9a09111c);
        paint.setStrokeWidth(4 * density);
        canvas.drawLine(-halfLength, 0, halfLength, 0, paint);
        if (level) {
            paint.setColor(0x6634bfff);
            paint.setStrokeWidth(6 * density);
            canvas.drawLine(-halfLength, 0, halfLength, 0, paint);
        }
        paint.setColor(level ? activeColor : 0xccebf0f5);
        paint.setStrokeWidth(2 * density);
        canvas.drawLine(-halfLength, 0, halfLength, 0, paint);
        canvas.restoreToCount(restore);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
