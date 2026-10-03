package dev.kinora.core.timeline;

/**
 * Easing curves: maps a segment's progress {@code s} in [0, 1] to eased progress, with
 * {@code f(0) = 0} and {@code f(1) = 1}.
 */
public enum Easing {
    LINEAR,
    SINE_IN, SINE_OUT, SINE_IN_OUT,
    QUAD_IN, QUAD_OUT, QUAD_IN_OUT,
    CUBIC_IN, CUBIC_OUT, CUBIC_IN_OUT,
    EXPO_IN, EXPO_OUT, EXPO_IN_OUT,
    /** A cubic-bezier curve given by the keyframe's own control points (CSS {@code cubic-bezier}). */
    CUSTOM;

    public double apply(double s) {
        double t = Math.max(0, Math.min(1, s));
        return switch (this) {
            case LINEAR, CUSTOM -> t;
            case SINE_IN -> 1 - Math.cos(t * Math.PI / 2);
            case SINE_OUT -> Math.sin(t * Math.PI / 2);
            case SINE_IN_OUT -> -(Math.cos(Math.PI * t) - 1) / 2;
            case QUAD_IN -> t * t;
            case QUAD_OUT -> 1 - (1 - t) * (1 - t);
            case QUAD_IN_OUT -> t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
            case CUBIC_IN -> t * t * t;
            case CUBIC_OUT -> 1 - Math.pow(1 - t, 3);
            case CUBIC_IN_OUT -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
            case EXPO_IN -> t == 0 ? 0 : Math.pow(2, 10 * t - 10);
            case EXPO_OUT -> t == 1 ? 1 : 1 - Math.pow(2, -10 * t);
            case EXPO_IN_OUT -> t == 0 ? 0 : t == 1 ? 1 : t < 0.5 ? Math.pow(2, 20 * t - 10) / 2 : (2 - Math.pow(2, -20 * t + 10)) / 2;
        };
    }

    /**
     * CSS-style {@code cubic-bezier(x1, y1, x2, y2)}: the curve from (0,0) to (1,1) with those two
     * control points, evaluated at x = {@code s}. x1 and x2 are clamped to [0, 1] so x is
     * monotonic and the curve is a function.
     */
    public static double cubicBezier(double x1, double y1, double x2, double y2, double s) {
        double t = Math.max(0, Math.min(1, s));
        double cx1 = Math.max(0, Math.min(1, x1));
        double cx2 = Math.max(0, Math.min(1, x2));
        if (t == 0 || t == 1) {
            return t;
        }
        double u = solveForX(cx1, cx2, t);
        return bezier1d(y1, y2, u);
    }

    /** Bezier component with endpoints 0 and 1 and inner points a, b, at parameter u. */
    static double bezier1d(double a, double b, double u) {
        double inv = 1 - u;
        return 3 * inv * inv * u * a + 3 * inv * u * u * b + u * u * u;
    }

    private static double bezier1dDerivative(double a, double b, double u) {
        double inv = 1 - u;
        return 3 * inv * inv * a + 6 * inv * u * (b - a) + 3 * u * u * (1 - b);
    }

    /** Parameter u where the x component equals x: Newton steps, then bisection as a fallback. */
    static double solveForX(double x1, double x2, double x) {
        double u = x;
        for (int i = 0; i < 8; i++) {
            double err = bezier1d(x1, x2, u) - x;
            if (Math.abs(err) < 1e-12) {
                return u;
            }
            double d = bezier1dDerivative(x1, x2, u);
            if (Math.abs(d) < 1e-9) {
                break;
            }
            u -= err / d;
            if (u < 0 || u > 1) {
                break;
            }
        }
        double lo = 0;
        double hi = 1;
        u = x;
        for (int i = 0; i < 60; i++) {
            double v = bezier1d(x1, x2, u);
            if (Math.abs(v - x) < 1e-12) {
                break;
            }
            if (v < x) {
                lo = u;
            } else {
                hi = u;
            }
            u = (lo + hi) / 2;
        }
        return u;
    }
}
