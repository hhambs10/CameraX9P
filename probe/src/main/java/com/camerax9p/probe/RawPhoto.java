package com.camerax9p.probe;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ExifInterface;
import android.util.Size;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

final class RawPhoto {
    private RawPhoto() {}

    static Size size(CameraCharacteristics camera) {
        int[] capabilities = camera.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities == null || Arrays.stream(capabilities).noneMatch(
                capability -> capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return null;
        StreamConfigurationMap map = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.RAW_SENSOR);
        if (sizes == null) return null;
        return Arrays.stream(sizes).max(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()))
            .orElse(null);
    }

    static void write(File file, CameraCharacteristics camera, CaptureResult result, Image image, int rotation)
            throws IOException {
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (timestamp != null && timestamp != image.getTimestamp()) {
            throw new IOException("I metadati RAW appartengono a un altro fotogramma");
        }
        int orientation = switch (rotation) {
            case 90 -> ExifInterface.ORIENTATION_ROTATE_90;
            case 180 -> ExifInterface.ORIENTATION_ROTATE_180;
            case 270 -> ExifInterface.ORIENTATION_ROTATE_270;
            default -> ExifInterface.ORIENTATION_NORMAL;
        };
        try (DngCreator creator = new DngCreator(camera, result);
             FileOutputStream output = new FileOutputStream(file)) {
            creator.setOrientation(orientation);
            creator.writeImage(output, image);
        }
    }
}
