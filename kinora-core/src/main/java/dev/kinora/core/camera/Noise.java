package dev.kinora.core.camera;

/**
 * Deterministic smooth noise for camera shake: 1D gradient noise with a few octaves. The same
 * seed, channel and time always give the same value, so shake renders identically every time.
 */
public final class Noise {
    private Noise() {}

    /** Gradient noise in roughly [-1, 1], period-free, smooth (C2) in {@code x}. */
    public static double gradient(long seed, int channel, double x) {
        long cell = (long) Math.floor(x);
        double f = x - cell;
        double g0 = slope(seed, channel, cell);
        double g1 = slope(seed, channel, cell + 1);
        double v0 = g0 * f;
        double v1 = g1 * (f - 1);
        double fade = f * f * f * (f * (f * 6 - 15) + 10);
        // Scale so the typical range is about [-1, 1].
        return 2.0 * (v0 + (v1 - v0) * fade);
    }

    /** Fractal sum of three octaves, normalised to roughly [-1, 1]. */
    public static double fractal(long seed, int channel, double x) {
        double sum = 0;
        double amplitude = 1;
        double frequency = 1;
        double norm = 0;
        for (int octave = 0; octave < 3; octave++) {
            sum += amplitude * gradient(seed + octave * 0x9E3779B97F4A7C15L, channel, x * frequency);
            norm += amplitude;
            amplitude *= 0.5;
            frequency *= 2.03;
        }
        return sum / norm;
    }

    private static double slope(long seed, int channel, long cell) {
        long h = seed ^ (channel * 0xD6E8FEB86659FD93L) ^ (cell * 0x9E3779B97F4A7C15L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        // Uniform in [-1, 1].
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1;
    }
}
