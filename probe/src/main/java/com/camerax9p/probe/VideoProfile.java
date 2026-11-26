package com.camerax9p.probe;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import java.util.List;
import java.util.stream.Collectors;

enum VideoProfile {
    STANDARD(R.string.profile_standard, R.drawable.ic_profile_standard),
    SLOW(R.string.profile_slow, R.drawable.ic_profile_slow),
    SUPER_SLOW(R.string.profile_super_slow, R.drawable.ic_profile_super_slow),
    ACTION(R.string.profile_action, R.drawable.ic_profile_action),
    HORIZON(R.string.horizon_lock, R.drawable.ic_profile_horizon),
    TIMELAPSE(R.string.profile_time_lapse, R.drawable.ic_profile_lapse);

    private final int label;
    final int icon;

    VideoProfile(int label, int icon) {
        this.label = label;
        this.icon = icon;
    }

    String label(Context context) { return context.getString(label); }

    boolean isSlowMotion() { return this == SLOW || this == SUPER_SLOW; }
    boolean isManualRecording() { return !isSlowMotion(); }

    List<VideoOptions> sizes(CameraCharacteristics camera, String id) {
        List<VideoOptions> candidates = switch (this) {
            case STANDARD -> VideoOptions.sizes(camera, id);
            case ACTION -> ActionExposure.supported(camera) ? List.of(VideoOptions.UHD, VideoOptions.FULL_HD) : List.of();
            case SLOW -> List.of(VideoOptions.UHD, VideoOptions.FULL_HD);
            case SUPER_SLOW -> id.equals("0") ? List.of(VideoOptions.FULL_HD, VideoOptions.HD) : List.of(VideoOptions.FULL_HD);
            case HORIZON -> id.equals("1") ? List.of() : List.of(VideoOptions.UHD);
            case TIMELAPSE -> List.of(VideoOptions.FULL_HD, VideoOptions.UHD);
        };
        return candidates.stream().filter(size -> !rates(camera, size).isEmpty()).collect(Collectors.toList());
    }

    List<Integer> rates(CameraCharacteristics camera, VideoOptions size) {
        if (!isSlowMotion()) {
            List<Integer> regular = size.rates(camera);
            if (this == HORIZON || this == TIMELAPSE) return regular.contains(30) ? List.of(30) : List.of();
            return this == ACTION ? regular.stream().filter(rate -> rate <= 60).collect(Collectors.toList()) : regular;
        }
        int captureFps = this == SLOW ? 120 : size == VideoOptions.HD ? 480 : 240;
        return VideoOptions.hasHighSpeed(camera, size.width, size.height, captureFps) ? List.of(captureFps) : List.of();
    }
}
