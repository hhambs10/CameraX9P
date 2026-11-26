package com.camerax9p.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashSet;
import java.util.Set;

/** Immutable CPU reference for standalone 1D/3D .cube LUTs. No color-space conversion. */
public final class CubeLut {
    private static final int MAX_LINE_LENGTH = 8192;
    private static final int MAX_CHARACTERS = 32 * 1024 * 1024;
    private final int size;
    private final boolean threeDimensional;
    private final float[] values;
    private final float[] min;
    private final float[] max;

    private CubeLut(int size, boolean threeDimensional, float[] values, float[] min, float[] max) {
        this.size = size;
        this.threeDimensional = threeDimensional;
        this.values = values;
        this.min = min;
        this.max = max;
    }

    /** Caller owns the reader. Rejects combined shapers rather than silently misreading them. */
    public static CubeLut read(Reader source) throws IOException {
        BoundedLines reader = new BoundedLines(source);
        float[] min = {0, 0, 0}, max = {1, 1, 1}, values = null;
        Set<String> headers = new HashSet<>();
        int size = 0, count = 0, lineNumber = 0;
        boolean threeD = false, dataStarted = false, rangeSpecified = false, domainSpecified = false;
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (lineNumber == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (lineNumber > 300000) throw invalid(lineNumber, "File too large");
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // TITLE may itself contain #; it does not affect numerical evaluation.
            if (line.startsWith("TITLE ") || line.startsWith("TITLE\t")) {
                if (dataStarted || !headers.add("TITLE")) throw invalid(lineNumber, "Duplicate or late TITLE");
                continue;
            }
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment).trim();
            String[] fields = line.split("\\s+");
            String key = fields[0];
            try {
                if (Character.isLetter(key.charAt(0))) {
                    if (dataStarted || !headers.add(key)) throw invalid(lineNumber, "Duplicate or late header");
                    switch (key) {
                        case "LUT_1D_SIZE":
                        case "LUT_3D_SIZE":
                            if (size != 0) throw invalid(lineNumber, "Combined 1D+3D LUTs are not supported yet");
                            if (fields.length != 2) throw invalid(lineNumber, "Invalid LUT size");
                            threeD = key.equals("LUT_3D_SIZE");
                            size = Integer.parseInt(fields[1]);
                            if (size < 2 || size > (threeD ? 65 : 65536)) throw invalid(lineNumber, "Unsupported LUT size");
                            values = new float[3 * (threeD ? size * size * size : size)];
                            break;
                        case "DOMAIN_MIN":
                        case "DOMAIN_MAX":
                            if (rangeSpecified || fields.length != 4) throw invalid(lineNumber, "Invalid or conflicting domain");
                            domainSpecified = true;
                            for (int c = 0; c < 3; c++) (key.equals("DOMAIN_MIN") ? min : max)[c] = finite(fields[c + 1]);
                            break;
                        case "LUT_1D_INPUT_RANGE":
                        case "LUT_3D_INPUT_RANGE":
                            if (domainSpecified || rangeSpecified || fields.length != 3) throw invalid(lineNumber, "Conflicting input range");
                            rangeSpecified = true;
                            for (int c = 0; c < 3; c++) { min[c] = finite(fields[1]); max[c] = finite(fields[2]); }
                            break;
                        default: throw invalid(lineNumber, "Unsupported header: " + key);
                    }
                } else {
                    dataStarted = true;
                    if (values == null || fields.length != 3 || count + 3 > values.length) throw invalid(lineNumber, "Unexpected data row");
                    for (String field : fields) values[count++] = finite(field);
                }
            } catch (NumberFormatException e) {
                throw invalid(lineNumber, "Invalid or non-finite number");
            }
        }
        if (values == null || count != values.length) throw invalid(lineNumber, "Missing LUT samples");
        if (headers.contains(threeD ? "LUT_1D_INPUT_RANGE" : "LUT_3D_INPUT_RANGE")) throw invalid(lineNumber, "Input range dimension mismatch");
        for (int c = 0; c < 3; c++) {
            if (!(max[c] > min[c]) || !Float.isFinite(max[c] - min[c])) throw invalid(lineNumber, "Invalid domain extent");
        }
        return new CubeLut(size, threeD, values, min, max);
    }

    private static final class BoundedLines {
        private final BufferedReader reader;
        private int characters;
        private int lineNumber;
        private boolean skipLf;

        BoundedLines(Reader source) { reader = new BufferedReader(source); }

        String readLine() throws IOException {
            lineNumber++;
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                if (++characters > MAX_CHARACTERS) throw invalid(lineNumber, "File too large");
                if (skipLf) {
                    skipLf = false;
                    if (ch == '\n') continue;
                }
                if (ch == '\n' || ch == '\r') {
                    skipLf = ch == '\r';
                    return line.toString();
                }
                if (line.length() == MAX_LINE_LENGTH) throw invalid(lineNumber, "Line too long");
                line.append((char) ch);
            }
            return line.length() == 0 ? null : line.toString();
        }
    }

    public int size() { return size; }
    public boolean isThreeDimensional() { return threeDimensional; }

    /** RGB in the LUT's declared input encoding; intensity in [0,1]. Output is not clipped. */
    public float[] apply(float red, float green, float blue, float intensity) {
        float[] output = new float[3];
        apply(red, green, blue, intensity, output);
        return output;
    }

    /** Evaluates into a caller-provided RGB array so full-resolution exports can reuse it. */
    public void apply(float red, float green, float blue, float intensity, float[] output) {
        if (!Float.isFinite(intensity) || intensity < 0 || intensity > 1) throw new IllegalArgumentException("Invalid intensity");
        if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue)) {
            throw new IllegalArgumentException("Invalid RGB");
        }
        output[0] = red;
        output[1] = green;
        output[2] = blue;
        if (intensity == 0) return;
        float x = position(red, 0), y = position(green, 1), z = position(blue, 2);
        int r = (int) x, g = (int) y, b = (int) z;
        int nextR = Math.min(size - 1, r + 1), nextG = Math.min(size - 1, g + 1), nextB = Math.min(size - 1, b + 1);
        for (int c = 0; c < 3; c++) {
            double mapped = threeDimensional
                ? mix(plane(r, nextR, g, nextG, b, c, x - r, y - g),
                    plane(r, nextR, g, nextG, nextB, c, x - r, y - g), z - b)
                : switch (c) {
                    case 0 -> mix(values[3 * r], values[3 * nextR], x - r);
                    case 1 -> mix(values[3 * g + 1], values[3 * nextG + 1], y - g);
                    default -> mix(values[3 * b + 2], values[3 * nextB + 2], z - b);
                };
            output[c] = (float) mix(output[c], mapped, intensity);
        }
    }

    private float position(float input, int channel) {
        return Math.max(0, Math.min(1, (input - min[channel]) / (max[channel] - min[channel]))) * (size - 1);
    }

    private double plane(int r, int nextR, int g, int nextG, int b, int channel, float x, float y) {
        // .cube order: red changes fastest, then green, then blue.
        int lower = 3 * (size * (size * b + g)) + channel;
        int upper = 3 * (size * (size * b + nextG)) + channel;
        return mix(mix(values[lower + 3 * r], values[lower + 3 * nextR], x),
            mix(values[upper + 3 * r], values[upper + 3 * nextR], x), y);
    }

    private static double mix(double from, double to, float amount) {
        return (1.0 - amount) * from + amount * to;
    }

    private static float finite(String value) {
        float parsed = Float.parseFloat(value);
        if (!Float.isFinite(parsed)) throw new NumberFormatException();
        return parsed;
    }

    private static IOException invalid(int line, String message) {
        return new IOException(".cube line " + line + ": " + message);
    }
}
