package com.camerax9p.probe;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import com.camerax9p.core.PhotoGeometry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONObject;

final class PhotoExport {
    private PhotoExport() {}

    static void reframe(File source, File destination, PhotoGeometry.Crop crop, int rotation, String caption,
            LutGrade grade) throws IOException {
        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(source.getPath());
        if (decoder == null) throw new IOException("JPEG non leggibile");
        try {
            Rect region = new Rect(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inMutable = grade != null;
            if (grade != null) options.inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB);
            Bitmap bitmap = decoder.decodeRegion(region, options);
            if (bitmap == null) throw new IOException("Ritaglio non riuscito");
            if (grade != null && !bitmap.isMutable()) {
                Bitmap decoded = bitmap;
                try {
                    bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true);
                } finally {
                    decoded.recycle();
                }
                if (bitmap == null) throw new IOException("Immagine modificabile non disponibile");
            }
            Bitmap output = bitmap;
            try {
                if (grade != null) grade.apply(bitmap);
                if (caption != null) {
                    output = mountPolaroid(bitmap, rotation, caption);
                } else if (rotation != 0) {
                    Matrix matrix = new Matrix();
                    matrix.setRotate(rotation);
                    output = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
                }
                try (OutputStream stream = new FileOutputStream(destination)) {
                    if (!output.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                        throw new IOException("Codifica JPEG non riuscita");
                    }
                }
            } finally {
                if (output != bitmap) output.recycle();
                bitmap.recycle();
            }
        } finally {
            decoder.recycle();
        }
    }

    private static Bitmap mountPolaroid(Bitmap photo, int rotation, String caption) {
        boolean swapped = rotation % 180 != 0;
        int width = swapped ? photo.getHeight() : photo.getWidth();
        int height = swapped ? photo.getWidth() : photo.getHeight();
        PhotoGeometry.Polaroid layout = PhotoGeometry.polaroid(width, height);
        Bitmap mounted = Bitmap.createBitmap(layout.width(), layout.height(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mounted);
        canvas.drawColor(Color.WHITE);
        Matrix transform = new Matrix();
        transform.setTranslate(-photo.getWidth() / 2f, -photo.getHeight() / 2f);
        transform.postRotate(rotation);
        transform.postTranslate(layout.left() + width / 2f, layout.top() + height / 2f);
        canvas.drawBitmap(photo, transform, new Paint(Paint.FILTER_BITMAP_FLAG));
        PolaroidCaption.draw(canvas, caption, 0, layout.top() + height, layout.width(), layout.height());
        return mounted;
    }

    static void preserveExif(File source, File destination, JSONObject capture) throws IOException {
        ExifInterface original = new ExifInterface(source.getPath());
        ExifInterface output = new ExifInterface(destination.getPath());
        for (String tag : new String[]{ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH}) {
            String value = original.getAttribute(tag);
            if (value != null) output.setAttribute(tag, value);
        }
        long exposureNs = capture.optLong("exposureNs");
        int iso = capture.optInt("iso");
        double focalLength = capture.optDouble("focalLength");
        if (exposureNs > 0) output.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, Double.toString(exposureNs / 1e9));
        if (iso > 0) output.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, Integer.toString(iso));
        if (focalLength > 0) output.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, Math.round(focalLength * 1000) + "/1000");

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(destination.getPath(), bounds);
        String width = Integer.toString(bounds.outWidth), height = Integer.toString(bounds.outHeight);
        output.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width);
        output.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height);
        output.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width);
        output.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height);
        output.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_NORMAL));
        output.saveAttributes();
    }

    static String publish(ContentResolver resolver, File jpeg, File raw, boolean xpan) throws IOException {
        String dimensions = "";
        if (jpeg != null) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(jpeg.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Dimensioni JPEG non valide");
            dimensions = bounds.outWidth + "×" + bounds.outHeight;
        }
        String name = (xpan ? "CameraX9P_XPAN_" : "CameraX9P_") + System.currentTimeMillis();
        Uri jpegUri = null, rawUri = null;
        boolean published = false;
        try {
            if (jpeg != null) jpegUri = stageImage(resolver, jpeg, name + ".jpg", "image/jpeg");
            if (raw != null) rawUri = stageImage(resolver, raw, name + ".dng", "image/x-adobe-dng");
            if (jpegUri != null) finishMedia(resolver, jpegUri);
            if (rawUri != null) finishMedia(resolver, rawUri);
            published = true;
            return dimensions;
        } finally {
            if (!published) {
                if (jpegUri != null) resolver.delete(jpegUri, null, null);
                if (rawUri != null) resolver.delete(rawUri, null, null);
            }
        }
    }

    static void publishBracket(ContentResolver resolver, List<File> exposures) throws IOException {
        String name = "CameraX9P_BRACKET_" + System.currentTimeMillis();
        String[] suffixes = {"_EV-2.jpg", "_EV0.jpg", "_EV+2.jpg"};
        Uri[] outputs = new Uri[3];
        boolean complete = false;
        try {
            for (int i = 0; i < outputs.length; i++) {
                outputs[i] = stageImage(resolver, exposures.get(i), name + suffixes[i], "image/jpeg");
            }
            for (Uri output : outputs) finishMedia(resolver, output);
            complete = true;
        } finally {
            if (!complete) for (Uri output : outputs) if (output != null) resolver.delete(output, null, null);
        }
    }

    private static Uri stageImage(ContentResolver resolver, File source, String name, String mime) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CameraX9P");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        return stageMedia(resolver, source, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    }

    static void publishClip(ContentResolver resolver, File source) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "CameraX9P_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CameraX9P");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = stageMedia(resolver, source, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        try {
            finishMedia(resolver, uri);
        } catch (IOException | RuntimeException failure) {
            resolver.delete(uri, null, null);
            throw failure;
        }
    }

    private static Uri stageMedia(ContentResolver resolver, File source, Uri collection, ContentValues values)
        throws IOException {
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("Impossibile creare il file nella galleria");

        try (OutputStream stream = resolver.openOutputStream(uri, "w")) {
            if (stream == null) throw new IOException("Impossibile scrivere il file");
            Files.copy(source.toPath(), stream);
        } catch (IOException | RuntimeException failure) {
            resolver.delete(uri, null, null);
            throw failure;
        }
        return uri;
    }

    private static void finishMedia(ContentResolver resolver, Uri uri) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        if (resolver.update(uri, values, null, null) != 1) throw new IOException("Pubblicazione del file non riuscita");
    }
}
