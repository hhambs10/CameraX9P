package com.camerax9p.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.media3.common.Effect;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.RgbFilter;
import androidx.media3.effect.SingleColorLut;
import com.camerax9p.core.CubeLut;
import java.util.ArrayList;
import java.util.List;

record LutGrade(CubeLut lut, float intensity, boolean monochrome) {
    void apply(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int[] row = new int[width];
        float[] rgb = new float[3];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int color = row[x];
                float red = Color.red(color) / 255f, green = Color.green(color) / 255f, blue = Color.blue(color) / 255f;
                if (lut == null) {
                    rgb[0] = red; rgb[1] = green; rgb[2] = blue;
                } else {
                    lut.apply(red, green, blue, intensity, rgb);
                }
                if (monochrome) {
                    float luma = .2126f * clamp(rgb[0]) + .7152f * clamp(rgb[1]) + .0722f * clamp(rgb[2]);
                    rgb[0] = luma; rgb[1] = luma; rgb[2] = luma;
                }
                row[x] = Color.argb(Color.alpha(color), channel(rgb[0]), channel(rgb[1]), channel(rgb[2]));
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
    }

    @androidx.annotation.OptIn(markerClass = UnstableApi.class)
    List<Effect> videoEffects() {
        List<Effect> effects = new ArrayList<>(2);
        if (lut != null) effects.add(videoLut());
        if (monochrome) effects.add(RgbFilter.createGrayscaleFilter());
        return effects;
    }

    String label(Context context) {
        if (!monochrome) return "LUT";
        return context.getString(lut == null ? R.string.monochrome_short : R.string.lut_monochrome);
    }

    @androidx.annotation.OptIn(markerClass = UnstableApi.class)
    private SingleColorLut videoLut() {
        // Media3 packs the cube into a texture of height N². Stay within 4096 pixels.
        int size = lut.isThreeDimensional() ? Math.min(64, lut.size()) : 64;
        float scale = 1f / (size - 1);
        int[][][] cube = new int[size][size][size];
        float[] rgb = new float[3];
        for (int r = 0; r < size; r++) {
            for (int g = 0; g < size; g++) {
                for (int b = 0; b < size; b++) {
                    lut.apply(r * scale, g * scale, b * scale, intensity, rgb);
                    cube[r][g][b] = Color.rgb(channel(rgb[0]), channel(rgb[1]), channel(rgb[2]));
                }
            }
        }
        return SingleColorLut.createFromCube(cube);
    }

    private static int channel(float value) {
        return Math.round(255 * clamp(value));
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
