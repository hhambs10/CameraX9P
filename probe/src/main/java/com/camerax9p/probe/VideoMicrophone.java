package com.camerax9p.probe;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record VideoMicrophone(String key, Kind kind, String label, AudioDeviceInfo input, AudioDeviceInfo communication) {
    enum Kind {
        PHONE(R.drawable.ic_mic_phone), WIRED(R.drawable.ic_mic_wired),
        BLUETOOTH(R.drawable.ic_mic_bluetooth), OFF(R.drawable.ic_mic_off);

        final int icon;
        Kind(int icon) { this.icon = icon; }
    }

    static final String PHONE = "phone", OFF = "off";

    static List<VideoMicrophone> available(Context context, AudioManager manager) {
        AudioDeviceInfo phone = null;
        List<VideoMicrophone> options = new ArrayList<>();
        List<AudioDeviceInfo> endpoints = manager.getAvailableCommunicationDevices();
        Set<Integer> seenEndpoints = new HashSet<>();
        for (AudioDeviceInfo input : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (input.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                if (phone == null) phone = input;
            } else if (isWired(input)) {
                options.add(new VideoMicrophone("input:" + input.getId(), Kind.WIRED,
                    context.getString(R.string.mic_wired, input.getProductName()), input, null));
            } else if (isBluetooth(input)) {
                AudioDeviceInfo endpoint = endpoints.stream().filter(device -> sameEndpoint(device, input))
                    .findFirst().orElse(null);
                if (endpoint != null) seenEndpoints.add(endpoint.getId());
                options.add(bluetooth(context, input, endpoint));
            }
        }
        for (AudioDeviceInfo endpoint : endpoints) {
            if (isBluetooth(endpoint) && !seenEndpoints.contains(endpoint.getId())) {
                options.add(bluetooth(context, null, endpoint));
            }
        }
        options.add(0, new VideoMicrophone(PHONE, Kind.PHONE, context.getString(R.string.mic_phone), phone, null));
        options.add(new VideoMicrophone(OFF, Kind.OFF, context.getString(R.string.mic_off), null, null));
        return options;
    }

    private static VideoMicrophone bluetooth(Context context, AudioDeviceInfo input, AudioDeviceInfo endpoint) {
        AudioDeviceInfo identified = endpoint == null ? input : endpoint;
        return new VideoMicrophone((endpoint == null ? "input:" : "bt:") + identified.getId(), Kind.BLUETOOTH,
            context.getString(R.string.mic_bluetooth, identified.getProductName()), input, endpoint);
    }

    private static boolean isBluetooth(AudioDeviceInfo device) {
        return device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET;
    }

    private static boolean isWired(AudioDeviceInfo device) {
        return switch (device.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> true;
            default -> false;
        };
    }

    private static boolean sameEndpoint(AudioDeviceInfo first, AudioDeviceInfo second) {
        if (first == null || second == null || first.getType() != second.getType()) return false;
        if (first.getId() == second.getId()) return true;
        return !first.getAddress().isEmpty() && first.getAddress().equals(second.getAddress());
    }

    int source() {
        return switch (kind) {
            case PHONE -> MediaRecorder.AudioSource.CAMCORDER;
            case BLUETOOTH -> MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            default -> MediaRecorder.AudioSource.MIC;
        };
    }

    boolean matches(AudioDeviceInfo actual) {
        if (actual == null) return false;
        if (kind == Kind.PHONE) return actual.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC;
        return sameEndpoint(input, actual) || sameEndpoint(communication, actual);
    }

    Route use(Context context, AudioManager manager, MediaRecorder recorder) throws IOException, InterruptedException {
        Route route = new Route(manager, communication);
        try {
            route.select(context);
            if (input != null && !recorder.setPreferredDevice(input)) {
                throw new IOException(context.getString(R.string.audio_input_rejected, label));
            }
            return route;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            route.close();
            throw failure;
        }
    }

    static final class Route implements AutoCloseable {
        private final AudioManager manager;
        private final AudioDeviceInfo endpoint;
        private final int previousMode;
        private boolean modeChanged, selected;

        Route(AudioManager manager, AudioDeviceInfo endpoint) {
            this.manager = manager;
            this.endpoint = endpoint;
            previousMode = manager.getMode();
        }

        void select(Context context) throws IOException, InterruptedException {
            if (endpoint == null) return;
            if (previousMode == AudioManager.MODE_NORMAL) {
                manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                modeChanged = true;
            }
            if (!manager.setCommunicationDevice(endpoint)) {
                throw new IOException(context.getString(R.string.bluetooth_route_rejected));
            }
            selected = true;
            long deadline = SystemClock.elapsedRealtime() + 2200;
            while (!sameEndpoint(manager.getCommunicationDevice(), endpoint) && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(40);
            }
        }

        @Override public void close() {
            try {
                if (selected) manager.clearCommunicationDevice();
            } finally {
                if (modeChanged && manager.getMode() == AudioManager.MODE_IN_COMMUNICATION) manager.setMode(previousMode);
            }
        }
    }
}
