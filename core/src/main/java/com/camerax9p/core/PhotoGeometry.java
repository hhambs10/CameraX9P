package com.camerax9p.core;

/** Image geometry only; does not imply camera availability or perform image resampling. */
public final class PhotoGeometry {
    private PhotoGeometry() {}

    public static final class Crop {
        public final int left, top, width, height;
        private Crop(int left, int top, int width, int height) {
            this.left = left; this.top = top; this.width = width; this.height = height;
        }
        public double megapixels() { return (long) width * height / 1_000_000.0; }
    }

    /** Largest centered crop at exactly 65:24, after applying image orientation. */
    public static Crop xpan(int width, int height) {
        return centeredCrop(width, height, 65, 24);
    }

    /** Largest centered crop at the given ratio, expressed in lowest terms. */
    public static Crop centeredCrop(int width, int height, int ratioWidth, int ratioHeight) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid source dimensions");
        if (ratioWidth <= 0 || ratioHeight <= 0) throw new IllegalArgumentException("Invalid aspect ratio");
        int scale = Math.min(width / ratioWidth, height / ratioHeight);
        if (scale < 1) throw new IllegalArgumentException("Image too small for the aspect ratio");
        int cropWidth = scale * ratioWidth, cropHeight = scale * ratioHeight;
        return new Crop((width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight);
    }

    public record Polaroid(int width, int height, int left, int top) {}

    /** Adds equal top and side margins and a bottom margin four times as deep. */
    public static Polaroid polaroid(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid source dimensions");
        int margin = Math.max(1, Math.round(Math.min(width, height) * .05f));
        int outerWidth = Math.toIntExact(width + 2L * margin);
        int outerHeight = Math.toIntExact(height + 5L * margin);
        return new Polaroid(outerWidth, outerHeight, margin, margin);
    }

    /** Output size at or below requested MP, preserving aspect approximately; never upscales. */
    public static int[] downsampleSize(int width, int height, int megapixels) {
        if (width <= 0 || height <= 0 || !(megapixels == 12 || megapixels == 25 || megapixels == 50 || megapixels == 200)) {
            throw new IllegalArgumentException("Invalid photo dimensions or preset");
        }
        long sourcePixels = (long) width * height;
        long targetPixels = megapixels * 1_000_000L;
        if (sourcePixels < targetPixels) throw new IllegalArgumentException("Preset needs a larger source; upscaling is disabled");
        double scale = Math.sqrt(targetPixels / (double) sourcePixels);
        return new int[] { Math.max(1, (int) Math.floor(width * scale)), Math.max(1, (int) Math.floor(height * scale)) };
    }
}
