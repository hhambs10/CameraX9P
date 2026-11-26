package com.camerax9p.probe;

import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import java.util.Arrays;
import java.util.List;

enum WhiteBalance {
    AUTO(R.string.wb_auto, CaptureRequest.CONTROL_AWB_MODE_AUTO),
    LOCKED(R.string.wb_locked, CaptureRequest.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT(R.string.wb_daylight, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY(R.string.wb_cloudy, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE(R.string.wb_shade, CaptureRequest.CONTROL_AWB_MODE_SHADE),
    INCANDESCENT(R.string.wb_tungsten, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT(R.string.wb_fluorescent, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
    WARM(R.string.wb_warm_fluorescent, CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT),
    TWILIGHT(R.string.wb_twilight, CaptureRequest.CONTROL_AWB_MODE_TWILIGHT);

    private final int label;
    final int mode;

    WhiteBalance(int label, int mode) {
        this.label = label;
        this.mode = mode;
    }

    String label(Context context) { return context.getString(label); }

    static List<WhiteBalance> available(CameraCharacteristics camera) {
        int[] modes = camera.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (modes == null) return List.of(AUTO);
        int[] capabilities = camera.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean canLockAndTransfer = Boolean.TRUE.equals(camera.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE))
            && capabilities != null && Arrays.stream(capabilities).anyMatch(value ->
                value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING);
        return Arrays.stream(values()).filter(choice -> (choice != LOCKED || canLockAndTransfer)
            && Arrays.stream(modes).anyMatch(mode -> mode == choice.mode)).collect(java.util.stream.Collectors.toList());
    }

    void preview(CaptureRequest.Builder request, CameraCharacteristics camera) {
        WhiteBalance choice = available(camera).contains(this) ? this : AUTO;
        request.set(CaptureRequest.CONTROL_AWB_MODE, choice.mode);
        if (Boolean.TRUE.equals(camera.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE))) {
            request.set(CaptureRequest.CONTROL_AWB_LOCK, choice == LOCKED);
        }
    }

    static void record(Intent config, CaptureRequest.Builder request) {
        if (!config.hasExtra("awbMode")) return;
        float[] gains = config.getFloatArrayExtra("awbGains");
        int[] transform = config.getIntArrayExtra("awbTransform");
        if (gains != null && transform != null) {
            request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
            request.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            request.set(CaptureRequest.COLOR_CORRECTION_GAINS,
                new RggbChannelVector(gains[0], gains[1], gains[2], gains[3]));
            request.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, new ColorSpaceTransform(transform));
        } else {
            request.set(CaptureRequest.CONTROL_AWB_MODE, config.getIntExtra("awbMode", AUTO.mode));
        }
    }

    record Snapshot(RggbChannelVector gains, ColorSpaceTransform transform) {
        static Snapshot locked(CaptureResult result) {
            Integer state = result.get(CaptureResult.CONTROL_AWB_STATE);
            if (state == null || state != CaptureResult.CONTROL_AWB_STATE_LOCKED) return null;
            RggbChannelVector gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
            ColorSpaceTransform transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
            return gains == null || transform == null ? null : new Snapshot(gains, transform);
        }

        void write(Intent config) {
            float[] channels = new float[4];
            int[] coefficients = new int[18];
            gains.copyTo(channels, 0);
            transform.copyElements(coefficients, 0);
            config.putExtra("awbGains", channels).putExtra("awbTransform", coefficients);
        }
    }
}
