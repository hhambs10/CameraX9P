package com.camerax9p.probe;

import android.hardware.camera2.CameraCharacteristics;
import android.util.Range;
import java.util.ArrayList;
import java.util.List;

record ExposureBracket(int ev, long timeNs, int iso) {
    static List<ExposureBracket> plan(CameraCharacteristics camera, long timeNs, int iso) {
        Range<Long> times = camera.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> sensitivities = camera.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (times == null || sensitivities == null || timeNs <= 0 || iso <= 0) return List.of();
        List<ExposureBracket> result = new ArrayList<>(3);
        for (int ev : new int[]{-2, 0, 2}) {
            double scale = Math.scalb(1.0, ev);
            double target = timeNs * (double) iso * scale;
            long exposure = times.clamp(Math.round(timeNs * scale));
            int sensitivity = sensitivities.clamp((int) Math.round(target / exposure));
            if (Math.abs(Math.log(exposure * (double) sensitivity / target) / Math.log(2)) > .12) return List.of();
            result.add(new ExposureBracket(ev, exposure, sensitivity));
        }
        return result;
    }
}
