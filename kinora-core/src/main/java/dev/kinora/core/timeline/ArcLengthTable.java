package dev.kinora.core.timeline;

import java.util.List;

/**
 * Distance along a path track, for constant-speed travel: maps a fraction of the total length to
 * the point that far along, whatever the spacing of the keys.
 *
 * <p>Each segment is sampled at a fixed number of points; between samples the curve is treated as
 * straight and the parameter is interpolated linearly. With 128 samples per segment the speed
 * error on typical camera paths is far below one percent.
 */
public final class ArcLengthTable {
    private final List<Keyframe> keys;
    private final int samples;
    /** Cumulative length at each sample, segment-major: (segments * samples + 1) entries. */
    private final double[] cumulative;
    private final double total;

    private ArcLengthTable(List<Keyframe> keys, int samples, double[] cumulative) {
        this.keys = List.copyOf(keys);
        this.samples = samples;
        this.cumulative = cumulative;
        this.total = cumulative.length == 0 ? 0 : cumulative[cumulative.length - 1];
    }

    public static ArcLengthTable build(List<Keyframe> keys, int samplesPerSegment) {
        int segments = Math.max(0, keys.size() - 1);
        double[] cumulative = new double[segments * samplesPerSegment + 1];
        double length = 0;
        double[] previous = keys.isEmpty() ? null : keys.getFirst().value;
        int index = 1;
        for (int seg = 0; seg < segments; seg++) {
            for (int j = 1; j <= samplesPerSegment; j++) {
                double[] p = TrackMath.pathPoint(keys, seg, j / (double) samplesPerSegment);
                length += distance(previous, p);
                cumulative[index++] = length;
                previous = p;
            }
        }
        return new ArcLengthTable(keys, samplesPerSegment, cumulative);
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double totalLength() {
        return total;
    }

    /** Point at {@code fraction} (0 to 1) of the total length. */
    public double[] pointAtFraction(double fraction) {
        if (keys.size() < 2 || total <= 0) {
            return keys.isEmpty() ? null : keys.getFirst().value.clone();
        }
        double target = Math.max(0, Math.min(1, fraction)) * total;
        int lo = 0;
        int hi = cumulative.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int sample = Math.max(1, lo);
        double before = cumulative[sample - 1];
        double after = cumulative[sample];
        double within = after > before ? (target - before) / (after - before) : 0;
        int segment = (sample - 1) / samples;
        double u = ((sample - 1) % samples + within) / samples;
        return TrackMath.pathPoint(keys, Math.min(segment, keys.size() - 2), u);
    }
}
