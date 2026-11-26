package com.camerax9p.probe;

import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.TonemapCurve;
import java.util.Arrays;

record CameraAdjustments(int ev, int contrast, int iso, long shutterNs, int flash) {
    static final int OFF = 0, AUTO = 1, ON = 2, TORCH = 3;
    static final CameraAdjustments DEFAULT = new CameraAdjustments(0, 0, 0, 0, OFF);

    static boolean supportsContrast(CameraCharacteristics camera) {
        int[] modes = camera.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
        return modes != null && Arrays.stream(modes).anyMatch(
            mode -> mode == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
    }

    static boolean supportsFlash(CameraCharacteristics camera) {
        return Boolean.TRUE.equals(camera.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
    }

    static CameraAdjustments from(Intent intent) {
        return new CameraAdjustments(intent.getIntExtra("ev", 0), intent.getIntExtra("contrast", 0),
            intent.getIntExtra("isoOverride", 0), intent.getLongExtra("shutterOverride", 0), intent.getIntExtra("flashMode", OFF));
    }

    void writeTo(Intent intent) {
        intent.putExtra("ev", ev).putExtra("contrast", contrast).putExtra("isoOverride", iso)
            .putExtra("shutterOverride", shutterNs).putExtra("flashMode", flash);
    }

    boolean needsPreflash() { return flash == AUTO || flash == ON; }

    CameraAdjustments limitShutter(long limit) {
        return shutterNs <= limit ? this : new CameraAdjustments(ev, contrast, iso, limit, flash);
    }

    void apply(CaptureRequest.Builder request, CameraCharacteristics camera, int fps) {
        int ae = switch (flash) {
            case AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
            case ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            default -> CaptureRequest.CONTROL_AE_MODE_ON;
        };
        request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
        if (iso > 0 && shutterNs > 0 && ActionExposure.supported(camera)) {
            long ceiling = fps > 0 ? 1_000_000_000L / fps : Long.MAX_VALUE;
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                camera.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE).clamp(Math.min(shutterNs, ceiling)));
            request.set(CaptureRequest.SENSOR_SENSITIVITY, camera.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).clamp(iso));
            request.set(CaptureRequest.SENSOR_FRAME_DURATION, fps > 0 ? Math.round(1_000_000_000.0 / fps) : null);
            ae = CaptureRequest.CONTROL_AE_MODE_OFF;
        } else {
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null);
            request.set(CaptureRequest.SENSOR_SENSITIVITY, null);
            request.set(CaptureRequest.SENSOR_FRAME_DURATION, null);
        }
        request.set(CaptureRequest.CONTROL_AE_MODE, ae);
        if (supportsFlash(camera)) request.set(CaptureRequest.FLASH_MODE,
            flash == TORCH ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        if (supportsContrast(camera)) {
            if (contrast == 0) {
                if (request.get(CaptureRequest.TONEMAP_CURVE) != null) {
                    request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST);
                    request.set(CaptureRequest.TONEMAP_CURVE, null);
                }
            } else {
                float shift = contrast * .025f;
                float[] curve = {0f, 0f, .25f, .25f - shift, .5f, .5f, .75f, .75f + shift, 1f, 1f};
                request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
                request.set(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(curve, curve, curve));
            }
        }
    }
}
