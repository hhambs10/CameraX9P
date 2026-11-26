package com.camerax9p.probe;

import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.*;
import android.media.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.view.Surface;
import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Camera2 capture and diagnostics shared by CameraX9P. */
final class CaptureProbe {
    static String measureVideoCadence(Context context, Intent intent, Surface preview, boolean hevc, int seconds) {
        VideoDiagnostic diagnostic = new VideoDiagnostic(hevc, 1000L * Math.max(5, Math.min(seconds, 90)), 2);
        return run(context, intent, preview, recording -> {}, null, null, null, null, diagnostic, () -> false);
    }

    static String measureViewfinderCadence(Context context, Intent intent, Surface preview, Consumer<Boolean> recordingState,
            Supplier<Float> zoom, BooleanSupplier stop, String microphone, int previewInterval, BooleanSupplier paused) {
        return run(context, intent, preview, recordingState, zoom, stop, microphone, null,
            new VideoDiagnostic(false, 0, previewInterval), paused);
    }

    private record VideoDiagnostic(boolean hevc, long durationMs, int previewInterval) {}

    private static final class SensorCadence {
        private boolean measuring;
        private long first = -1, last, frames;
        private final int[] framesPerSecond;
        private final CompletableFuture<Long> firstCallback;
        private long firstCallbackNs, lastCallbackNs;
        private int lostRecorderBuffers, lostOtherBuffers, failedRequests;
        private Long lastExposureNs;
        private Integer lastFlashState;

        SensorCadence(long detailedDurationMs) {
            framesPerSecond = detailedDurationMs > 0 ? new int[(int) (detailedDurationMs / 1000) + 2] : null;
            firstCallback = framesPerSecond == null ? null : new CompletableFuture<>();
        }

        synchronized void start() { measuring = true; }

        synchronized void sample(Long timestamp) {
            if (!measuring || timestamp == null || (first >= 0 && timestamp <= last)) return;
            if (first < 0) first = timestamp;
            last = timestamp;
            frames++;
            if (framesPerSecond != null) {
                lastCallbackNs = SystemClock.elapsedRealtimeNanos();
                if (frames == 1) {
                    firstCallbackNs = lastCallbackNs;
                    firstCallback.complete(firstCallbackNs);
                }
                int second = (int) ((timestamp - first) / 1_000_000_000L);
                if (second >= 0 && second < framesPerSecond.length) framesPerSecond[second]++;
            }
        }

        long awaitFirstCallback(BooleanSupplier paused) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + 1200;
            while (!paused.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) {
                try { return firstCallback.get(50, TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) { }
            }
            throw new TimeoutException();
        }

        synchronized void sampleHighSpeed(TotalCaptureResult result) {
            if (!measuring) return;
            sample(result.get(CaptureResult.SENSOR_TIMESTAMP));
            lastExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            lastFlashState = result.get(CaptureResult.FLASH_STATE);
        }

        synchronized void lostBuffer(boolean recorder) {
            if (!measuring) return;
            if (recorder) lostRecorderBuffers++;
            else lostOtherBuffers++;
        }

        synchronized void failedRequest() {
            if (measuring) failedRequests++;
        }

        synchronized JSONObject stop() throws org.json.JSONException {
            measuring = false;
            JSONObject result = new JSONObject().put("frames", frames);
            if (frames > 1) result.put("fps", 1_000_000_000d * (frames - 1) / (last - first));
            if (framesPerSecond != null) {
                var windows = new org.json.JSONArray();
                if (first >= 0) {
                    for (int second = 0; second < framesPerSecond.length && second * 1_000_000_000L <= last - first; second++) {
                        windows.put(framesPerSecond[second]);
                    }
                }
                result.put("lostRecorderBuffers", lostRecorderBuffers).put("lostOtherBuffers", lostOtherBuffers)
                    .put("failedRequests", failedRequests).put("framesPerTimestampSecond", windows)
                    .put("lastExposureNs", lastExposureNs).put("lastFlashState", lastFlashState);
                if (first >= 0) result.put("firstTimestampNs", first).put("lastTimestampNs", last)
                    .put("firstCallbackElapsedNs", firstCallbackNs).put("lastCallbackElapsedNs", lastCallbackNs);
            }
            return result;
        }
    }

    @FunctionalInterface
    interface PhotoSink {
        void save(JSONObject report) throws Exception;
    }

    static String burst(Context context, Intent intent, BooleanSupplier released, PhotoSink sink) {
        return run(context, intent, null, recording -> {}, null, released, null, sink, null, () -> false);
    }

    static String run(Context context, Intent intent) {
        return run(context, intent, null);
    }

    static String run(Context context, Intent intent, Surface highSpeedPreview) {
        return run(context, intent, highSpeedPreview, recording -> {});
    }

    static String run(Context context, Intent intent, Surface liveVideoPreview, Consumer<Boolean> recordingState) {
        return run(context, intent, liveVideoPreview, recordingState, null);
    }

    static String run(Context context, Intent intent, Surface liveVideoPreview, Consumer<Boolean> recordingState,
            Supplier<Float> recordingZoom) {
        return run(context, intent, liveVideoPreview, recordingState, recordingZoom, null);
    }

    static String run(Context context, Intent intent, Surface liveVideoPreview, Consumer<Boolean> recordingState,
            Supplier<Float> recordingZoom, BooleanSupplier stopRecording) {
        return run(context, intent, liveVideoPreview, recordingState, recordingZoom, stopRecording, null);
    }

    static String run(Context context, Intent intent, Surface liveVideoPreview, Consumer<Boolean> recordingState,
            Supplier<Float> recordingZoom, BooleanSupplier stopRecording, String microphoneKey) {
        return run(context, intent, liveVideoPreview, recordingState, recordingZoom, stopRecording, microphoneKey, null);
    }

    static String run(Context context, Intent intent, Surface liveVideoPreview, Consumer<Boolean> recordingState,
            Supplier<Float> recordingZoom, BooleanSupplier stopRecording, String microphoneKey, BooleanSupplier paused) {
        return run(context, intent, liveVideoPreview, recordingState, recordingZoom, stopRecording, microphoneKey,
            null, null, paused == null ? () -> false : paused);
    }

    private static String run(Context context, Intent intent, Surface liveVideoPreview, Consumer<Boolean> recordingState,
            Supplier<Float> recordingZoom, BooleanSupplier stopRecording, String microphoneKey, PhotoSink burstSink,
            VideoDiagnostic diagnostic, BooleanSupplier paused) {
        HandlerThread thread = new HandlerThread("camera-callback"); thread.start();
        Handler handler = new Handler(thread.getLooper());
        CameraDevice device = null;
        CameraCaptureSession session = null;
        ImageReader reader = null;
        MediaRecorder recorder = null;
        VideoMicrophone microphone = null;
        VideoMicrophone.Route audioRoute = null;
        Surface videoSurface = null;
        GpuCameraRelay relay = null;
        ImageReader videoPreview = null;
        ImageReader rawReader = null;
        boolean recorderRunning = false;
        JSONObject report = new JSONObject();
        String id = intent.getStringExtra("camera");
        int width = intent.getIntExtra("width", 4000), height = intent.getIntExtra("height", 3000);
        boolean video = intent.getBooleanExtra("video", false);
        int fps = intent.getIntExtra("fps", 30);
        int playbackFps = intent.getIntExtra("playbackFps", fps);
        int lapseIntervalMs = video ? intent.getIntExtra("lapseIntervalMs", 0) : 0;
        boolean maximum = intent.getBooleanExtra("maximum", false);
        int format = intent.getIntExtra("format", ImageFormat.JPEG);
        boolean modern = intent.getBooleanExtra("modern",false);
        String physical = intent.getStringExtra("physical");
        File captureDirectory = CaptureFiles.directory(context, intent);
        File output = new File(captureDirectory, "capture-test." + (video ? "mp4" : "jpg"));
        try {
            checkRecordingActive(paused);
            if (!captureDirectory.isDirectory() && !captureDirectory.mkdirs()) {
                throw new java.io.IOException("Could not create capture directory");
            }
            report.put("camera", id).put("requestedWidth", width).put("requestedHeight", height).put("video", video).put("maximumPixelMode", maximum);
            report.put("format", format);
            report.put("runId",intent.getStringExtra("runId"));
            report.put("modernSession",modern).put("physicalId",physical);
            if (width < 1 || height < 1 || width > 20000 || height > 20000) throw new IllegalArgumentException("Invalid dimensions");
            CameraManager manager = context.getSystemService(CameraManager.class);
            CameraCharacteristics properties = manager.getCameraCharacteristics(id);
            Range<Float> zoomRange = properties.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            Float zoom = intent.hasExtra("zoomRatio") && zoomRange != null
                ? zoomRange.clamp(intent.getFloatExtra("zoomRatio", 1f)) : null;
            boolean dng = !video && intent.getBooleanExtra("dng", false);
            android.util.Size dngSize = dng ? RawPhoto.size(properties) : null;
            if (dng && (dngSize == null || maximum || modern || format != ImageFormat.JPEG)) {
                throw new IllegalArgumentException("RAW DNG unavailable for this camera configuration");
            }
            boolean highSpeed = video && fps >= 120;
            boolean useRelay = video && !highSpeed && !modern && liveVideoPreview != null
                && intent.getBooleanExtra("gpuRelay", false);
            CameraAdjustments adjustments = highSpeed ? CameraAdjustments.DEFAULT : CameraAdjustments.from(intent);
            boolean slowMotion = video && playbackFps != fps;
            boolean timeFromFirstFrame = highSpeed && (diagnostic != null
                ? intent.getBooleanExtra("timeFromFirstFrame", false)
                : slowMotion && fps == 120 && width == 3840 && height == 2160 && "0".equals(id)
                    && "OPPO".equalsIgnoreCase(android.os.Build.MANUFACTURER)
                    && "CPH2791".equalsIgnoreCase(android.os.Build.MODEL));
            long diagnosticWindowMs = highSpeed && diagnostic != null ? diagnostic.durationMs()
                : timeFromFirstFrame ? 3500 : 0;
            SensorCadence cadence = useRelay || diagnostic != null || timeFromFirstFrame
                ? new SensorCadence(diagnosticWindowMs) : null;
            boolean timeLapse = lapseIntervalMs > 0;
            boolean captureAudio = video && !highSpeed && !slowMotion && !timeLapse
                && !intent.getBooleanExtra("starLapse", false) && microphoneKey != null
                && !VideoMicrophone.OFF.equals(microphoneKey);
            if (captureAudio) {
                report.put("stage", "audio");
                if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException(context.getString(R.string.microphone_access_required));
                }
                microphone = VideoMicrophone.available(context, context.getSystemService(AudioManager.class)).stream()
                    .filter(option -> option.key().equals(microphoneKey)).findFirst()
                    .orElseThrow(() -> new java.io.IOException(context.getString(R.string.mic_selected_disconnected)));
                report.put("microphone", microphone.label());
            }
            if (timeLapse && (highSpeed || lapseIntervalMs < 100 || lapseIntervalMs > 10_000)) {
                throw new IllegalArgumentException("Unsupported time-lapse interval");
            }
            boolean actionVideo = video && intent.hasExtra("actionExposureNs");
            if (actionVideo && (highSpeed || !ActionExposure.supported(properties))) {
                throw new IllegalArgumentException("Action exposure requires regular video and manual sensor support");
            }
            if (slowMotion && (!highSpeed || playbackFps != 30)) {
                throw new IllegalArgumentException("Slow-motion probe requires high-speed capture and 30 fps playback");
            }
            boolean measurePhoto = !video && intent.getBooleanExtra("measurePhoto", false);
            if (highSpeed && (!VideoOptions.hasHighSpeed(properties, width, height, fps) || modern
                || physical != null || intent.getBooleanExtra("videoPreview", false))) {
                throw new IllegalArgumentException("Unsupported high-speed capture configuration");
            }
            Rect activeSensor = properties.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            FocusPoint focus = intent.hasExtra("focusX") && activeSensor != null && FocusPoint.supported(properties)
                ? new FocusPoint(intent.getFloatExtra("focusX", .5f), intent.getFloatExtra("focusY", .5f))
                : null;
            if (video) report.put("requestedFps", fps).put("playbackFps", playbackFps).put("highSpeed", highSpeed);
            int rotation = 0;
            if (intent.hasExtra("displayRotation")) {
                Integer sensor = properties.get(CameraCharacteristics.SENSOR_ORIENTATION);
                Integer facing = properties.get(CameraCharacteristics.LENS_FACING);
                int display = intent.getIntExtra("displayRotation", 0);
                boolean front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
                rotation = Math.floorMod((sensor == null ? 0 : sensor) + (front ? display : -display)
                    + intent.getIntExtra("manualRotation", 0), 360);
                report.put("rotation", rotation);
            }
            CompletableFuture<CameraDevice> opened = new CompletableFuture<>();
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Camera permission is required");
            }
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) { if (!opened.complete(c)) c.close(); }
                @Override public void onDisconnected(CameraDevice c) { c.close(); opened.completeExceptionally(new Exception("Disconnected")); }
                @Override public void onError(CameraDevice c, int error) { c.close(); opened.completeExceptionally(new Exception("Camera error " + error)); }
            }, handler);
            try { device = opened.get(10, TimeUnit.SECONDS); } finally { if (!opened.isDone()) opened.cancel(false); }
            checkRecordingActive(paused);
            if (video && intent.getBooleanExtra("starLapse", false)) {
                StarLapse.capture(device, properties, output, rotation, liveVideoPreview, handler, recordingState,
                    stopRecording, paused, report);
                report.put("success", true).put("stage", "complete");
                return saveReport(context, report);
            }
            Surface surface;
            if (video) {
                report.put("stage", "encoder");
                recorder = new MediaRecorder(context);
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                if (microphone != null) recorder.setAudioSource(microphone.source());
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setOutputFile(output);
                int bitrate = width >= 3840 ? 30000000 : 12000000;
                recorder.setVideoEncodingBitRate(bitrate * Math.min(fps / 30, 3));
                recorder.setVideoFrameRate(playbackFps);
                if (slowMotion) recorder.setCaptureRate(fps);
                if (timeLapse) recorder.setCaptureRate(1000.0 / lapseIntervalMs);
                recorder.setVideoSize(width, height);
                boolean hevc = highSpeed || diagnostic != null && diagnostic.hevc();
                recorder.setVideoEncoder(hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
                if (diagnostic != null) {
                    int interval = fps == 60 ? diagnostic.previewInterval() : 1;
                    report.put("diagnosticEncoder", hevc ? "HEVC" : "H.264")
                        .put("previewRequest", liveVideoPreview == null ? "none"
                            : interval == 1 ? "continuous" : interval == 2 ? "alternating" : "every-" + interval)
                        .put("diagnosticPreviewSize", intent.getStringExtra("diagnosticPreviewSize"))
                        .put("diagnosticPreviewRate", intent.getIntExtra("diagnosticPreviewRate", fps));
                    var map = properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    android.util.Size[] sizes = map == null ? null : map.getOutputSizes(android.graphics.SurfaceTexture.class);
                    org.json.JSONArray previewSizes = new org.json.JSONArray();
                    if (sizes != null) {
                        for (android.util.Size size : sizes) {
                            if (size.getWidth() <= 1280 && size.getHeight() <= 720) {
                                previewSizes.put(new JSONObject().put("size", size.toString()).put("minFrameDurationNs",
                                    map.getOutputMinFrameDuration(android.graphics.SurfaceTexture.class, size)));
                            }
                        }
                    }
                    report.put("previewSizes", previewSizes);
                }
                if (microphone != null) {
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    recorder.setAudioChannels(1);
                    recorder.setAudioSamplingRate(48000);
                    recorder.setAudioEncodingBitRate(128000);
                    report.put("stage", "audio");
                    audioRoute = microphone.use(context, context.getSystemService(AudioManager.class), recorder);
                    report.put("stage", "encoder");
                }
                if (rotation != 0) recorder.setOrientationHint(rotation);
                recorder.prepare();
                videoSurface = recorder.getSurface();
                surface = videoSurface;
                if (useRelay) {
                    boolean realtime = Integer.valueOf(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
                        .equals(properties.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE));
                    try {
                        relay = new GpuCameraRelay(videoSurface, liveVideoPreview, width, height, realtime);
                        surface = relay.input();
                        report.put("previewRequest", "gpu-relay").put("gpuRelay", true);
                    } catch (InterruptedException interrupted) {
                        throw interrupted;
                    } catch (Exception failure) {
                        android.util.Log.w("CameraX9P", "GPU relay setup failed. Using direct camera output.", failure);
                        report.put("gpuRelayFallback", "egl");
                    }
                }
            } else {
                reader = ImageReader.newInstance(width, height, format, 1);
                surface = reader.getSurface();
            }
            CompletableFuture<CameraCaptureSession> configured = new CompletableFuture<>();
            CameraCaptureSession.StateCallback sessionCallback = sessionCallback(configured);
            report.put("stage","configure");
            java.util.List<Surface> targets = new java.util.ArrayList<>();
            targets.add(surface);
            Surface cameraPreview = relay == null ? liveVideoPreview : null;
            if (video && liveVideoPreview != null) {
                report.put("livePreview", true);
            }
            if (video && cameraPreview != null) targets.add(cameraPreview);
            if (dngSize != null) {
                rawReader = ImageReader.newInstance(dngSize.getWidth(), dngSize.getHeight(), ImageFormat.RAW_SENSOR, 1);
                targets.add(rawReader.getSurface());
            }
            if ((video && liveVideoPreview == null && intent.getBooleanExtra("videoPreview", false))
                    || (!video && (focus != null || adjustments.needsPreflash())) || measurePhoto) {
                videoPreview = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);
                videoPreview.setOnImageAvailableListener(source -> {
                    try (Image ignored = source.acquireLatestImage()) {
                        // Drain the preview stream while the recorder consumes its own surface.
                    }
                }, handler);
                targets.add(videoPreview.getSurface());
            }
            if (highSpeed) {
                java.util.List<android.hardware.camera2.params.OutputConfiguration> outputs = new java.util.ArrayList<>();
                outputs.add(new android.hardware.camera2.params.OutputConfiguration(surface));
                if (liveVideoPreview != null) {
                    outputs.add(new android.hardware.camera2.params.OutputConfiguration(liveVideoPreview));
                }
                var configuration = new android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_HIGH_SPEED,
                    outputs, command -> handler.post(command), sessionCallback);
                if (intent.getBooleanExtra("sessionFps", false)) {
                    CaptureRequest.Builder parameters = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    parameters.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(fps, fps));
                    configuration.setSessionParameters(parameters.build());
                }
                device.createCaptureSession(configuration);
            } else if (video && liveVideoPreview != null && fps > 30 && !modern) {
                try {
                    session = configureVideoSession(device, targets, fps, handler);
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception failure) {
                    if (relay == null) throw failure;
                    android.util.Log.w("CameraX9P", "GPU camera session failed. Using direct camera output.", failure);
                    relay.close();
                    relay = null;
                    surface = videoSurface;
                    cameraPreview = liveVideoPreview;
                    report.put("gpuRelay", false).put("gpuRelayFallback", "cameraSession")
                        .put("previewRequest", "direct");
                    session = configureVideoSession(device, List.of(surface, cameraPreview), fps, handler);
                }
            } else if(modern) {
                android.hardware.camera2.params.OutputConfiguration outputConfig = new android.hardware.camera2.params.OutputConfiguration(surface);
                outputConfig.addSensorPixelModeUsed(maximum?CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION:CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT);
                if(physical!=null) outputConfig.setPhysicalCameraId(physical);
                java.util.List<android.hardware.camera2.params.OutputConfiguration> outputs = new java.util.ArrayList<>();
                outputs.add(outputConfig);
                if (videoPreview != null) outputs.add(new android.hardware.camera2.params.OutputConfiguration(videoPreview.getSurface()));
                android.hardware.camera2.params.SessionConfiguration configuration = new android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,outputs,command -> handler.post(command),sessionCallback);
                device.createCaptureSession(configuration);
            } else {
                if(physical!=null) throw new IllegalArgumentException("Physical routing requires modern session");
                device.createCaptureSession(targets, sessionCallback, handler);
            }
            if (session == null) session = awaitSession(configured);
            checkRecordingActive(paused);
            CaptureRequest.Builder request = device.createCaptureRequest(video ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(surface);
            if (rawReader != null) request.addTarget(rawReader.getSurface());
            if (video && cameraPreview != null) request.addTarget(cameraPreview);
            if (video && videoPreview != null) request.addTarget(videoPreview.getSurface());
            request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            if (!video && intent.hasExtra("displayRotation")) request.set(CaptureRequest.JPEG_ORIENTATION, 0);
            if (maximum) request.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
            if (zoom != null) {
                request.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
                report.put("requestedZoom", zoom);
            }
            if (!adjustments.equals(CameraAdjustments.DEFAULT)) adjustments.apply(request, properties, video ? fps : 0);
            if (video && !highSpeed) WhiteBalance.record(intent, request);
            if (!video && intent.hasExtra("manualFocus")) {
                Float maxDiopters = properties.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                if (maxDiopters != null && maxDiopters > 0) {
                    request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    request.set(CaptureRequest.LENS_FOCUS_DISTANCE,
                        Math.max(0f, Math.min(maxDiopters, intent.getFloatExtra("manualFocus", 0f))));
                }
            }
            if (video) {
                report.put("stage", "record");
                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(fps, fps));
                CompletableFuture<TotalCaptureResult> actionResult = new CompletableFuture<>();
                CameraCaptureSession.CaptureCallback callback = null;
                if (highSpeed) {
                    CameraConstrainedHighSpeedCaptureSession fast = (CameraConstrainedHighSpeedCaptureSession) session;
                    if (diagnostic != null && intent.getBooleanExtra("diagnosticTorch", false)) {
                        request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                        report.put("diagnosticTorch", true);
                    }
                    if (cadence != null) {
                        Surface encoderTarget = surface;
                        callback = new CameraCaptureSession.CaptureCallback() {
                            @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                    TotalCaptureResult result) {
                                cadence.sampleHighSpeed(result);
                                actionResult.complete(result);
                            }

                            @Override public void onCaptureBufferLost(CameraCaptureSession session, CaptureRequest request,
                                    Surface target, long frameNumber) {
                                cadence.lostBuffer(target == encoderTarget);
                            }

                            @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
                                    CaptureFailure failure) {
                                cadence.failedRequest();
                            }
                        };
                    }
                    fast.setRepeatingBurst(fast.createHighSpeedRequestList(request.build()), callback, handler);
                } else {
                    if (focus != null) {
                        focus.apply(request, activeSensor, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        report.put("focusRegion", java.util.Arrays.toString(request.get(CaptureRequest.CONTROL_AF_REGIONS)));
                    }
                    if (actionVideo) {
                        ActionExposure meter = new ActionExposure(intent.getLongExtra("actionExposureNs", 0),
                            intent.getIntExtra("actionIso", 0));
                        meter.apply(request, properties, fps);
                        report.put("actionShutterNs", request.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
                        report.put("actionIso", request.get(CaptureRequest.SENSOR_SENSITIVITY));
                    }
                    if (cadence != null || actionVideo || !adjustments.equals(CameraAdjustments.DEFAULT) || zoom != null
                            || intent.hasExtra("awbMode")) {
                        callback = new CameraCaptureSession.CaptureCallback() {
                            @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                    TotalCaptureResult result) {
                                actionResult.complete(result);
                                if (cadence != null) cadence.sample(result.get(CaptureResult.SENSOR_TIMESTAMP));
                            }
                        };
                    }
                    submitVideo(session, request, cameraPreview, fps,
                        diagnostic == null ? 2 : diagnostic.previewInterval(), callback, handler);
                }
                checkRecordingActive(paused);
                recorder.start();
                if (relay != null) relay.start();
                long recordingStartedNs = SystemClock.elapsedRealtimeNanos();
                long recordingStarted = SystemClock.elapsedRealtime();
                if (cadence != null) cadence.start();
                recorderRunning = true;
                long captureWindowStarted = recordingStarted;
                if (timeFromFirstFrame) {
                    try {
                        captureWindowStarted = cadence.awaitFirstCallback(paused) / 1_000_000L;
                        report.put("captureWindowDelayMs", captureWindowStarted - recordingStarted);
                    } catch (TimeoutException timeout) {
                        report.put("firstFrameTimedOut", true);
                    }
                }
                if (!paused.getAsBoolean()) recordingState.accept(true);
                if (microphone != null) {
                    report.put("stage", "audio");
                    AudioDeviceInfo routed = routedMicrophone(recorder);
                    long deadline = recordingStarted + 1600;
                    while (!microphone.matches(routed) && !paused.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(50);
                        routed = routedMicrophone(recorder);
                    }
                    if (!microphone.matches(routed)) {
                        throw new java.io.IOException(context.getString(R.string.audio_input_inactive));
                    }
                    report.put("routedMic", routed.getProductName().toString()).put("routedMicType", routed.getType());
                    report.put("stage", "record");
                }
                boolean liveZoom = recordingZoom != null && zoomRange != null && !highSpeed;
                float lastZoom = zoom == null ? 1f : zoom;
                long minimumMs = timeLapse ? 3L * lapseIntervalMs : 650;
                long holdMs = diagnostic == null || liveZoom ? 3500 : diagnostic.durationMs();
                while (!paused.getAsBoolean()) {
                    long now = SystemClock.elapsedRealtime();
                    if (relay != null && relay.interrupted()) break;
                    if (stopRecording == null ? now - captureWindowStarted >= holdMs
                            : now - recordingStarted >= minimumMs && stopRecording.getAsBoolean()) break;
                    Thread.sleep(50);
                    if (!liveZoom || paused.getAsBoolean()) continue;
                    float next = zoomRange.clamp(recordingZoom.get());
                    if (next == lastZoom) continue;
                    request.set(CaptureRequest.CONTROL_ZOOM_RATIO, next);
                    submitVideo(session, request, cameraPreview, fps,
                        diagnostic == null ? 2 : diagnostic.previewInterval(), callback, handler);
                    lastZoom = next;
                    report.put("endZoom", next);
                }
                boolean backgroundStop = paused.getAsBoolean();
                if (backgroundStop) session.stopRepeating();
                report.put("backgroundStop", backgroundStop);
                long recordingMs = SystemClock.elapsedRealtime() - recordingStarted;
                long captureWindowMs = recordingMs - (captureWindowStarted - recordingStarted);
                if (report.has("captureWindowDelayMs")) report.put("captureWindowMs", captureWindowMs);
                if (cadence != null) report.put("sensorCadence", cadence.stop());
                report.put("recordingStartedNs", recordingStartedNs);
                if (timeLapse) report.put("lapseIntervalMs", lapseIntervalMs);
                boolean routedUntilStop = microphone == null || microphone.matches(routedMicrophone(recorder));
                if (relay != null) {
                    GpuCameraRelay.Stats stats = relay.finish();
                    report.put("relayEncoderFrames", stats.encodedFrames()).put("relayPreviewFrames", stats.previewFrames())
                        .put("relayPreviewFailed", stats.previewFailed()).put("relayInterrupted", stats.interrupted());
                }
                recorder.stop();
                recorderRunning = false;
                recordingState.accept(false);
                if (!backgroundStop) session.stopRepeating();
                if (!routedUntilStop) {
                    report.put("stage", "audio");
                    throw new java.io.IOException(context.getString(R.string.mic_selected_disconnected));
                }
                TotalCaptureResult firstActionFrame = actionResult.getNow(null);
                if (firstActionFrame != null) {
                    if (intent.hasExtra("awbMode")) {
                        report.put("awbMode", firstActionFrame.get(CaptureResult.CONTROL_AWB_MODE))
                            .put("awbGains", String.valueOf(firstActionFrame.get(CaptureResult.COLOR_CORRECTION_GAINS)));
                    }
                    if (zoom != null) report.put("zoom", firstActionFrame.get(CaptureResult.CONTROL_ZOOM_RATIO));
                    if (actionVideo) {
                        report.put("actionActualShutterNs", firstActionFrame.get(CaptureResult.SENSOR_EXPOSURE_TIME));
                        report.put("actionActualIso", firstActionFrame.get(CaptureResult.SENSOR_SENSITIVITY));
                        report.put("actionActualAfMode", firstActionFrame.get(CaptureResult.CONTROL_AF_MODE));
                    }
                    if (highSpeed && diagnostic != null) {
                        report.put("sensorExposureSampleNs", firstActionFrame.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                            .put("sensorFrameDurationSampleNs", firstActionFrame.get(CaptureResult.SENSOR_FRAME_DURATION))
                            .put("sensorTimestampSource", properties.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE));
                    }
                    if (!adjustments.equals(CameraAdjustments.DEFAULT)) reportAdjustments(report, firstActionFrame);
                }
                try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
                    metadata.setDataSource(output.getAbsolutePath());
                    boolean hasAudio = "yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
                    report.put("audio", hasAudio);
                    if (microphone != null && !hasAudio) {
                        report.put("stage", "audio");
                        throw new java.io.IOException(context.getString(R.string.audio_track_missing));
                    }
                    report.put("actualWidth", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    report.put("actualHeight", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    report.put("actualRotation", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                    report.put("durationMs", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                    report.put("frameCount", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
                    if (relay != null && Math.abs(report.optLong("durationMs") - recordingMs) > 2_000) {
                        report.put("stage", "relayTimestamps");
                        throw new java.io.IOException("GPU relay recording and track durations differ");
                    }
                    double measuredFps = 1000.0 * report.optInt("frameCount") / Math.max(1, report.optInt("durationMs"));
                    report.put("measuredFps", measuredFps);
                    double framesPerCaptureSecond = 1000.0 * report.optInt("frameCount") / recordingMs;
                    report.put("recordingMs", recordingMs).put("framesPerCaptureSecond", framesPerCaptureSecond);
                    double estimatedCaptureFps = framesPerCaptureSecond;
                    if (report.has("captureWindowMs")) {
                        double windowFps = 1000.0 * report.optInt("frameCount") / Math.max(1, captureWindowMs);
                        // The first result can arrive after the encoder has received its first frame.
                        estimatedCaptureFps = Math.min(windowFps, report.getJSONObject("sensorCadence").optDouble("fps", windowFps));
                        report.put("estimatedActiveCaptureFps", estimatedCaptureFps);
                    }
                    if (intent.hasExtra("fps") && (Math.abs(measuredFps - playbackFps) > playbackFps * .1
                            || (slowMotion && Math.abs(estimatedCaptureFps - fps) > fps * .1))) {
                        report.put("frameRateOutsideTolerance", true);
                        if (diagnostic == null && !slowMotion && (stopRecording == null || timeLapse || highSpeed)) {
                            report.put("stage", "frameRate");
                            throw new IllegalStateException("Requested capture/playback: " + fps + "/" + playbackFps
                                + " fps; measured playback: " + Math.round(measuredFps) + " fps");
                        }
                    }
                    android.graphics.Bitmap frame = metadata.getFrameAtTime(1000000);
                    report.put("decodedFrame", frame != null);
                    if (frame != null) frame.recycle();
                    else if (slowMotion || backgroundStop || report.optBoolean("relayInterrupted")) {
                        throw new java.io.IOException("Recording does not contain a readable frame");
                    }
                }
                report.put("bytes", output.length());
            } else {
                report.put("stage","capture");
                if (measurePhoto) {
                    configureMotionPhoto(request, intent, properties);
                    CaptureRequest.Builder metering = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    metering.addTarget(videoPreview.getSurface());
                    configureMotionPhoto(metering, intent, properties);
                    session.setRepeatingRequest(metering.build(), null, handler);
                    try {
                        Thread.sleep(1100);
                    } finally {
                        session.stopRepeating();
                    }
                }
                if (focus != null && videoPreview != null) {
                    focus.apply(request, activeSensor, CaptureRequest.CONTROL_AF_MODE_AUTO);
                    report.put("focusRegion", java.util.Arrays.toString(request.get(CaptureRequest.CONTROL_AF_REGIONS)));
                    int focusState = focusForPhoto(device, session, videoPreview.getSurface(), focus, activeSensor,
                        adjustments, properties, zoom, handler);
                    report.put("focusState", focusState);
                }
                if (adjustments.needsPreflash()) {
                    warmUpFlash(device, session, videoPreview.getSurface(), adjustments, properties, focus, activeSensor, zoom, handler);
                }
                File dngOutput = new File(captureDirectory, "capture-test.dng");
                CaptureRequest still = request.build();
                do {
                    report.put("stage", "capture");
                    TotalCaptureResult result = takePhoto(session, still, handler, reader, rawReader,
                        output, dngOutput, properties, rotation, report);
                    report.put("exposureNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                        .put("iso", result.get(CaptureResult.SENSOR_SENSITIVITY))
                        .put("focusDistance", result.get(CaptureResult.LENS_FOCUS_DISTANCE))
                        .put("focalLength", result.get(CaptureResult.LENS_FOCAL_LENGTH))
                        .put("crop", String.valueOf(result.get(CaptureResult.SCALER_CROP_REGION)))
                        .put("sensorPixelMode", result.get(CaptureResult.SENSOR_PIXEL_MODE));
                    if (zoom != null) report.put("zoom", result.get(CaptureResult.CONTROL_ZOOM_RATIO));
                    if (!adjustments.equals(CameraAdjustments.DEFAULT) || intent.hasExtra("flashMode")) {
                        reportAdjustments(report, result);
                    }
                    if (measurePhoto) {
                        report.put("actualSceneMode", result.get(CaptureResult.CONTROL_SCENE_MODE));
                        report.put("actualAeMode", result.get(CaptureResult.CONTROL_AE_MODE));
                        report.put("actualAfMode", result.get(CaptureResult.CONTROL_AF_MODE));
                    }
                    if (burstSink != null) {
                        report.put("stage", "export");
                        burstSink.save(report);
                        report.put("burstFrames", report.optInt("burstFrames") + 1);
                    }
                } while (burstSink != null && !stopRecording.getAsBoolean());
            }
            report.put("success", true);
            report.put("stage","complete");
        } catch (Exception e) {
            try {
                report.put("success", false).put("error", e.toString());
                if (paused.getAsBoolean()) report.put("backgroundStop", true);
            } catch (Exception ignored) {}
        } finally {
            if (recorderRunning) recordingState.accept(false);
            if (session != null) session.close();
            if (device != null) device.close();
            if (rawReader != null) rawReader.close();
            if (reader != null) reader.close();
            if (videoPreview != null) videoPreview.close();
            if (relay != null) relay.close();
            try {
                if (recorder != null) recorder.release();
            } finally {
                if (audioRoute != null) audioRoute.close();
            }
            if (videoSurface != null) videoSurface.release();
            thread.quitSafely();
        }
        return saveReport(context, report);
    }

    private static void checkRecordingActive(BooleanSupplier paused) throws java.io.IOException {
        if (paused.getAsBoolean()) throw new java.io.IOException("Recording stopped before capture");
    }

    private static CameraCaptureSession.StateCallback sessionCallback(CompletableFuture<CameraCaptureSession> configured) {
        return new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession session) {
                if (!configured.complete(session)) session.close();
            }

            @Override public void onConfigureFailed(CameraCaptureSession session) {
                session.close();
                configured.completeExceptionally(new java.io.IOException("Session rejected"));
            }
        };
    }

    private static CameraCaptureSession awaitSession(CompletableFuture<CameraCaptureSession> configured) throws Exception {
        try {
            return configured.get(12, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException failure) {
            if (!configured.cancel(false)) {
                configured.thenAccept(CameraCaptureSession::close);
            }
            throw failure;
        }
    }

    private static CameraCaptureSession configureVideoSession(CameraDevice device, List<Surface> surfaces,
            int fps, Handler handler) throws Exception {
        CompletableFuture<CameraCaptureSession> configured = new CompletableFuture<>();
        List<android.hardware.camera2.params.OutputConfiguration> outputs = new ArrayList<>();
        for (Surface surface : surfaces) outputs.add(new android.hardware.camera2.params.OutputConfiguration(surface));
        var configuration = new android.hardware.camera2.params.SessionConfiguration(
            android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
            outputs, command -> handler.post(command), sessionCallback(configured));
        CaptureRequest.Builder parameters = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        parameters.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(fps, fps));
        configuration.setSessionParameters(parameters.build());
        device.createCaptureSession(configuration);
        return awaitSession(configured);
    }

    private static TotalCaptureResult takePhoto(CameraCaptureSession session, CaptureRequest request, Handler handler,
            ImageReader reader, ImageReader rawReader, File jpeg, File dng, CameraCharacteristics properties,
            int rotation, JSONObject report) throws Exception {
        CompletableFuture<JSONObject> imageResult = new CompletableFuture<>();
        reader.setOnImageAvailableListener(source -> {
            try (Image image = source.acquireNextImage()) {
                if (image == null) return;
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                if (image.getFormat() != ImageFormat.JPEG) {
                    imageResult.complete(new JSONObject().put("width", image.getWidth()).put("height", image.getHeight())
                        .put("format", image.getFormat()).put("planeBytes", buffer.remaining())
                        .put("rowStride", image.getPlanes()[0].getRowStride()));
                    return;
                }
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                Files.write(jpeg.toPath(), data);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                imageResult.complete(new JSONObject().put("width", bounds.outWidth).put("height", bounds.outHeight)
                    .put("bytes", data.length).put("bufferWidth", image.getWidth()).put("bufferHeight", image.getHeight()));
            } catch (Exception failure) {
                imageResult.completeExceptionally(failure);
            }
        }, handler);
        CompletableFuture<Image> rawImage = new CompletableFuture<>();
        if (rawReader != null) rawReader.setOnImageAvailableListener(source -> {
            Image image = source.acquireNextImage();
            if (image != null && !rawImage.complete(image)) image.close();
        }, handler);
        try {
            CompletableFuture<TotalCaptureResult> metadata = new CompletableFuture<>();
            session.capture(request, new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    metadata.complete(result);
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    Exception error = new java.io.IOException("Capture failed: " + failure.getReason());
                    metadata.completeExceptionally(error);
                    imageResult.completeExceptionally(error);
                }
            }, handler);
            Long exposure = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
            long waitSeconds = exposure == null ? 20 : Math.max(20, 15 + (exposure + 999_999_999L) / 1_000_000_000L);
            JSONObject actual = imageResult.get(waitSeconds, TimeUnit.SECONDS);
            report.put("image", actual).put("requestedDimensionsMatched",
                actual.getInt("width") == reader.getWidth() && actual.getInt("height") == reader.getHeight());
            TotalCaptureResult result = metadata.get(5, TimeUnit.SECONDS);
            if (rawReader != null) {
                report.put("stage", "raw");
                Image image = rawImage.get(10, TimeUnit.SECONDS);
                RawPhoto.write(dng, properties, result, image, rotation);
                report.put("raw", new JSONObject().put("width", image.getWidth()).put("height", image.getHeight())
                    .put("bytes", dng.length()));
            }
            return result;
        } finally {
            if (!rawImage.cancel(false)) rawImage.getNow(null).close();
        }
    }

    private static AudioDeviceInfo routedMicrophone(MediaRecorder recorder) {
        AudioDeviceInfo device = recorder.getRoutedDevice();
        if (device != null) return device;
        AudioRecordingConfiguration active = recorder.getActiveRecordingConfiguration();
        return active == null ? null : active.getAudioDevice();
    }

    private static String saveReport(Context context, JSONObject report) {
        try { Files.write(new File(context.getFilesDir(), "capture-result.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { return "Cannot save report: " + e; }
        return report.toString();
    }

    private static void submitVideo(CameraCaptureSession session, CaptureRequest.Builder request, Surface preview,
            int fps, int previewInterval, CameraCaptureSession.CaptureCallback callback, Handler handler)
            throws CameraAccessException {
        if (preview != null && fps == 60 && previewInterval > 1) {
            CaptureRequest withPreview = request.build();
            request.removeTarget(preview);
            CaptureRequest recordingOnly = request.build();
            request.addTarget(preview);
            List<CaptureRequest> burst = new ArrayList<>(previewInterval);
            burst.add(withPreview);
            burst.addAll(Collections.nCopies(previewInterval - 1, recordingOnly));
            session.setRepeatingBurst(burst, callback, handler);
        } else {
            session.setRepeatingRequest(request.build(), callback, handler);
        }
    }

    private static void configureMotionPhoto(CaptureRequest.Builder request, Intent intent,
            CameraCharacteristics camera) {
        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        int scene = intent.getIntExtra("sceneMode", CaptureRequest.CONTROL_SCENE_MODE_DISABLED);
        if (scene != CaptureRequest.CONTROL_SCENE_MODE_DISABLED) {
            int[] available = camera.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
            if (available == null || java.util.Arrays.stream(available).noneMatch(mode -> mode == scene)) {
                throw new IllegalArgumentException("Scene mode unavailable");
            }
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
            request.set(CaptureRequest.CONTROL_SCENE_MODE, scene);
        } else if (intent.hasExtra("exposureNs")) {
            long time = intent.getLongExtra("exposureNs", 1_000_000);
            int iso = intent.getIntExtra("iso", 800);
            Range<Long> times = camera.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Range<Integer> sensitivities = camera.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (times == null || sensitivities == null || !times.contains(time) || !sensitivities.contains(iso)) {
                throw new IllegalArgumentException("Exposure time or ISO unavailable");
            }
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, time);
            request.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        }
    }

    private static int focusForPhoto(CameraDevice camera, CameraCaptureSession session, Surface target,
            FocusPoint point, Rect sensor, CameraAdjustments adjustments, CameraCharacteristics properties,
            Float zoom, Handler handler) throws Exception {
        CaptureRequest.Builder scan = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        scan.addTarget(target);
        point.apply(scan, sensor, CaptureRequest.CONTROL_AF_MODE_AUTO);
        if (zoom != null) scan.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        if (!adjustments.equals(CameraAdjustments.DEFAULT)) adjustments.limitShutter(66_666_666L).apply(scan, properties, 0);
        CompletableFuture<Integer> finished = new CompletableFuture<>();
        CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                    TotalCaptureResult result) {
                Integer state = result.get(CaptureResult.CONTROL_AF_STATE);
                if (FocusPoint.isTerminal(state)) finished.complete(state);
            }
        };
        scan.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        session.capture(scan.build(), callback, handler);
        scan.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        session.setRepeatingRequest(scan.build(), callback, handler);
        try {
            return finished.get(1800, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            return -1;
        } finally {
            session.stopRepeating();
        }
    }

    private static void warmUpFlash(CameraDevice camera, CameraCaptureSession session, Surface target,
            CameraAdjustments adjustments, CameraCharacteristics properties, FocusPoint focus, Rect sensor,
            Float zoom, Handler handler) throws Exception {
        CaptureRequest.Builder meter = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        meter.addTarget(target);
        adjustments.apply(meter, properties, 0);
        if (focus != null) focus.apply(meter, sensor, CaptureRequest.CONTROL_AF_MODE_AUTO);
        if (zoom != null) meter.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        CompletableFuture<Void> ready = new CompletableFuture<>();
        CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
            private int frames;

            @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                    TotalCaptureResult result) {
                Integer state = result.get(CaptureResult.CONTROL_AE_STATE);
                if (++frames >= 4 && state != null && (state == CaptureResult.CONTROL_AE_STATE_CONVERGED
                        || state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                        || state == CaptureResult.CONTROL_AE_STATE_LOCKED)) ready.complete(null);
            }
        };
        meter.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
        session.capture(meter.build(), callback, handler);
        meter.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        session.setRepeatingRequest(meter.build(), callback, handler);
        try { ready.get(1500, TimeUnit.MILLISECONDS); }
        catch (TimeoutException ignored) { }
        finally { session.stopRepeating(); }
    }

    private static void reportAdjustments(JSONObject report, CaptureResult result) throws org.json.JSONException {
        report.put("ev", result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION))
            .put("aeMode", result.get(CaptureResult.CONTROL_AE_MODE))
            .put("iso", result.get(CaptureResult.SENSOR_SENSITIVITY))
            .put("exposureNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            .put("tonemapMode", result.get(CaptureResult.TONEMAP_MODE))
            .put("flashMode", result.get(CaptureResult.FLASH_MODE))
            .put("flashState", result.get(CaptureResult.FLASH_STATE));
    }
}
