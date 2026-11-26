package com.camerax9p.probe;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

final class StarVideoWriter implements AutoCloseable {
    private final MediaCodec encoder;
    private final MediaMuxer muxer;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final byte[] row = new byte[StarLapse.WIDTH];
    private int track = -1;
    private boolean ended;

    StarVideoWriter(File destination, int rotation) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
            StarLapse.WIDTH, StarLapse.HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 14_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, StarLapse.FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaMuxer openedMuxer = null;
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            openedMuxer = new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            openedMuxer.setOrientationHint(rotation);
            encoder.start();
        } catch (IOException | RuntimeException failure) {
            if (openedMuxer != null) openedMuxer.release();
            encoder.release();
            throw failure;
        }
        muxer = openedMuxer;
    }

    void write(Image frame, int frameNumber) throws IOException {
        int index = input();
        Image output = encoder.getInputImage(index);
        if (output == null) throw new IOException("Encoder H.264 senza input YUV flessibile");
        Image.Plane[] source = frame.getPlanes(), destination = output.getPlanes();
        for (int i = 0; i < 3; i++) {
            int width = i == 0 ? StarLapse.WIDTH : StarLapse.WIDTH / 2;
            int height = i == 0 ? StarLapse.HEIGHT : StarLapse.HEIGHT / 2;
            copyPlane(source[i], destination[i], width, height);
        }
        encoder.queueInputBuffer(index, 0, StarLapse.WIDTH * StarLapse.HEIGHT * 3 / 2, timestamp(frameNumber), 0);
        drain(0);
    }

    private void copyPlane(Image.Plane from, Image.Plane to, int width, int height) {
        ByteBuffer source = from.getBuffer(), destination = to.getBuffer();
        int fromBase = source.position(), toBase = destination.position();
        for (int y = 0; y < height; y++) {
            int fromOffset = fromBase + y * from.getRowStride();
            int toOffset = toBase + y * to.getRowStride();
            if (from.getPixelStride() == 1 && to.getPixelStride() == 1) {
                source.position(fromOffset);
                source.get(row, 0, width);
                destination.position(toOffset);
                destination.put(row, 0, width);
            } else {
                for (int x = 0; x < width; x++) {
                    destination.put(toOffset + x * to.getPixelStride(),
                        source.get(fromOffset + x * from.getPixelStride()));
                }
            }
        }
    }

    void finish(int frames) throws IOException {
        encoder.queueInputBuffer(input(), 0, 0, timestamp(frames), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        for (int i = 0; i < 100 && !ended; i++) drain(100_000);
        if (!ended || track == -1) throw new IOException("Esportazione MP4 incompleta");
        info.set(0, 0, timestamp(frames), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        muxer.writeSampleData(track, ByteBuffer.allocateDirect(0), info);
    }

    private int input() throws IOException {
        for (int i = 0; i < 40; i++) {
            drain(0);
            int index = encoder.dequeueInputBuffer(100_000);
            if (index >= 0) return index;
        }
        throw new IOException("Buffer di codifica non disponibile");
    }

    private void drain(long waitUs) {
        while (!ended) {
            int index = encoder.dequeueOutputBuffer(info, waitUs);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
            } else if (index >= 0) {
                ByteBuffer encoded = encoder.getOutputBuffer(index);
                if (encoded != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    muxer.writeSampleData(track, encoded, info);
                }
                ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(index, false);
            }
            waitUs = 0;
        }
    }

    private static long timestamp(int frames) { return frames * 1_000_000L / StarLapse.FPS; }

    @Override public void close() {
        try {
            if (track >= 0) muxer.stop();
        } finally {
            try { muxer.release(); }
            finally { encoder.release(); }
        }
    }
}
