package com.camerax9p.probe;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;

final class StarLapse {
    static final int WIDTH = 1920, HEIGHT = 1080, FPS = 30;
    private static final int BASE_ISO = 1600;

    record Preset(long shutterNs, int iso) {
        int exposureSeconds() { return (int) (shutterNs / 1_000_000_000L); }
        int intervalSeconds() { return exposureSeconds() + 2; }
    }

    private StarLapse() {}

    static Preset preset(CameraCharacteristics camera) {
        if (camera == null || !ActionExposure.supported(camera)
                || !Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(camera.get(CameraCharacteristics.LENS_FACING))) return null;
        StreamConfigurationMap map = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        Range<Long> exposure = camera.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> iso = camera.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Long frameDuration = camera.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        if (sizes == null || !Arrays.asList(sizes).contains(new Size(WIDTH, HEIGHT))
                || exposure == null || iso == null || frameDuration == null) return null;
        float[] focalLengths = camera.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        float focalLength = focalLengths == null || focalLengths.length == 0 ? 6f : focalLengths[0];
        // Shorter lenses tolerate longer exposures before a star moves across one output pixel.
        int seconds = focalLength >= 12f ? 2 : focalLength <= 4f ? 6 : 4;
        long shutter = seconds * 1_000_000_000L;
        if (!exposure.contains(shutter) || frameDuration < shutter + 100_000_000L) return null;
        return new Preset(shutter, iso.clamp(BASE_ISO));
    }

    static void capture(CameraDevice device, CameraCharacteristics camera, File output, int rotation,
            Surface preview, Handler handler, Consumer<Boolean> recording, BooleanSupplier stop,
            BooleanSupplier paused, JSONObject report)
            throws Exception {
        Preset preset = preset(camera);
        if (preset == null || stop == null) throw new IOException("Profilo Cielo stellato non disponibile");
        report.put("stage", "star-configure");
        report.put("requestedExposureNs", preset.shutterNs()).put("requestedIso", preset.iso())
            .put("lapseIntervalMs", preset.intervalSeconds() * 1000);
        try (ImageReader reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2);
             StarVideoWriter writer = new StarVideoWriter(output, rotation)) {
            ArrayBlockingQueue<Image> images = new ArrayBlockingQueue<>(1);
            reader.setOnImageAvailableListener(source -> {
                Image image = source.acquireNextImage();
                if (image != null && !images.offer(image)) image.close();
            }, handler);
            CompletableFuture<CameraCaptureSession> configured = new CompletableFuture<>();
            device.createCaptureSession(preview == null ? List.of(reader.getSurface()) : List.of(reader.getSurface(), preview),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        if (!configured.complete(session)) session.close();
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                        session.close();
                        configured.completeExceptionally(new IOException("Sessione pose lunghe rifiutata"));
                    }
                }, handler);
            try (CameraCaptureSession session = configured.get(12, TimeUnit.SECONDS)) {
                CaptureRequest request = request(device, camera, reader.getSurface(), preview, preset);
                report.put("stage", "star-capture");
                if (!paused.getAsBoolean()) recording.accept(true);
                try {
                    int frames = 0;
                    long started = SystemClock.elapsedRealtime();
                    while (!paused.getAsBoolean() && !stop.getAsBoolean()) {
                        long frameStart = SystemClock.elapsedRealtime();
                        TotalCaptureResult result = shoot(session, request, handler, paused);
                        if (result == null) {
                            session.abortCaptures();
                            break;
                        }
                        try (Image frame = awaitImage(images, paused)) {
                            if (paused.getAsBoolean()) break;
                            if (frame == null) throw new IOException("Fotogramma cielo stellato mancante");
                            Long actual = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                            report.put("exposureNs", actual).put("iso", iso)
                                .put("focusDistance", result.get(CaptureResult.LENS_FOCUS_DISTANCE))
                                .put("whiteBalanceMode", result.get(CaptureResult.CONTROL_AWB_MODE));
                            if (actual == null || actual < preset.shutterNs() * 0.9) {
                                throw new IOException("La fotocamera non ha applicato la posa lunga");
                            }
                            writer.write(frame, frames++);
                        }
                        long next = frameStart + preset.intervalSeconds() * 1000L;
                        long remaining;
                        while (!paused.getAsBoolean() && !stop.getAsBoolean()
                                && (remaining = next - SystemClock.elapsedRealtime()) > 0) {
                            Thread.sleep(Math.min(remaining, 100));
                        }
                    }
                    report.put("backgroundStop", paused.getAsBoolean());
                    if (frames == 0) throw new IOException("No complete star time-lapse frame");
                    writer.finish(frames);
                    report.put("capturedFrames", frames).put("recordingMs", SystemClock.elapsedRealtime() - started);
                } finally {
                    recording.accept(false);
                }
            } finally {
                configured.cancel(false);
                reader.setOnImageAvailableListener(null, null);
                Image unconsumed = images.poll();
                if (unconsumed != null) unconsumed.close();
            }
        }
        report.put("stage", "star-export");
        try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
            metadata.setDataSource(output.getAbsolutePath());
            String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String frames = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
            if (duration == null || frames == null || Long.parseLong(duration) <= 0 || Integer.parseInt(frames) < 1) {
                throw new IOException("MP4 cielo stellato privo di fotogrammi");
            }
            report.put("actualWidth", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                .put("actualHeight", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                .put("actualRotation", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
                .put("durationMs", duration).put("frameCount", frames)
                .put("measuredFps", 1000.0 * Integer.parseInt(frames) / Long.parseLong(duration))
                .put("audio", false).put("bytes", output.length());
        }
    }

    private static CaptureRequest request(CameraDevice device, CameraCharacteristics camera, Surface output, Surface preview,
            Preset preset) throws Exception {
        CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        request.addTarget(output);
        if (preview != null) request.addTarget(preview);
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, preset.shutterNs());
        request.set(CaptureRequest.SENSOR_FRAME_DURATION, preset.shutterNs() + 100_000_000);
        request.set(CaptureRequest.SENSOR_SENSITIVITY, preset.iso());
        request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        request.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
        int[] whiteBalance = camera.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (whiteBalance != null && Arrays.stream(whiteBalance).anyMatch(mode -> mode == CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)) {
            request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
        }
        request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        return request.build();
    }

    private static Image awaitImage(ArrayBlockingQueue<Image> images, BooleanSupplier paused) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!paused.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) {
            Image image = images.poll(50, TimeUnit.MILLISECONDS);
            if (image != null) return image;
        }
        return null;
    }

    private static TotalCaptureResult shoot(CameraCaptureSession session, CaptureRequest request, Handler handler,
            BooleanSupplier paused)
            throws Exception {
        CompletableFuture<TotalCaptureResult> frame = new CompletableFuture<>();
        session.capture(request, new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                frame.complete(result);
            }
            @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                frame.completeExceptionally(new IOException("Posa lunga rifiutata: " + failure.getReason()));
            }
        }, handler);
        long deadline = SystemClock.elapsedRealtime() + 16000;
        while (!paused.getAsBoolean()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new java.util.concurrent.TimeoutException();
            try { return frame.get(50, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException waiting) { }
        }
        return null;
    }
}
