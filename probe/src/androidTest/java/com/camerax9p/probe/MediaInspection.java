package com.camerax9p.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Environment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** On-device inspection of QA files created by CameraX9P. */
public final class MediaInspection extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if ("true".equals(arguments.getString("captureReport"))) {
                File report = new File(getTargetContext().getFilesDir(), "capture-result.json");
                String json = new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
                result.putString("stream", "\n" + json + "\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            JSONArray files = new JSONArray();
            boolean cadence = "true".equals(arguments.getString("cadence"));
            for (String source : arguments.getString("files", "").split(",")) {
                if (source.isEmpty()) continue;
                String[] parts = source.split(":", -1);
                if (parts.length != 2 || !parts[1].matches("[A-Za-z0-9_+.\\-]+")) throw new IllegalArgumentException(source);
                File parent = switch (parts[0]) {
                    case "private" -> getTargetContext().getFilesDir();
                    case "video" -> new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CameraX9P");
                    case "photo" -> new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CameraX9P");
                    default -> throw new IllegalArgumentException(source);
                };
                File file = new File(parent, parts[1]);
                JSONObject inspected = file.getName().endsWith(".mp4") ? video(file, cadence) : photo(file);
                files.put(inspected.put("file", file.getName()));
            }
            result.putString("stream", "\n" + files.toString(2) + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception failed) {
            result.putString("stream", "\n" + failed + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static JSONObject photo(File file) throws Exception {
        ExifInterface exif = new ExifInterface(file.getPath());
        JSONObject result = new JSONObject()
            .put("exposureSeconds", exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1))
            .put("iso", exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, -1))
            .put("focalMm", exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1))
            .put("width", exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, -1))
            .put("height", exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, -1))
            .put("orientation", exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1));
        if (file.getName().endsWith(".jpg")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 16;
            Bitmap image = BitmapFactory.decodeFile(file.getPath(), options);
            if (image != null) {
                try { result.put("meanChannelSpread", meanChannelSpread(image)); }
                finally { image.recycle(); }
            }
        }
        return result;
    }

    private static JSONObject video(File file, boolean cadence) throws Exception {
        try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
            metadata.setDataSource(file.getPath());
            long duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000;
            Bitmap tail = metadata.getScaledFrameAtTime(Math.max(0, duration - 500_000L),
                MediaMetadataRetriever.OPTION_CLOSEST, 160, 160);
            double meanChroma = tail == null ? -1 : meanChannelSpread(tail);
            if (tail != null) tail.recycle();
            JSONArray tracks = new JSONArray();
            for (int i = 0; ; i++) {
                MediaExtractor media = new MediaExtractor();
                try {
                    media.setDataSource(file.getPath());
                    if (i >= media.getTrackCount()) break;
                    media.selectTrack(i);
                    MediaFormat format = media.getTrackFormat(i);
                    long first = media.getSampleTime();
                    long trackDuration = format.getLong(MediaFormat.KEY_DURATION);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    Map<Long, Integer> framesPerWindow = cadence && mime.startsWith("video/") ? new TreeMap<>() : null;
                    long windowUs = 10_000_000L;
                    if (framesPerWindow == null) {
                        media.seekTo(Math.max(0, duration - 3_000_000L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    }
                    long last = media.getSampleTime();
                    do {
                        long time = media.getSampleTime();
                        if (time < 0) break;
                        last = Math.max(last, time);
                        if (framesPerWindow != null) {
                            framesPerWindow.merge(Math.max(0, time - first) / windowUs, 1, Integer::sum);
                        }
                    } while (media.advance());
                    JSONObject track = new JSONObject().put("mime", mime).put("firstSampleUs", first)
                        .put("lastSampleUs", last).put("durationUs", trackDuration);
                    if (framesPerWindow != null) {
                        JSONArray windows = new JSONArray();
                        for (long base = 0; base < trackDuration; base += windowUs) {
                            int count = framesPerWindow.getOrDefault(base / windowUs, 0);
                            long span = Math.min(windowUs, trackDuration - base);
                            windows.put(new JSONObject().put("startSeconds", base / 1_000_000)
                                .put("frames", count).put("fps", 1_000_000d * count / span));
                        }
                        track.put("cadenceWindows", windows);
                    }
                    tracks.put(track);
                } finally {
                    media.release();
                }
            }
            return new JSONObject().put("durationUs", duration).put("nearEndDecoded", tail != null)
                .put("nearEndMeanChannelSpread", meanChroma)
                .put("frames", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT))
                .put("tracks", tracks);
        }
    }

    private static double meanChannelSpread(Bitmap bitmap) {
        long spread = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int color = bitmap.getPixel(x, y);
                int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
                spread += Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
            }
        }
        return (double) spread / (bitmap.getHeight() * bitmap.getWidth());
    }
}
