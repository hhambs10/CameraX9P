package com.camerax9p.probe;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import java.util.Arrays;

record FocusPoint(float x, float y) {
    static boolean supported(CameraCharacteristics camera) {
        Integer regions = camera.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        int[] modes = camera.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        return regions != null && regions > 0 && modes != null
            && Arrays.stream(modes).anyMatch(mode -> mode == CaptureRequest.CONTROL_AF_MODE_AUTO);
    }

    void apply(CaptureRequest.Builder request, Rect activeSensor, int mode) {
        int width = Math.max(1, activeSensor.width() / 10);
        int height = Math.max(1, activeSensor.height() / 10);
        int left = Math.max(activeSensor.left, Math.min(activeSensor.right - width,
            activeSensor.left + Math.round(activeSensor.width() * x) - width / 2));
        int top = Math.max(activeSensor.top, Math.min(activeSensor.bottom - height,
            activeSensor.top + Math.round(activeSensor.height() * y) - height / 2));
        request.set(CaptureRequest.CONTROL_AF_MODE, mode);
        request.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{
            new MeteringRectangle(left, top, width, height, MeteringRectangle.METERING_WEIGHT_MAX)
        });
    }

    static boolean isTerminal(Integer state) {
        return state != null && (state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
            || state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
    }
}
