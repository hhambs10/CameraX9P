package com.camerax9p.core;

import java.io.IOException;
import java.io.StringReader;

public final class CoreTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        StringBuilder identity = new StringBuilder("\uFEFF# identity\nTITLE \"test # title\"\nLUT_3D_SIZE 2\n");
        StringBuilder swap = new StringBuilder("LUT_3D_SIZE 2\n");
        for (int b = 0; b < 2; b++) for (int g = 0; g < 2; g++) for (int r = 0; r < 2; r++) {
            identity.append(r + " " + g + " " + b + "\n");
            swap.append(b + " " + r + " " + g + "\n");
        }
        CubeLut lut = read(identity.toString());
        rgb(lut.apply(.2f, .45f, .8f, 1), .2f, .45f, .8f);
        rgb(lut.apply(-1, 2, .5f, 1), 0, 1, .5f);
        rgb(lut.apply(-1, 2, .5f, 0), -1, 2, .5f);
        rgb(read(swap.toString()).apply(.2f, .45f, .8f, 1), .8f, .2f, .45f);
        rgb(read(swap.toString()).apply(.2f, .45f, .8f, .5f), .5f, .325f, .625f);
        rgb(read("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n").apply(.3f, .6f, .9f, 1), .3f, .6f, .9f);
        rgb(read("DOMAIN_MIN -1 -1 -1\nDOMAIN_MAX 1 1 1\nLUT_1D_SIZE 2\n0 0 0\n1 1 1\n").apply(0, -1, 1, 1), .5f, 0, 1);
        rgb(read("LUT_1D_SIZE 2\nLUT_1D_INPUT_RANGE -1 1\n0 0 0\n1 1 1\n").apply(0, -1, 1, 1), .5f, 0, 1);
        invalid("LUT_3D_SIZE 2\n0 0 0\n");
        invalid("LUT_1D_SIZE 2\nLUT_3D_SIZE 2\n");
        invalid("LUT_1D_SIZE 2\n0 0 0\n1 NaN 1\n");
        invalid("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n1 1 1\n");
        invalid("DOMAIN_MIN 1 0 0\nLUT_1D_SIZE 2\n0 0 0\n1 1 1\n");
        invalid("LUT_3D_SIZE 999999999\n");
        invalid("LUT_1D_SIZE 2\nLUT_3D_INPUT_RANGE 0 1\n0 0 0\n1 1 1\n");
        invalid("LUT_1D_SIZE 2\n0 0 0\nDOMAIN_MAX 1 1 1\n1 1 1\n");
        rgb(read("LUT_1D_SIZE 2\r\n0 0 0\r1 1 1").apply(.3f, .6f, .9f, 1), .3f, .6f, .9f);
        check(read("#" + "x".repeat(8191) + "\nLUT_1D_SIZE 2\n0 0 0\n1 1 1").size() == 2,
            "a line of exactly 8192 characters is accepted");
        try {
            CubeLut.read(new java.io.Reader() {
                private int supplied;
                @Override public int read(char[] buffer, int offset, int length) {
                    supplied += length;
                    if (supplied > 16384) throw new AssertionError("Parser read past the oversized line limit");
                    java.util.Arrays.fill(buffer, offset, offset + length, 'x');
                    return length;
                }
                @Override public void close() {}
            });
            throw new AssertionError("Oversized line accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("Line too long"), "oversized line rejected during read");
        }
        PhotoGeometry.Crop crop = PhotoGeometry.xpan(8000, 6000);
        check(crop.width * 24 == crop.height * 65, "65:24 ratio");
        check(crop.left >= 0 && crop.top >= 0 && crop.left + crop.width <= 8000 && crop.top + crop.height <= 6000, "crop bounds");
        check(crop.megapixels() < 24, "crop reduces megapixels");
        PhotoGeometry.Crop wide = PhotoGeometry.centeredCrop(4096, 3072, 16, 9);
        check(wide.width == 4096 && wide.height == 2304 && wide.left == 0 && wide.top == 384,
            "16:9 output matches the centered 12.6 MP crop");
        PhotoGeometry.Polaroid portrait = PhotoGeometry.polaroid(3072, 4096);
        check(portrait.width() == 3380 && portrait.height() == 4866 && portrait.top() == 154
            && portrait.height() - portrait.top() - 4096 == 616, "portrait mount leaves a deeper caption space");
        PhotoGeometry.Polaroid landscape = PhotoGeometry.polaroid(4096, 3072);
        check(landscape.width() == 4404 && landscape.height() == 3842 && landscape.left() == 154
            && landscape.height() - landscape.top() - 3072 == 616, "landscape mount keeps the caption at the bottom");
        int[] size = PhotoGeometry.downsampleSize(8192, 6144, 25);
        check((long) size[0] * size[1] <= 25000000, "25 MP maximum");
        check((long) size[0] * size[1] > 24980000, "25 MP approximation");
        try { PhotoGeometry.downsampleSize(4000, 3000, 200); throw new AssertionError("Upscaling allowed"); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("PASS: " + checks + " checks (LUT parsing, interpolation, invalid inputs, crop and downsample geometry).");
    }
    private static CubeLut read(String text) throws IOException { return CubeLut.read(new StringReader(text)); }
    private static void invalid(String text) throws Exception {
        try { read(text); throw new AssertionError("Invalid LUT accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static void rgb(float[] actual, float r, float g, float b) {
        float[] expected = {r, g, b};
        for (int c = 0; c < 3; c++) check(Math.abs(actual[c] - expected[c]) < .00001, "RGB channel " + c);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
