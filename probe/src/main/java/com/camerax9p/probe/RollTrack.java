package com.camerax9p.probe;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class RollTrack implements SensorEventListener, AutoCloseable {
    private final SensorManager sensors;
    private final List<Sample> samples = new ArrayList<>();
    private final float[] matrix = new float[9];
    private record Sample(long timeNs, double radians) {}

    private static Sensor availableSensor(SensorManager sensors) {
        Sensor sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        return sensor == null ? sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : sensor;
    }

    static boolean supported(Context context) {
        SensorManager manager = context.getSystemService(SensorManager.class);
        return manager != null && availableSensor(manager) != null;
    }

    RollTrack(Context context) throws IOException {
        sensors = context.getSystemService(SensorManager.class);
        Sensor sensor = sensors == null ? null : availableSensor(sensors);
        if (sensor == null || !sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME,
                new Handler(Looper.getMainLooper()))) throw new IOException(context.getString(R.string.rotation_sensor_unavailable));
    }

    @Override public synchronized void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values);
        double x = matrix[6], y = matrix[7];
        if (Math.hypot(x, y) < .18) return;
        double angle = Math.atan2(x, y);
        if (!samples.isEmpty()) {
            Sample previous = samples.get(samples.size() - 1);
            if (event.timestamp <= previous.timeNs) return;
            angle = previous.radians + Math.IEEEremainder(angle - previous.radians, 2 * Math.PI);
        }
        samples.add(new Sample(event.timestamp, angle));
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override public void close() { sensors.unregisterListener(this); }

    synchronized Timeline timeline(long startNs) throws IOException {
        if (samples.size() < 2 || startNs <= 0) throw new IOException("Traccia di rotazione insufficiente");
        return new Timeline(startNs, List.copyOf(samples));
    }

    static final class Timeline {
        private final long startNs;
        private final List<Sample> samples;
        private final double initialRoll;

        private Timeline(long startNs, List<Sample> samples) {
            this.startNs = startNs;
            this.samples = samples;
            initialRoll = at(startNs);
        }

        double degreesAt(long timeUs) { return Math.toDegrees(at(startNs + timeUs * 1000) - initialRoll); }

        double degreesCovered(long durationMs) {
            double min = initialRoll, max = initialRoll;
            long endNs = startNs + durationMs * 1_000_000;
            for (Sample sample : samples) {
                if (sample.timeNs < startNs || sample.timeNs > endNs) continue;
                min = Math.min(min, sample.radians);
                max = Math.max(max, sample.radians);
            }
            return Math.toDegrees(max - min);
        }

        private double at(long timeNs) {
            if (timeNs <= samples.get(0).timeNs) return samples.get(0).radians;
            int left = 0, right = samples.size() - 1;
            if (timeNs >= samples.get(right).timeNs) return samples.get(right).radians;
            while (right - left > 1) {
                int middle = (left + right) >>> 1;
                if (samples.get(middle).timeNs <= timeNs) left = middle;
                else right = middle;
            }
            Sample a = samples.get(left), b = samples.get(right);
            double fraction = (double) (timeNs - a.timeNs) / (b.timeNs - a.timeNs);
            return a.radians + (b.radians - a.radians) * fraction;
        }
    }
}
