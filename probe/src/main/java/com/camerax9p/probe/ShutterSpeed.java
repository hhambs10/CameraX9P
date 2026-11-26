package com.camerax9p.probe;

import android.hardware.camera2.CameraCharacteristics;
import android.util.Range;
import java.util.ArrayList;
import java.util.List;

record ShutterSpeed(long nanos, String label) {
    private static final int[] DENOMINATORS = {8000, 4000, 2000, 1000, 500, 250, 120, 60, 30, 15, 8, 4, 2};
    private static final int[] SECONDS = {1, 2, 4, 8, 15};

    static List<ShutterSpeed> choices(CameraCharacteristics camera, int fps, boolean allowThirtySeconds) {
        Range<Long> sensor = camera.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        long ceiling = fps > 0 ? 1_000_000_000L / fps : Long.MAX_VALUE;
        List<ShutterSpeed> choices = new ArrayList<>();
        choices.add(new ShutterSpeed(0, "Auto"));
        for (int divisor : DENOMINATORS) {
            add(choices, sensor, ceiling, 1_000_000_000L / divisor, "1/" + divisor + " s");
        }
        for (int seconds : SECONDS) {
            add(choices, sensor, ceiling, 1_000_000_000L * seconds, seconds + " s");
        }
        if (allowThirtySeconds) add(choices, sensor, ceiling, 30_000_000_000L, "30 s");
        return choices;
    }

    private static void add(List<ShutterSpeed> choices, Range<Long> sensor, long ceiling, long value, String label) {
        if (sensor != null && value <= ceiling && sensor.contains(value)) choices.add(new ShutterSpeed(value, label));
    }
}
