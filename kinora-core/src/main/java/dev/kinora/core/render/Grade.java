package dev.kinora.core.render;

import dev.kinora.core.project.Tracks;

import java.util.Map;

/**
 * The look of a frame: colour grading and lens effects from a shot's grade and fx tracks, applied
 * to the rendered image before it is mixed into the output frame.
 *
 * @param exposure    stops; 0 leaves the image alone, +1 doubles the light
 * @param contrast    1 is neutral, above 1 is punchier
 * @param saturation  1 is neutral, 0 is black and white
 * @param temperature -1 (cool, blue) .. 1 (warm, orange)
 * @param tint        -1 (green) .. 1 (magenta)
 * @param vignette    0 .. 1, how dark the corners get
 * @param grain       0 .. 1, film grain strength
 * @param chromatic   colour fringing at the edges, in thousandths of the frame width
 * @param bloom       0 .. 1, glow around bright areas
 * @param letterbox   aspect ratio of the picture inside black bars (2.39 for scope); 0 for none
 */
public record Grade(double exposure, double contrast, double saturation, double temperature, double tint, double vignette, double grain,
                    double chromatic, double bloom, double letterbox) {
    public static final Grade NONE = new Grade(0, 1, 1, 0, 0, 0, 0, 0, 0, 0);

    /** The grade a shot's evaluated values describe. */
    public static Grade of(Map<String, Double> values) {
        return new Grade(v(values, Tracks.EXPOSURE), v(values, Tracks.CONTRAST), v(values, Tracks.SATURATION), v(values, Tracks.TEMPERATURE),
                v(values, Tracks.TINT), v(values, Tracks.VIGNETTE), v(values, Tracks.GRAIN), v(values, Tracks.CHROMATIC), v(values, Tracks.BLOOM),
                v(values, Tracks.LETTERBOX));
    }

    private static double v(Map<String, Double> values, String id) {
        Double d = values.get(id);
        return d != null ? d : Tracks.defaultValue(id);
    }

    public boolean isNone() {
        return equals(NONE);
    }

    /** sRGB byte to linear light. */
    private static final float[] TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    /** Linear light (0..1 in 16384 steps) to sRGB byte: a table, as pow() per pixel is slow. */
    private static final byte[] TO_SRGB = new byte[16385];

    static {
        for (int i = 0; i < TO_SRGB.length; i++) {
            double c = i / 16384.0;
            double s = c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
            TO_SRGB[i] = (byte) Math.round(s * 255);
        }
    }

    private static byte toSrgbByte(float linear) {
        float c = linear <= 0 ? 0 : linear >= 1 ? 1 : linear;
        return TO_SRGB[(int) (c * 16384 + 0.5f)];
    }

    /**
     * Applies the grade in place.
     *
     * @param rgba  RGBA bytes, top row first
     * @param seed  varies the grain per frame, so it moves like film grain but repeats exactly
     */
    public void apply(byte[] rgba, int width, int height, long seed) {
        if (isNone()) {
            return;
        }
        int n = width * height;
        float[] r = new float[n];
        float[] g = new float[n];
        float[] b = new float[n];
        // Chromatic aberration samples red and blue from slightly different places: read them shifted.
        double shift = chromatic / 1000.0 * width;
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        double maxRadius = Math.sqrt(cx * cx + cy * cy);
        for (int y = 0, i = 0; y < height; y++) {
            for (int x = 0; x < width; x++, i++) {
                g[i] = TO_LINEAR[rgba[i * 4 + 1] & 0xFF];
                if (shift != 0) {
                    double dx = x - cx;
                    double dy = y - cy;
                    double k = shift / maxRadius;
                    r[i] = TO_LINEAR[rgba[index(x + dx * k, y + dy * k, width, height) * 4] & 0xFF];
                    b[i] = TO_LINEAR[rgba[index(x - dx * k, y - dy * k, width, height) * 4 + 2] & 0xFF];
                } else {
                    r[i] = TO_LINEAR[rgba[i * 4] & 0xFF];
                    b[i] = TO_LINEAR[rgba[i * 4 + 2] & 0xFF];
                }
            }
        }
        if (bloom > 0) {
            bloom(r, g, b, width, height);
        }
        float gain = (float) Math.pow(2, exposure);
        // White balance: warm adds red and takes blue; tint trades green for magenta.
        float wr = (float) (gain * (1 + 0.25 * temperature) * (1 + 0.1 * tint));
        float wg = (float) (gain * (1 - 0.2 * tint));
        float wb = (float) (gain * (1 - 0.25 * temperature) * (1 + 0.1 * tint));
        float sat = (float) saturation;
        float con = (float) contrast;
        int barHeight = letterbox > 0 ? (int) Math.max(0, Math.round((height - width / letterbox) / 2)) : 0;
        // Rows are independent: spread them over the cores.
        java.util.stream.IntStream.range(0, height).parallel().forEach(y -> {
            boolean bar = y < barHeight || y >= height - barHeight;
            for (int x = 0, i = y * width; x < width; x++, i++) {
                if (bar) {
                    rgba[i * 4] = 0;
                    rgba[i * 4 + 1] = 0;
                    rgba[i * 4 + 2] = 0;
                    continue;
                }
                float lr = r[i] * wr;
                float lg = g[i] * wg;
                float lb = b[i] * wb;
                float luma = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb;
                lr = luma + (lr - luma) * sat;
                lg = luma + (lg - luma) * sat;
                lb = luma + (lb - luma) * sat;
                if (con != 1) {
                    // Contrast around middle grey, in a log-like space so shadows do not crush.
                    lr = contrast(lr, con);
                    lg = contrast(lg, con);
                    lb = contrast(lb, con);
                }
                if (vignette > 0) {
                    double dx = (x - cx) / cx;
                    double dy = (y - cy) / cy;
                    double d = Math.sqrt(dx * dx + dy * dy) / Math.sqrt(2);
                    float v = (float) (1 - vignette * smoothstep(0.35, 1.0, d));
                    lr *= v;
                    lg *= v;
                    lb *= v;
                }
                if (grain > 0) {
                    float noise = (float) ((hash(x, y, seed) - 0.5) * grain * 0.12);
                    lr = Math.max(0, lr + noise * (0.3f + lr));
                    lg = Math.max(0, lg + noise * (0.3f + lg));
                    lb = Math.max(0, lb + noise * (0.3f + lb));
                }
                rgba[i * 4] = toSrgbByte(lr);
                rgba[i * 4 + 1] = toSrgbByte(lg);
                rgba[i * 4 + 2] = toSrgbByte(lb);
            }
        });
    }

    private static float contrast(float linear, float amount) {
        double grey = 0.18;
        double v = Math.max(linear, 1e-6);
        return (float) (grey * Math.pow(v / grey, amount));
    }

    private static int index(double x, double y, int width, int height) {
        int ix = (int) Math.max(0, Math.min(width - 1, Math.round(x)));
        int iy = (int) Math.max(0, Math.min(height - 1, Math.round(y)));
        return iy * width + ix;
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /** A repeatable pseudo-random value in 0..1 for a pixel and frame. */
    static double hash(int x, int y, long seed) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Adds a soft glow around light brighter than 0.8: threshold at quarter size, blurred, added back. */
    private void bloom(float[] r, float[] g, float[] b, int width, int height) {
        int w = Math.max(1, width / 4);
        int h = Math.max(1, height / 4);
        float[][] small = {new float[w * h], new float[w * h], new float[w * h]};
        float[][] full = {r, g, b};
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    float sum = 0;
                    for (int dy = 0; dy < 4; dy++) {
                        int row = Math.min(height - 1, y * 4 + dy) * width;
                        for (int dx = 0; dx < 4; dx++) {
                            sum += full[c][row + Math.min(width - 1, x * 4 + dx)];
                        }
                    }
                    small[c][y * w + x] = Math.max(0, sum / 16 - 0.8f);
                }
            }
        }
        int radius = Math.max(1, w / 64);
        for (int c = 0; c < 3; c++) {
            // Three box blurs approximate a Gaussian.
            for (int pass = 0; pass < 3; pass++) {
                boxBlur(small[c], w, h, radius);
            }
        }
        float strength = (float) (bloom * 2);
        for (int y = 0; y < height; y++) {
            int sy = Math.min(h - 1, y / 4);
            for (int x = 0; x < width; x++) {
                int s = sy * w + Math.min(w - 1, x / 4);
                int i = y * width + x;
                r[i] += small[0][s] * strength;
                g[i] += small[1][s] * strength;
                b[i] += small[2][s] * strength;
            }
        }
    }

    private static void boxBlur(float[] a, int w, int h, int radius) {
        float[] tmp = new float[a.length];
        float norm = 1f / (2 * radius + 1);
        for (int y = 0; y < h; y++) {
            float sum = 0;
            for (int x = -radius; x <= radius; x++) {
                sum += a[y * w + Math.max(0, Math.min(w - 1, x))];
            }
            for (int x = 0; x < w; x++) {
                tmp[y * w + x] = sum * norm;
                sum += a[y * w + Math.min(w - 1, x + radius + 1)] - a[y * w + Math.max(0, x - radius)];
            }
        }
        for (int x = 0; x < w; x++) {
            float sum = 0;
            for (int y = -radius; y <= radius; y++) {
                sum += tmp[Math.max(0, Math.min(h - 1, y)) * w + x];
            }
            for (int y = 0; y < h; y++) {
                a[y * w + x] = sum * norm;
                sum += tmp[Math.min(h - 1, y + radius + 1) * w + x] - tmp[Math.max(0, y - radius) * w + x];
            }
        }
    }
}
