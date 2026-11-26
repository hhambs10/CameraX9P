package com.camerax9p.probe;

import android.content.Context;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Effect;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@androidx.annotation.OptIn(markerClass = UnstableApi.class)
final class VideoExport {
    private VideoExport() {}

    static void write(Context context, File source, File destination, RollTrack.Timeline roll, boolean portrait)
            throws Exception {
        write(context, source, destination, List.of(new LevelFrame(roll),
            Presentation.createForWidthAndHeight(portrait ? 1080 : 1920, portrait ? 1920 : 1080,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)));
    }

    static void write(Context context, File source, File destination, LutGrade grade) throws Exception {
        write(context, source, destination, grade.videoEffects());
    }

    static void join(Context context, List<File> clips, File destination, int width, int height) throws Exception {
        List<EditedMediaItem> items = new ArrayList<>(clips.size());
        long duration = 0;
        for (File clip : clips) {
            duration += durationMs(clip);
            items.add(new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(clip)))
                .setEffects(new Effects(List.of(), List.of(Presentation.createForWidthAndHeight(width, height,
                    Presentation.LAYOUT_SCALE_TO_FIT))))
                .build());
        }
        Composition composition = new Composition.Builder(new EditedMediaItemSequence.Builder(items).build()).build();
        export(context, destination, composition, duration);
    }

    static void checkVideo(File file, int width, int height, boolean audio) throws IOException {
        try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
            metadata.setDataSource(file.getAbsolutePath());
            int actualWidth = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int actualHeight = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rotation % 180 != 0) {
                int swap = actualWidth;
                actualWidth = actualHeight;
                actualHeight = swap;
            }
            boolean hasAudio = "yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            if (actualWidth != width || actualHeight != height || hasAudio != audio) {
                throw new IOException("Exported video has unexpected dimensions or audio");
            }
        }
    }

    private static void write(Context context, File source, File destination, List<Effect> effects) throws Exception {
        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
            .setEffects(new Effects(List.of(), effects)).build();
        Composition composition = new Composition.Builder(new EditedMediaItemSequence.Builder(item).build()).build();
        export(context, destination, composition, durationMs(source));
    }

    private static long durationMs(File source) throws IOException {
        try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
            metadata.setDataSource(source.getAbsolutePath());
            String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration == null ? 0 : Long.parseLong(duration);
        }
    }

    private static void export(Context context, File destination, Composition composition, long durationMs) throws Exception {
        long timeoutMs = Math.max(TimeUnit.MINUTES.toMillis(5), 3 * durationMs);
        HandlerThread exportThread = new HandlerThread("video-export");
        exportThread.start();
        Handler handler = new Handler(exportThread.getLooper());
        java.util.concurrent.atomic.AtomicReference<Transformer> exporter = new java.util.concurrent.atomic.AtomicReference<>();
        CompletableFuture<ExportResult> result = new CompletableFuture<>();
        handler.post(() -> {
            try {
                Transformer transformer = new Transformer.Builder(context.getApplicationContext())
                    .setVideoFrameProcessorFactory(new DefaultVideoFrameProcessor.Factory.Builder()
                        .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL).build())
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition composition, ExportResult output) {
                            result.complete(output);
                        }

                        @Override public void onError(Composition composition, ExportResult output, ExportException failure) {
                            result.completeExceptionally(failure);
                        }
                    }).build();
                exporter.set(transformer);
                transformer.start(composition, destination.getAbsolutePath());
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            result.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException interrupted) {
            handler.post(() -> {
                Transformer active = exporter.get();
                if (active != null) active.cancel();
            });
            throw interrupted;
        } finally {
            exportThread.quitSafely();
        }
    }

    private static final class LevelFrame implements MatrixTransformation {
        private final RollTrack.Timeline roll;
        private float aspect, scale;

        LevelFrame(RollTrack.Timeline roll) { this.roll = roll; }

        @Override public Size configure(int inputWidth, int inputHeight) {
            aspect = (float) inputWidth / inputHeight;
            // Fit the circumscribed circle of the output inside the shortest input edge for any roll.
            scale = (float) (Math.hypot(inputWidth, inputHeight) / Math.min(inputWidth, inputHeight) * 1.01);
            return new Size(inputWidth, inputHeight);
        }

        @Override public Matrix getMatrix(long presentationTimeUs) {
            Matrix transform = new Matrix();
            transform.setScale(aspect, 1);
            transform.postRotate((float) roll.degreesAt(presentationTimeUs));
            transform.postScale(scale / aspect, scale);
            return transform;
        }
    }
}
