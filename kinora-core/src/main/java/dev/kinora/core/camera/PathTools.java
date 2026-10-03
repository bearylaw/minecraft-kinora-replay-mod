package dev.kinora.core.camera;

import java.util.ArrayList;
import java.util.List;

/**
 * Turning sampled camera motion (a free flight captured live, a recorded first-person view) into
 * something editable: smoothing it (stabilisation) and reducing it to few keyframes.
 */
public final class PathTools {
    private PathTools() {}

    /** One sample of camera motion. */
    public record Sample(double time, double x, double y, double z, double yaw, double pitch, double roll, double fov) {
        static Sample of(double time, CameraState s) {
            return new Sample(time, s.x(), s.y(), s.z(), s.yaw(), s.pitch(), s.roll(), s.fov());
        }

        public CameraState state() {
            return new CameraState(x, y, z, yaw, pitch, roll, fov);
        }
    }

    /**
     * Gaussian low-pass filter over time: {@code strength} is the standard deviation in seconds.
     * Yaw is unwrapped first so smoothing never spins the long way round.
     */
    public static List<Sample> stabilize(List<Sample> samples, double strength) {
        if (samples.size() < 3 || strength <= 0) {
            return List.copyOf(samples);
        }
        List<Sample> unwrapped = unwrapYaw(samples);
        List<Sample> out = new ArrayList<>(samples.size());
        double sigma = strength;
        for (Sample center : unwrapped) {
            double wSum = 0;
            double[] acc = new double[7];
            for (Sample s : unwrapped) {
                double d = s.time() - center.time();
                if (Math.abs(d) > 3 * sigma) {
                    continue;
                }
                double w = Math.exp(-d * d / (2 * sigma * sigma));
                acc[0] += w * s.x();
                acc[1] += w * s.y();
                acc[2] += w * s.z();
                acc[3] += w * s.yaw();
                acc[4] += w * s.pitch();
                acc[5] += w * s.roll();
                acc[6] += w * s.fov();
                wSum += w;
            }
            out.add(new Sample(center.time(), acc[0] / wSum, acc[1] / wSum, acc[2] / wSum, acc[3] / wSum, acc[4] / wSum, acc[5] / wSum,
                    acc[6] / wSum));
        }
        return out;
    }

    /** Yaw made continuous: consecutive samples never differ by more than 180°. */
    public static List<Sample> unwrapYaw(List<Sample> samples) {
        List<Sample> out = new ArrayList<>(samples.size());
        double previous = Double.NaN;
        for (Sample s : samples) {
            double yaw = s.yaw();
            if (!Double.isNaN(previous)) {
                yaw = previous + wrap(yaw - previous);
            }
            out.add(new Sample(s.time(), s.x(), s.y(), s.z(), yaw, s.pitch(), s.roll(), s.fov()));
            previous = yaw;
        }
        return out;
    }

    private static double wrap(double d) {
        double r = d % 360;
        if (r > 180) {
            r -= 360;
        }
        if (r < -180) {
            r += 360;
        }
        return r;
    }

    /**
     * Ramer-Douglas-Peucker simplification over time: keeps the samples needed so that linear
     * interpolation between kept samples stays within {@code positionTolerance} blocks and
     * {@code angleTolerance} degrees of every dropped sample. Returns indices of kept samples,
     * always including the first and last.
     */
    public static List<Integer> simplify(List<Sample> samples, double positionTolerance, double angleTolerance) {
        List<Integer> kept = new ArrayList<>();
        if (samples.isEmpty()) {
            return kept;
        }
        List<Sample> s = unwrapYaw(samples);
        boolean[] keep = new boolean[s.size()];
        keep[0] = true;
        keep[s.size() - 1] = true;
        // Iterative to avoid deep recursion on long captures.
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[] {0, s.size() - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int a = range[0];
            int b = range[1];
            if (b - a < 2) {
                continue;
            }
            double worst = 0;
            int worstIndex = -1;
            Sample sa = s.get(a);
            Sample sb = s.get(b);
            for (int i = a + 1; i < b; i++) {
                Sample si = s.get(i);
                double f = (si.time() - sa.time()) / (sb.time() - sa.time());
                double dp = Math.sqrt(sq(lerp(sa.x(), sb.x(), f) - si.x()) + sq(lerp(sa.y(), sb.y(), f) - si.y()) + sq(lerp(sa.z(), sb.z(), f) - si.z()));
                double da = Math.max(Math.abs(lerp(sa.yaw(), sb.yaw(), f) - si.yaw()),
                        Math.max(Math.abs(lerp(sa.pitch(), sb.pitch(), f) - si.pitch()), Math.abs(lerp(sa.roll(), sb.roll(), f) - si.roll())));
                double df = Math.abs(lerp(sa.fov(), sb.fov(), f) - si.fov());
                double error = Math.max(dp / positionTolerance, Math.max(da, df) / angleTolerance);
                if (error > worst) {
                    worst = error;
                    worstIndex = i;
                }
            }
            if (worst > 1 && worstIndex > 0) {
                keep[worstIndex] = true;
                stack.push(new int[] {a, worstIndex});
                stack.push(new int[] {worstIndex, b});
            }
        }
        for (int i = 0; i < keep.length; i++) {
            if (keep[i]) {
                kept.add(i);
            }
        }
        return kept;
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    private static double sq(double v) {
        return v * v;
    }
}
