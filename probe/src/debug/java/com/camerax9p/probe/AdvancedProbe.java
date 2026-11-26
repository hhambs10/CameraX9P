package com.camerax9p.probe;

import android.content.Context;
import android.hardware.camera2.*;
import android.graphics.ImageFormat;
import android.util.Size;
import org.json.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class AdvancedProbe {
    static String run(Context context) {
        JSONObject report = new JSONObject();
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            Set<String> publicIds = new LinkedHashSet<>(Arrays.asList(manager.getCameraIdList()));
            report.put("publicIds", new JSONArray(publicIds));
            JSONArray cameras = new JSONArray();
            // IDs 0..7 were observed in the device's camera service dump; no identity spoofing.
            for (int number = 0; number <= 7; number++) {
                String id = Integer.toString(number);
                JSONObject item = new JSONObject().put("id", id).put("enumerated", publicIds.contains(id));
                try {
                    CameraCharacteristics c = manager.getCameraCharacteristics(id);
                    JSONObject vendor = new JSONObject();
                    for (CameraCharacteristics.Key<?> key : c.getKeys()) {
                        if (!key.getName().startsWith("android.")) {
                            try { vendor.put(key.getName(), value(c.get(key))); }
                            catch (Exception e) { vendor.put(key.getName(), e.toString()); }
                        }
                    }
                    item.put("vendor", vendor);
                    JSONObject explicitVendor = new JSONObject();
                    for (String name : new String[]{"com.oplus.custom.jpeg.size", "com.oplus.custom.sensor.info.pixel.array.size", "com.oplus.custom.support.raw.size", "com.oplus.custom.support.raw.200m.size"}) {
                        try { explicitVendor.put(name, value(c.get(new CameraCharacteristics.Key<int[]>(name, int[].class)))); }
                        catch (Exception e) { explicitVendor.put(name, e.toString()); }
                    }
                    item.put("explicitVendor", explicitVendor);
                    JSONArray requestKeys = new JSONArray();
                    for (CaptureRequest.Key<?> key : c.getAvailableCaptureRequestKeys()) requestKeys.put(key.getName());
                    item.put("requestKeys", requestKeys);
                    JSONObject formats = new JSONObject();
                    android.hardware.camera2.params.StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    for (int format : new int[]{ImageFormat.JPEG, ImageFormat.HEIC, ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12, ImageFormat.YUV_420_888}) {
                        JSONArray sizes = new JSONArray();
                        Size[] normal = map.getOutputSizes(format), high = map.getHighResolutionOutputSizes(format);
                        if (normal != null) for (Size s : normal) sizes.put(s.toString());
                        if (high != null) for (Size s : high) sizes.put(s.toString());
                        formats.put(Integer.toString(format), sizes);
                    }
                    item.put("formats", formats);
                    try {
                        CameraExtensionCharacteristics extensions = manager.getCameraExtensionCharacteristics(id);
                        JSONArray modes = new JSONArray();
                        for (int mode : extensions.getSupportedExtensions()) {
                            JSONObject extension = new JSONObject().put("mode", mode);
                            JSONArray sizes = new JSONArray();
                            for (Size s : extensions.getExtensionSupportedSizes(mode, ImageFormat.JPEG)) sizes.put(s.toString());
                            extension.put("jpegSizes", sizes); modes.put(extension);
                        }
                        item.put("extensions", modes);
                    } catch (Exception e) { item.put("extensionError", e.toString()); }
                } catch (Exception e) { item.put("error", e.toString()); }
                cameras.put(item);
            }
            report.put("cameras", cameras);
            Files.write(new File(context.getFilesDir(), "advanced.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return "Advanced probe failed: " + e; }
        return report.toString();
    }
    private static Object value(Object v) throws JSONException {
        if (v == null) return JSONObject.NULL;
        if (v.getClass().isArray()) {
            JSONArray array = new JSONArray();
            for (int i = 0; i < java.lang.reflect.Array.getLength(v); i++) array.put(value(java.lang.reflect.Array.get(v, i)));
            return array;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Byte || v instanceof Boolean) return v;
        return v.toString();
    }
}
