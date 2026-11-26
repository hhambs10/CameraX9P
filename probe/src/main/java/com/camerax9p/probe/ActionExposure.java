package com.camerax9p.probe;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import java.util.Arrays;

record ActionExposure(long timeNs, int iso) {
    private static final long ONE_MILLISECOND_NS = 1_000_000L;

    static boolean supported(CameraCharacteristics camera) {
        int[] capabilities = camera.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        return capabilities != null && Arrays.stream(capabilities).anyMatch(capability ->
            capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            && camera.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) != null
            && camera.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) != null;
    }

    void apply(CaptureRequest.Builder request, CameraCharacteristics camera, int fps) {
        Range<Long> availableTimes = camera.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> availableIso = camera.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (availableTimes == null || availableIso == null || timeNs <= 0 || iso <= 0) {
            throw new IllegalArgumentException("Action exposure is unavailable");
        }
        long shutter = availableTimes.clamp(Math.min(timeNs, ONE_MILLISECOND_NS));
        int estimatedIso = (int) Math.min(Integer.MAX_VALUE, Math.round((double) timeNs * iso / shutter));
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter);
        request.set(CaptureRequest.SENSOR_SENSITIVITY, availableIso.clamp(estimatedIso));
        request.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.round(1_000_000_000.0 / fps));
    }
}
