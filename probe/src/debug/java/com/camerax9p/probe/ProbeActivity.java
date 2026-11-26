package com.camerax9p.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.ScrollView;
import org.json.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Public API inventory; no photography, networking, gallery access or persistent identifiers. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setPadding(32, 72, 32, 32);
        ScrollView scroll = new ScrollView(this); scroll.addView(text); setContentView(scroll);
        if (getIntent().getBooleanExtra("sessionquery",false) || getIntent().getBooleanExtra("legacy",false)) {
            text.setText(R.string.probe_running);
            new Thread(() -> {
                String result=getIntent().getBooleanExtra("legacy",false)?LegacyProbe.run(this):SessionPlanProbe.run(this);
                runOnUiThread(() -> text.setText(result));
            },"resolution-routes").start();
            return;
        }
        if (getIntent().getBooleanExtra("cameraunit", false)) {
            text.setText(R.string.probe_cameraunit);
            new Thread(() -> { String result=CameraUnitProbe.run(this); runOnUiThread(() -> text.setText(result)); },"cameraunit-check").start();
            return;
        }
        if (getIntent().getBooleanExtra("advanced", false)) {
            text.setText(R.string.probe_characteristics);
            new Thread(() -> {
                String result = AdvancedProbe.run(this);
                runOnUiThread(() -> text.setText(result));
            }, "advanced-inventory").start();
            return;
        }
        if (getIntent().hasExtra("camera")) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                text.setText(R.string.probe_camera_permission);
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 10);
                return;
            }
            text.setText(R.string.probe_capturing);
            if (getIntent().getBooleanExtra("highSpeedPreview", false)) {
                TextureView preview = new TextureView(this);
                preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                    private boolean started;

                    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                        if (started) return;
                        started = true;
                        texture.setDefaultBufferSize(
                            getIntent().getIntExtra("previewWidth", getIntent().getIntExtra("width", 1920)),
                            getIntent().getIntExtra("previewHeight", getIntent().getIntExtra("height", 1080)));
                        startCapture(scroll, text, new Surface(texture));
                    }
                    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
                    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { return true; }
                    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
                });
                setContentView(preview);
            } else {
                startCapture(scroll, text, null);
            }
            return;
        }
        try {
            CameraManager manager = getSystemService(CameraManager.class);
            JSONObject report = new JSONObject();
            report.put("model", Build.MODEL).put("android", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT).put("build", Build.DISPLAY);
            JSONArray cameras = new JSONArray();
            Set<String> publicIds = new LinkedHashSet<>(Arrays.asList(manager.getCameraIdList()));
            Set<String> all = new LinkedHashSet<>(publicIds);
            for (String id : publicIds) all.addAll(manager.getCameraCharacteristics(id).getPhysicalCameraIds());
            for (String id : all) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                JSONObject camera = new JSONObject().put("id", id).put("public", publicIds.contains(id));
                camera.put("physicalIds", new JSONArray(c.getPhysicalCameraIds()));
                JSONObject metadata = new JSONObject();
                for (CameraCharacteristics.Key<?> key : c.getKeys()) {
                    if (key.getName().startsWith("android.")) metadata.put(key.getName(), printable(c.get(key)));
                }
                camera.put("metadata", metadata);
                camera.put("default", streams(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)));
                camera.put("maximumResolution", streams(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)));
                cameras.put(camera);
            }
            report.put("cameras", cameras);
            File file = new File(getFilesDir(), "capabilities.json");
            Files.write(file.toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
            text.setText(getString(R.string.probe_completed, all.size(), report.toString(2)));
        } catch (Exception e) { text.setText(getString(R.string.probe_failed, e)); android.util.Log.e("CameraX9P", "Probe failed", e); }
    }
    private void startCapture(ScrollView scroll, TextView text, Surface preview) {
        Intent config = getIntent();
        boolean measure = config.getBooleanExtra("measureVideoCadence", false)
            && config.getBooleanExtra("video", false);
        if (measure) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            try {
                String result = measure
                    ? CaptureProbe.measureVideoCadence(this, config, preview, config.getBooleanExtra("hevc", false),
                        config.getIntExtra("recordSeconds", 30))
                    : CaptureProbe.run(this, config, preview);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (measure) getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (preview != null) setContentView(scroll);
                    text.setText(result);
                });
            } finally {
                if (preview != null) preview.release();
            }
        }, "capture-test").start();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 10 && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) recreate();
    }
    private Object printable(Object object) throws JSONException {
        if (object == null) return JSONObject.NULL;
        if (object.getClass().isArray()) {
            JSONArray result = new JSONArray();
            for (int i = 0; i < java.lang.reflect.Array.getLength(object); i++) result.put(printable(java.lang.reflect.Array.get(object, i)));
            return result;
        }
        if (object instanceof Integer || object instanceof Long || object instanceof Short || object instanceof Byte || object instanceof Boolean || object instanceof String) return object;
        if (object instanceof Float && Float.isFinite((Float) object)) return object;
        if (object instanceof Double && Double.isFinite((Double) object)) return object;
        return object.toString();
    }
    private JSONObject streams(StreamConfigurationMap map) throws JSONException {
        if (map == null) return null;
        JSONObject result = new JSONObject();
        for (int format : new int[]{ImageFormat.JPEG, ImageFormat.YUV_420_888, ImageFormat.RAW_SENSOR, ImageFormat.HEIC}) {
            JSONObject sizes = new JSONObject();
            sizes.put("normal", sizes(map.getOutputSizes(format)));
            sizes.put("highResolution", sizes(map.getHighResolutionOutputSizes(format)));
            result.put(Integer.toString(format), sizes);
        }
        JSONArray video = new JSONArray();
        Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
        if (videoSizes != null) for (Size size : videoSizes) {
            video.put(new JSONObject().put("width", size.getWidth()).put("height", size.getHeight())
                .put("minFrameDurationNs", map.getOutputMinFrameDuration(MediaRecorder.class, size)));
        }
        result.put("video", video);
        JSONArray highSpeed = new JSONArray();
        for (Size size : map.getHighSpeedVideoSizes()) highSpeed.put(new JSONObject().put("size", size.toString()).put("fps", printable(map.getHighSpeedVideoFpsRangesFor(size))));
        result.put("highSpeed", highSpeed);
        return result;
    }
    private JSONArray sizes(Size[] sizes) throws JSONException {
        JSONArray result = new JSONArray();
        if (sizes != null) for (Size size : sizes) result.put(new JSONObject().put("width", size.getWidth()).put("height", size.getHeight()).put("mp", size.getWidth() * (long) size.getHeight() / 1000000.0));
        return result;
    }
}
