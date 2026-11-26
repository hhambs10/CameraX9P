package com.camerax9p.probe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import com.camerax9p.core.PhotoGeometry;
import java.util.Collections;
import java.util.function.Consumer;

/** Owns the camera and its preview surface on the main thread. */
final class LivePreview implements TextureView.SurfaceTextureListener {
    interface Listener {
        void onWaiting();
        void onFrame();
        void onError();
        void onFraming(Rect frame, Rect mount);
    }

    private final Context context;
    private final TextureView view;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Surface surface;
    private String requestedId;
    private int generation;
    private Size size;
    private int sensorOrientation;
    private boolean frontFacing;
    private double aspect = 4.0 / 3;
    private int manualRotation;
    private boolean polaroid;
    private boolean firstFrame;
    private CaptureRequest.Builder repeating;
    private CameraCaptureSession.CaptureCallback repeatingCallback;
    private CameraCharacteristics cameraProperties;
    private CameraAdjustments adjustments = CameraAdjustments.DEFAULT;
    private float zoomRatio = 1f;
    private Rect activeSensor, sensorCrop;
    private ActionExposure exposure;
    private WhiteBalance whiteBalance = WhiteBalance.AUTO;
    private WhiteBalance.Snapshot lockedWhiteBalance;
    private boolean focusSupported;
    private boolean continuousFocusSupported;
    private FocusPoint focusPoint;
    private Float manualFocus;
    private Float measuredFocus;
    private boolean focusMagnified;
    private final Matrix touchToSensor = new Matrix();
    private final Rect frameBounds = new Rect();
    private final Rect viewfinderInsets = new Rect();
    private StreamConfigurationMap streamMap;
    private int focusGeneration;

    LivePreview(Context context, TextureView view, Listener listener) {
        this.context = context;
        this.view = view;
        this.listener = listener;
        view.setSurfaceTextureListener(this);
    }

    void start(String id) {
        if (id.equals(requestedId)) return;
        stop();
        requestedId = id;
        if (view.isAvailable()) open(view.getSurfaceTexture());
    }

    void stop() {
        generation++;
        requestedId = null;
        firstFrame = false;
        if (session != null) session.close();
        if (camera != null) camera.close();
        if (surface != null) surface.release();
        session = null;
        camera = null;
        surface = null;
        repeating = null;
        repeatingCallback = null;
        sensorCrop = null;
        exposure = null;
        lockedWhiteBalance = null;
        focusPoint = null;
        measuredFocus = null;
        focusGeneration++;
    }

    private void failed(int token) {
        if (token != generation) return;
        stop();
        listener.onError();
    }

    private void open(SurfaceTexture texture) {
        if (texture == null || requestedId == null) return;
        final int token = generation;
        listener.onWaiting();
        try {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                failed(token);
                return;
            }
            CameraManager manager = context.getSystemService(CameraManager.class);
            CameraCharacteristics properties = manager.getCameraCharacteristics(requestedId);
            cameraProperties = properties;
            StreamConfigurationMap map = properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            streamMap = map;
            size = chooseSize(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            Integer orientation = properties.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 0 : orientation;
            Integer facing = properties.get(CameraCharacteristics.LENS_FACING);
            frontFacing = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            activeSensor = properties.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            focusSupported = activeSensor != null && FocusPoint.supported(properties);
            int[] focusModes = properties.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            continuousFocusSupported = focusModes != null && java.util.Arrays.stream(focusModes)
                .anyMatch(mode -> mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            transform();
            surface = new Surface(texture);
            manager.openCamera(requestedId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (token != generation) {
                        device.close();
                        return;
                    }
                    camera = device;
                    configure(device, token);
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    failed(token);
                }
                @Override public void onError(CameraDevice device, int error) {
                    device.close();
                    failed(token);
                }
            }, main);
        } catch (Exception e) {
            failed(token);
        }
    }

    private void configure(CameraDevice device, int token) {
        try {
            Surface target = surface;
            device.createCaptureSession(Collections.singletonList(target), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession ready) {
                    if (token != generation) {
                        ready.close();
                        return;
                    }
                    session = ready;
                    try {
                        startContinuousFocus(device, ready, target);
                        firstFrame = true;
                    } catch (Exception e) {
                        failed(token);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession rejected) {
                    rejected.close();
                    failed(token);
                }
            }, main);
        } catch (Exception e) {
            failed(token);
        }
    }

    private Size chooseSize(Size[] choices) {
        if (choices == null || choices.length == 0) throw new IllegalStateException("No camera preview sizes");
        Size best = choices[0];
        double bestScore = Double.MAX_VALUE;
        for (Size option : choices) {
            double ratio = (double) option.getWidth() / option.getHeight();
            double score = 4 * Math.abs(ratio - 4.0 / 3) + Math.abs(Math.log(option.getWidth() / 1440.0));
            if (score < bestScore) {
                best = option;
                bestScore = score;
            }
        }
        return best;
    }

    void setFraming(double aspect, int manualRotation, boolean polaroid) {
        this.aspect = aspect;
        this.manualRotation = manualRotation;
        this.polaroid = polaroid;
        transform();
    }

    void setViewfinderInsets(int left, int top, int right, int bottom) {
        viewfinderInsets.set(left, top, right, bottom);
        transform();
    }

    Surface prepareRecordingPreview(int width, int height, int fps, int preferredPreviewWidth, int previewFps) {
        stop();
        SurfaceTexture texture = view.getSurfaceTexture();
        if (texture == null || streamMap == null) return null;
        Size recordingSize = new Size(width, height);
        if (fps >= 120) {
            size = recordingSize;
        } else {
            Size[] previews = streamMap.getOutputSizes(SurfaceTexture.class);
            if (previews == null) return null;
            double targetAspect = (double) width / height;
            size = java.util.Arrays.stream(previews)
                .filter(option -> option.getWidth() <= 1280 && option.getHeight() <= 720
                    && streamMap.getOutputMinFrameDuration(SurfaceTexture.class, option) <= 1_000_000_000L / previewFps + 1)
                .min(java.util.Comparator.comparingDouble(option ->
                    4 * Math.abs((double) option.getWidth() / option.getHeight() - targetAspect)
                        + Math.abs(Math.log(option.getWidth() / (double) preferredPreviewWidth))))
                .orElse(null);
            if (size == null) return null;
        }
        texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        transform();
        return new Surface(texture);
    }

    Size recordingPreviewSize() { return size; }

    static int displayRotation(View view) {
        if (view.getDisplay() == null) return 0;
        switch (view.getDisplay().getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private void transform() {
        int width = view.getWidth(), height = view.getHeight();
        if (size == null || width == 0 || height == 0) return;
        int display = displayRotation(view);
        int rotation = -display + manualRotation;
        // Camera buffers already carry the sensor-to-natural-orientation transform.
        boolean sensorSwapped = sensorOrientation % 180 != 0;
        float sourceWidth = sensorSwapped ? size.getHeight() : size.getWidth();
        float sourceHeight = sensorSwapped ? size.getWidth() : size.getHeight();
        boolean swapped = (sensorOrientation + (frontFacing ? display : -display) + manualRotation) % 180 != 0;
        float cropWidth = Math.min(size.getWidth(), (float) (size.getHeight() * aspect));
        float cropHeight = Math.min(size.getHeight(), (float) (size.getWidth() / aspect));
        float framedWidth = swapped ? cropHeight : cropWidth;
        float framedHeight = swapped ? cropWidth : cropHeight;
        float mountedWidth = framedWidth, mountedHeight = framedHeight, photoOffsetY = 0;
        if (polaroid) {
            PhotoGeometry.Polaroid layout = PhotoGeometry.polaroid(Math.round(framedWidth), Math.round(framedHeight));
            mountedWidth = layout.width();
            mountedHeight = layout.height();
            photoOffsetY = layout.top() + (framedHeight - mountedHeight) / 2;
        }
        int availableWidth = width - viewfinderInsets.left - viewfinderInsets.right;
        int availableHeight = height - viewfinderInsets.top - viewfinderInsets.bottom;
        if (availableWidth <= 0 || availableHeight <= 0) return;
        float centerX = viewfinderInsets.left + availableWidth / 2f;
        float mountCenterY = viewfinderInsets.top + availableHeight / 2f;
        float scale = Math.min(availableWidth / mountedWidth, availableHeight / mountedHeight);
        float centerY = mountCenterY + photoOffsetY * scale;
        Matrix matrix = new Matrix();
        matrix.setScale(sourceWidth / width, sourceHeight / height);
        matrix.postTranslate(-sourceWidth / 2f, -sourceHeight / 2f);
        matrix.postRotate(rotation);
        matrix.postScale(scale, scale);
        matrix.postTranslate(centerX, centerY);
        if (focusMagnified) matrix.postScale(2f, 2f, centerX, centerY);
        view.setTransform(matrix);
        int left = Math.round(centerX - framedWidth * scale / 2f);
        int right = Math.round(centerX + framedWidth * scale / 2f);
        int top = Math.round(centerY - framedHeight * scale / 2f);
        int bottom = Math.round(centerY + framedHeight * scale / 2f);
        Rect frame = new Rect(left, top, right, bottom);
        view.setClipBounds(frame);
        frameBounds.set(frame);
        Matrix sensorToView = new Matrix();
        sensorToView.setScale(size.getWidth(), size.getHeight());
        sensorToView.postTranslate(-size.getWidth() / 2f, -size.getHeight() / 2f);
        sensorToView.postRotate(sensorOrientation);
        if (frontFacing) sensorToView.postScale(-1, 1);
        sensorToView.postRotate(rotation);
        sensorToView.postScale(scale, scale);
        sensorToView.postTranslate(centerX, centerY);
        sensorToView.invert(touchToSensor);
        int mountLeft = Math.round(centerX - mountedWidth * scale / 2f);
        int mountRight = Math.round(centerX + mountedWidth * scale / 2f);
        int mountTop = Math.round(mountCenterY - mountedHeight * scale / 2f);
        int mountBottom = Math.round(mountCenterY + mountedHeight * scale / 2f);
        listener.onFraming(frame, new Rect(mountLeft, mountTop, mountRight, mountBottom));
    }

    void setRecordingCameraGeometry(CameraCharacteristics properties) {
        Integer orientation = properties.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = properties.get(CameraCharacteristics.LENS_FACING);
        sensorOrientation = orientation == null ? 0 : orientation;
        frontFacing = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        transform();
    }

    FocusPoint focusPoint() { return focusPoint; }

    Float measuredFocus() { return measuredFocus; }

    void setFocusMagnified(boolean enabled) {
        if (focusMagnified == enabled) return;
        focusMagnified = enabled;
        transform();
    }

    void setManualFocus(String cameraId, Float diopters) {
        if (java.util.Objects.equals(manualFocus, diopters)) return;
        manualFocus = diopters;
        if (repeating == null || session == null || !cameraId.equals(requestedId)) return;
        focusGeneration++;
        focusPoint = null;
        try {
            if (diopters == null) {
                startContinuousFocus(camera, session, surface);
            } else {
                repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                repeating.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                repeating.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters);
                session.setRepeatingRequest(repeating.build(), repeatingCallback, main);
            }
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            failed(generation);
        }
    }

    boolean hasFocusControl() { return repeating != null && continuousFocusSupported && focusSupported; }

    ActionExposure exposure() { return exposure; }

    WhiteBalance.Snapshot lockedWhiteBalance() { return lockedWhiteBalance; }

    void setWhiteBalance(String cameraId, WhiteBalance choice) {
        if (whiteBalance == choice) return;
        whiteBalance = choice;
        lockedWhiteBalance = null;
        if (repeating == null || session == null || !cameraId.equals(requestedId)) return;
        try {
            choice.preview(repeating, cameraProperties);
            session.setRepeatingRequest(repeating.build(), repeatingCallback, main);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            failed(generation);
        }
    }

    void setAdjustments(String cameraId, CameraAdjustments value) {
        if (adjustments.equals(value)) return;
        adjustments = value;
        if (repeating == null || session == null || !cameraId.equals(requestedId)) return;
        try {
            adjustments.apply(repeating, cameraProperties, 0);
            session.setRepeatingRequest(repeating.build(), repeatingCallback, main);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            failed(generation);
        }
    }

    void setZoomRatio(String cameraId, float value) {
        if (zoomRatio == value) return;
        zoomRatio = value;
        if (repeating == null || session == null || !cameraId.equals(requestedId)) return;
        try {
            repeating.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
            session.setRepeatingRequest(repeating.build(), repeatingCallback, main);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            failed(generation);
        }
    }

    private void readPreviewMetadata(TotalCaptureResult result) {
        measuredFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (whiteBalance == WhiteBalance.LOCKED) lockedWhiteBalance = WhiteBalance.Snapshot.locked(result);
        sensorCrop = result.get(CaptureResult.SCALER_CROP_REGION);
        Long time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (time != null && time > 0 && iso != null && iso > 0) exposure = new ActionExposure(time, iso);
    }

    private void startContinuousFocus(CameraDevice device, CameraCaptureSession ready, Surface target)
            throws android.hardware.camera2.CameraAccessException {
        CaptureRequest.Builder automatic = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        automatic.addTarget(target);
        if (continuousFocusSupported) {
            automatic.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        Float maxFocus = cameraProperties.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (manualFocus != null && maxFocus != null && maxFocus > 0) {
            automatic.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            automatic.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.min(maxFocus, manualFocus));
        }
        if (!adjustments.equals(CameraAdjustments.DEFAULT)) adjustments.apply(automatic, cameraProperties, 0);
        if (whiteBalance != WhiteBalance.AUTO) whiteBalance.preview(automatic, cameraProperties);
        if (zoomRatio != 1f) automatic.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
        CameraCaptureSession.CaptureCallback results = new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                    TotalCaptureResult result) {
                if (session == LivePreview.this.session) readPreviewMetadata(result);
            }
        };
        ready.setRepeatingRequest(automatic.build(), results, main);
        repeatingCallback = results;
        repeating = automatic;
    }

    boolean resetFocus() {
        if (session == null || repeating == null || focusPoint == null) return false;
        try {
            repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            session.capture(repeating.build(), null, main);
            startContinuousFocus(camera, session, surface);
            focusGeneration++;
            focusPoint = null;
            return true;
        } catch (Exception unavailable) {
            failed(generation);
            return false;
        }
    }

    boolean focusAt(float x, float y, Consumer<Boolean> finished) {
        if (manualFocus != null || session == null || repeating == null || !focusSupported
                || !frameBounds.contains((int) x, (int) y)) return false;
        float[] position = {x, y};
        touchToSensor.mapPoints(position);
        RectF stream = new RectF(sensorCrop == null ? activeSensor : sensorCrop);
        float streamAspect = (float) size.getWidth() / size.getHeight();
        if (stream.width() / stream.height() > streamAspect) {
            stream.inset((stream.width() - stream.height() * streamAspect) / 2, 0);
        } else {
            stream.inset(0, (stream.height() - stream.width() / streamAspect) / 2);
        }
        FocusPoint point = new FocusPoint(
            (stream.left + position[0] * stream.width() - activeSensor.left) / activeSensor.width(),
            (stream.top + position[1] * stream.height() - activeSensor.top) / activeSensor.height());
        int token = ++focusGeneration;
        CameraCaptureSession current = session;
        try {
            point.apply(repeating, activeSensor, CaptureRequest.CONTROL_AF_MODE_AUTO);
            repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            current.capture(repeating.build(), null, main);
            repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            CaptureRequest trigger = repeating.build();
            repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            CameraCaptureSession.CaptureCallback results = new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                        TotalCaptureResult result) {
                    if (current != LivePreview.this.session) return;
                    readPreviewMetadata(result);
                    if (token != focusGeneration) return;
                    Integer state = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (FocusPoint.isTerminal(state)) {
                        focusGeneration++;
                        finished.accept(state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED);
                    }
                }
            };
            current.capture(trigger, results, main);
            current.setRepeatingRequest(repeating.build(), results, main);
            repeatingCallback = results;
            focusPoint = point;
            main.postDelayed(() -> {
                if (token != focusGeneration) return;
                focusGeneration++;
                finished.accept(false);
            }, 2200);
            return true;
        } catch (Exception e) {
            focusGeneration++;
            return false;
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) { open(texture); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { transform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        String id = requestedId;
        stop();
        requestedId = id;
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (firstFrame) {
            firstFrame = false;
            listener.onFrame();
        }
    }
}
