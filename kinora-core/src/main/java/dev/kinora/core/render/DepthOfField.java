package dev.kinora.core.render;

import java.util.stream.IntStream;

/**
 * Lens blur from depth, the way a real camera lens does it: a thin lens with the focal length that
 * gives the shot's field of view on a full-frame (36 x 24 mm) sensor, stopped down to an f-number.
 * Points at the focus distance are sharp; the blur circle grows with distance from it.
 *
 * <p>The blur is built from a few pre-blurred copies of the frame; each pixel picks between them by
 * its blur size. Fast, and close to a gather blur except at the edges of out-of-focus foreground.
 *
 * @param focus    focus distance in blocks (1 block = 1 m)
 * @param fNumber  aperture, e.g. 1.4 (very shallow) .. 16 (deep)
 * @param fov      vertical field of view in degrees
 * @param m22      projection matrix element (2,2), to turn depth into distance
 * @param m32      projection matrix element (3,2)
 */
public record DepthOfField(double focus, double fNumber, double fov, double m22, double m32) {
    private static final double SENSOR_HEIGHT_MM = 24;
    private static final int LEVELS = 6;

    /** Distance in blocks along the view axis for a stored (reversed-Z, 0..1) depth. */
    public double distance(float depth) {
        double d = depth + m22;
        return Math.abs(d) < 1e-9 ? 1e9 : Math.max(0.05, m32 / d);
    }

    /** Blur circle diameter in pixels for a distance, at the given image height. */
    public double blurPixels(double distance, int height) {
        double f = SENSOR_HEIGHT_MM / 2 / Math.tan(Math.toRadians(fov) / 2);
        double s = Math.max(focus * 1000, f * 1.01);
        double d = distance * 1000;
        double cocMm = f * f / (fNumber * (s - f)) * Math.abs(d - s) / d;
        return cocMm / SENSOR_HEIGHT_MM * height;
    }

    /**
     * Blurs {@code rgba} in place.
     *
     * @param depth stored depth per pixel, top row first, same size as the image
     */
    public void apply(byte[] rgba, float[] depth, int width, int height) {
        if (Double.isNaN(focus)) {
            // Autofocus: whatever is in the middle of the frame.
            double centre = distance(depth[(height / 2) * width + width / 2]);
            new DepthOfField(centre, fNumber, fov, m22, m32).apply(rgba, depth, width, height);
            return;
        }
        int maxRadius = Math.max(1, height / 30);
        byte[][] levels = new byte[LEVELS + 1][];
        levels[0] = rgba.clone();
        for (int k = 1; k <= LEVELS; k++) {
            int radius = Math.min(maxRadius, 1 << (k - 1));
            levels[k] = levels[0].clone();
            // Two box passes per axis approximate a soft disc.
            blur(levels[k], width, height, radius);
            blur(levels[k], width, height, Math.max(1, radius / 2));
        }
        IntStream.range(0, height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                double radius = Math.min(maxRadius, blurPixels(distance(depth[i]), height) / 2);
                if (radius < 0.5) {
                    continue;
                }
                // Level k holds roughly radius 2^(k-1).
                double level = Math.min(LEVELS, 1 + Math.log(radius) / Math.log(2));
                int lo = (int) Math.floor(level);
                int hi = Math.min(LEVELS, lo + 1);
                double t = level - lo;
                for (int c = 0; c < 3; c++) {
                    double a = levels[Math.max(0, lo)][i * 4 + c] & 0xFF;
                    double b = levels[hi][i * 4 + c] & 0xFF;
                    rgba[i * 4 + c] = (byte) Math.round(a + (b - a) * t);
                }
            }
        });
    }

    /** Separable box blur on RGB, in place. */
    private static void blur(byte[] img, int w, int h, int r) {
        byte[] tmp = new byte[img.length];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int c = 0; c < 3; c++) {
                int sum = 0;
                for (int x = -r; x <= r; x++) {
                    sum += img[(y * w + clamp(x, w)) * 4 + c] & 0xFF;
                }
                for (int x = 0; x < w; x++) {
                    tmp[(y * w + x) * 4 + c] = (byte) (sum / (2 * r + 1));
                    sum += (img[(y * w + clamp(x + r + 1, w)) * 4 + c] & 0xFF) - (img[(y * w + clamp(x - r, w)) * 4 + c] & 0xFF);
                }
            }
        });
        IntStream.range(0, w).parallel().forEach(x -> {
            for (int c = 0; c < 3; c++) {
                int sum = 0;
                for (int y = -r; y <= r; y++) {
                    sum += tmp[(clamp(y, h) * w + x) * 4 + c] & 0xFF;
                }
                for (int y = 0; y < h; y++) {
                    img[(y * w + x) * 4 + c] = (byte) (sum / (2 * r + 1));
                    sum += (tmp[(clamp(y + r + 1, h) * w + x) * 4 + c] & 0xFF) - (tmp[(clamp(y - r, h) * w + x) * 4 + c] & 0xFF);
                }
            }
        });
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : v >= max ? max - 1 : v;
    }
}
