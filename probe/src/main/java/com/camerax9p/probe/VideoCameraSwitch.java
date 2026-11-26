package com.camerax9p.probe;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONObject;

final class VideoCameraSwitch {
    private final AtomicBoolean requested = new AtomicBoolean();

    boolean request() { return requested.compareAndSet(false, true); }

    String run(Activity context, Intent config, Surface preview, Consumer<Boolean> recordingState,
            Consumer<String> cameraChanged, Supplier<Float> zoom, AtomicBoolean stop, AtomicBoolean pause, String mic) {
        File directory = CaptureFiles.directory(context, config);
        File workingClip = new File(directory, "capture-test.mp4");
        List<File> clips = new ArrayList<>();
        File merged = null;
        boolean processed = false;
        try {
            JSONObject first = null;
            org.json.JSONArray cameraIds = new org.json.JSONArray();
            boolean interrupted = false;
            while (true) {
                String camera = config.getStringExtra("camera");
                String result = CaptureProbe.run(context, config, preview, active -> {
                    if (active) {
                        cameraChanged.accept(camera);
                        recordingState.accept(true);
                    }
                }, zoom, () -> stop.get() || requested.get(), mic, pause::get);
                JSONObject report = new JSONObject(result);
                if (!report.optBoolean("success")) {
                    if (clips.isEmpty()) return result;
                    interrupted = true;
                    break;
                }
                boolean switchNow = requested.getAndSet(false) && !stop.get();
                if (clips.isEmpty() && !switchNow) return result;
                if (first == null) first = report;
                File saved = File.createTempFile("camera-segment-", ".mp4", directory);
                clips.add(saved);
                Files.move(workingClip.toPath(), saved.toPath(), StandardCopyOption.REPLACE_EXISTING);
                cameraIds.put(camera);
                if (!switchNow || stop.get()) break;
                config = new Intent(config).putExtra("camera", "1".equals(camera) ? "0" : "1");
                config.removeExtra("focusX");
                config.removeExtra("focusY");
                config.removeExtra("zoomRatio");
                CameraAdjustments.DEFAULT.writeTo(config);
            }
            recordingState.accept(false);
            int width = first.getInt("actualWidth"), height = first.getInt("actualHeight");
            if (first.optInt("actualRotation") % 180 != 0) {
                int swap = width; width = height; height = swap;
            }
            if (clips.size() == 1) {
                Files.move(clips.get(0).toPath(), workingClip.toPath(), StandardCopyOption.REPLACE_EXISTING);
                processed = true;
                return writeResult(context, first.put("switchInterrupted", true));
            }
            merged = File.createTempFile("camera-join-", ".mp4", directory);
            VideoExport.join(context, clips, merged, width, height);
            VideoExport.checkVideo(merged, width, height, first.optBoolean("audio"));
            Files.move(merged.toPath(), workingClip.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try (MediaMetadataRetriever media = new MediaMetadataRetriever()) {
                media.setDataSource(workingClip.getPath());
                first.put("actualWidth", media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                first.put("actualHeight", media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                first.put("actualRotation", media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                first.put("durationMs", media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                first.put("frameCount", media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
                first.put("measuredFps", 1000.0 * first.optInt("frameCount") / Math.max(1, first.optLong("durationMs")));
            }
            processed = true;
            return writeResult(context, first.put("cameraSegments", clips.size()).put("cameraIds", cameraIds)
                .put("switchInterrupted", interrupted));
        } catch (Exception failed) {
            android.util.Log.w("CameraX9P", "Camera switch export failed", failed);
            try {
                int published = 0;
                for (File clip : clips) {
                    if (clip.length() == 0) continue;
                    PhotoExport.publishClip(context.getContentResolver(), clip);
                    published++;
                }
                processed = true;
                return new JSONObject().put("stage", published > 0 ? "segmentsSaved" : "cameraSwitch").toString();
            } catch (Exception unavailable) {
                android.util.Log.w("CameraX9P", "Camera switch segments could not be published", unavailable);
                return "{\"stage\":\"cameraSwitch\"}";
            }
        } finally {
            recordingState.accept(false);
            if (merged != null) merged.delete();
            if (processed) for (File clip : clips) clip.delete();
        }
    }

    private static String writeResult(Activity context, JSONObject result) {
        String json = result.toString();
        try {
            Files.write(new File(context.getFilesDir(), "capture-result.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException unavailable) {
            android.util.Log.w("CameraX9P", "Camera switch report unavailable", unavailable);
        }
        return json;
    }
}
