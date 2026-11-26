package com.camerax9p.probe;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.util.Range;
import android.util.Size;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

enum VideoOptions {
    UHD(Format.WIDE, "4K", 3840, 2160),
    WIDE_GATE(Format.FOUR_THIRDS, "3840×2880", 3840, 2880),
    FULL_GATE(Format.OG, "4096×3072", 4096, 3072),
    FULL_HD(Format.WIDE, "1080p", 1920, 1080),
    HD(Format.WIDE, "720p", 1280, 720);

    enum Format {
        WIDE(R.string.format_wide), FOUR_THIRDS(R.string.format_four_thirds), OG(R.string.mode_og);

        private final int label;
        Format(int label) { this.label = label; }
        String label(Context context) { return context.getString(label); }
    }

    final Format format;
    final String label, description;
    final int width, height;

    VideoOptions(Format format, String label, int width, int height) {
        this.format = format;
        this.label = label;
        this.description = format == Format.WIDE ? label + " · " + width + "×" + height : label;
        this.width = width;
        this.height = height;
    }

    static List<VideoOptions> sizes(CameraCharacteristics camera, String id) {
        List<VideoOptions> sizes = new ArrayList<>();
        for (VideoOptions option : List.of(UHD, FULL_HD, HD, WIDE_GATE, FULL_GATE)) {
            if (option == FULL_GATE && !id.equals("0")) continue;
            if (option.hasSize(camera)) sizes.add(option);
        }
        return sizes;
    }

    private boolean hasSize(CameraCharacteristics camera) {
        StreamConfigurationMap map = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(MediaRecorder.class);
        return sizes != null && Arrays.asList(sizes).contains(new Size(width, height));
    }

    double aspect() { return (double) width / height; }

    List<Integer> rates(CameraCharacteristics camera) {
        if (!hasSize(camera)) return List.of();
        if (height > 2160) return List.of(30);
        List<Integer> rates = new ArrayList<>();
        Range<Integer>[] regular = camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        for (int fps : new int[]{30, 60}) {
            if (regular != null && Arrays.asList(regular).contains(new Range<>(fps, fps))) rates.add(fps);
        }
        if (hasHighSpeed(camera, width, height, 120)) rates.add(120);
        return rates;
    }

    static boolean hasHighSpeed(CameraCharacteristics camera, int width, int height, int fps) {
        StreamConfigurationMap map = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return false;
        Size size = new Size(width, height);
        if (!Arrays.asList(map.getHighSpeedVideoSizes()).contains(size)) return false;
        return Arrays.asList(map.getHighSpeedVideoFpsRangesFor(size)).contains(new Range<>(fps, fps));
    }
}
