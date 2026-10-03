package dev.kinora.core.timeline;

import dev.kinora.core.camera.Quat;
import dev.kinora.core.camera.Vec3d;

import java.util.List;

/** Curve evaluation for {@link Track}. Stateless; all inputs sorted by time with distinct times. */
public final class TrackMath {
    private TrackMath() {}

    // ------------------------------------------------------------------ value curves

    /** Independent per-component curves, {@code t} strictly inside the keys' time range. */
    public static double[] evaluateValue(List<Keyframe> keys, double t) {
        int i = Track.segmentIndex(keys, t);
        Keyframe k0 = keys.get(i);
        Keyframe k1 = keys.get(i + 1);
        double h = k1.time - k0.time;
        double s = h <= 0 ? 1 : (t - k0.time) / h;
        int dim = k0.value.length;
        double[] out = new double[dim];
        switch (k0.interpolation) {
            case HOLD -> System.arraycopy(k0.value, 0, out, 0, dim);
            case LINEAR -> {
                double u = k0.ease(s);
                for (int c = 0; c < dim; c++) {
                    out[c] = k0.value[c] + (k1.value[c] - k0.value[c]) * u;
                }
            }
            case CATMULL_ROM -> {
                double u = k0.ease(s);
                for (int c = 0; c < dim; c++) {
                    out[c] = hermite(k0.value[c], k1.value[c], tangent(keys, i, c) * h, tangent(keys, i + 1, c) * h, u);
                }
            }
            case CENTRIPETAL -> {
                double u = k0.ease(s);
                for (int c = 0; c < dim; c++) {
                    out[c] = hermite(k0.value[c], k1.value[c], monotoneTangent(keys, i, c) * h, monotoneTangent(keys, i + 1, c) * h, u);
                }
            }
            case BEZIER -> {
                double outDt = k0.outTime > 0 ? Math.min(k0.outTime, h) : h / 3;
                double inDt = k1.inTime > 0 ? Math.min(k1.inTime, h) : h / 3;
                double u = Easing.solveForX(outDt / h, 1 - inDt / h, s);
                for (int c = 0; c < dim; c++) {
                    double p1 = k0.value[c] + (k0.outValue != null ? k0.outValue[c] : tangent(keys, i, c) * outDt);
                    double p2 = k1.value[c] + (k1.inValue != null ? k1.inValue[c] : -tangent(keys, i + 1, c) * inDt);
                    out[c] = cubic(k0.value[c], p1, p2, k1.value[c], u);
                }
            }
        }
        return out;
    }

    /** Catmull-Rom tangent (value per second) for non-uniform key spacing. */
    static double tangent(List<Keyframe> keys, int i, int c) {
        int n = keys.size();
        if (n < 2) {
            return 0;
        }
        if (i == 0) {
            return (keys.get(1).value[c] - keys.get(0).value[c]) / (keys.get(1).time - keys.get(0).time);
        }
        if (i == n - 1) {
            return (keys.get(n - 1).value[c] - keys.get(n - 2).value[c]) / (keys.get(n - 1).time - keys.get(n - 2).time);
        }
        return (keys.get(i + 1).value[c] - keys.get(i - 1).value[c]) / (keys.get(i + 1).time - keys.get(i - 1).time);
    }

    /** Steffen's monotone tangent: the curve never overshoots the keys. */
    static double monotoneTangent(List<Keyframe> keys, int i, int c) {
        int n = keys.size();
        if (n < 2) {
            return 0;
        }
        if (i == 0 || i == n - 1) {
            int a = i == 0 ? 0 : n - 2;
            double h = keys.get(a + 1).time - keys.get(a).time;
            double d = (keys.get(a + 1).value[c] - keys.get(a).value[c]) / h;
            // One-sided end tangent, limited so the end segment stays monotone.
            if (n == 2) {
                return d;
            }
            int b = i == 0 ? 1 : n - 3;
            double h2 = keys.get(b + 1).time - keys.get(b).time;
            double d2 = (keys.get(b + 1).value[c] - keys.get(b).value[c]) / h2;
            double p = d * (1 + h / (h + h2)) - d2 * h / (h + h2);
            if (p * d <= 0) {
                return 0;
            }
            return Math.abs(p) > 2 * Math.abs(d) ? 2 * d : p;
        }
        double h0 = keys.get(i).time - keys.get(i - 1).time;
        double h1 = keys.get(i + 1).time - keys.get(i).time;
        double d0 = (keys.get(i).value[c] - keys.get(i - 1).value[c]) / h0;
        double d1 = (keys.get(i + 1).value[c] - keys.get(i).value[c]) / h1;
        double p = (d0 * h1 + d1 * h0) / (h0 + h1);
        return (Math.signum(d0) + Math.signum(d1)) * Math.min(Math.min(Math.abs(d0), Math.abs(d1)), 0.5 * Math.abs(p));
    }

    /** Cubic Hermite with tangents already scaled to the segment. */
    static double hermite(double p0, double p1, double m0, double m1, double u) {
        double u2 = u * u;
        double u3 = u2 * u;
        return (2 * u3 - 3 * u2 + 1) * p0 + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * p1 + (u3 - u2) * m1;
    }

    static double cubic(double p0, double p1, double p2, double p3, double u) {
        double v = 1 - u;
        return v * v * v * p0 + 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u * p3;
    }

    // ------------------------------------------------------------------ paths

    /** A 3D path, {@code t} strictly inside the keys' time range; timing per segment from easing. */
    public static double[] evaluatePath(List<Keyframe> keys, double t) {
        int i = Track.segmentIndex(keys, t);
        Keyframe k0 = keys.get(i);
        Keyframe k1 = keys.get(i + 1);
        double h = k1.time - k0.time;
        double s = h <= 0 ? 1 : (t - k0.time) / h;
        if (k0.interpolation == Interpolation.HOLD) {
            return k0.value.clone();
        }
        return pathPoint(keys, i, k0.ease(s));
    }

    /** Point on segment {@code i} at spatial parameter {@code u} in [0, 1] (no easing applied). */
    public static double[] pathPoint(List<Keyframe> keys, int i, double u) {
        Keyframe k0 = keys.get(i);
        Keyframe k1 = keys.get(i + 1);
        Vec3d p1 = vec(k0);
        Vec3d p2 = vec(k1);
        Vec3d p0 = i > 0 ? vec(keys.get(i - 1)) : p1.mul(2).sub(p2);
        Vec3d p3 = i + 2 < keys.size() ? vec(keys.get(i + 2)) : p2.mul(2).sub(p1);
        Vec3d r = switch (k0.interpolation) {
            case HOLD -> p1;
            case LINEAR -> p1.lerp(p2, u);
            case CATMULL_ROM -> barryGoldman(p0, p1, p2, p3, 0.0, u);
            case CENTRIPETAL -> barryGoldman(p0, p1, p2, p3, 0.5, u);
            case BEZIER -> {
                Vec3d c1 = k0.outValue != null ? p1.add(new Vec3d(k0.outValue[0], k0.outValue[1], k0.outValue[2])) : p1.add(p2.sub(p0).mul(1.0 / 6));
                Vec3d c2 = k1.inValue != null ? p2.add(new Vec3d(k1.inValue[0], k1.inValue[1], k1.inValue[2])) : p2.sub(p3.sub(p1).mul(1.0 / 6));
                yield new Vec3d(cubic(p1.x(), c1.x(), c2.x(), p2.x(), u), cubic(p1.y(), c1.y(), c2.y(), p2.y(), u),
                        cubic(p1.z(), c1.z(), c2.z(), p2.z(), u));
            }
        };
        return new double[] {r.x(), r.y(), r.z()};
    }

    private static Vec3d vec(Keyframe k) {
        return new Vec3d(k.value[0], k.value[1], k.value[2]);
    }

    /**
     * Catmull-Rom between p1 and p2 by the Barry-Goldman pyramid with knot exponent
     * {@code alpha}: 0 uniform, 0.5 centripetal (no cusps or self-intersections), 1 chordal.
     */
    static Vec3d barryGoldman(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double alpha, double u) {
        double t0 = 0;
        double t1 = t0 + knot(p0, p1, alpha);
        double t2 = t1 + knot(p1, p2, alpha);
        double t3 = t2 + knot(p2, p3, alpha);
        double t = t1 + (t2 - t1) * u;
        Vec3d a1 = mix(p0, p1, t0, t1, t);
        Vec3d a2 = mix(p1, p2, t1, t2, t);
        Vec3d a3 = mix(p2, p3, t2, t3, t);
        Vec3d b1 = mix(a1, a2, t0, t2, t);
        Vec3d b2 = mix(a2, a3, t1, t3, t);
        return mix(b1, b2, t1, t2, t);
    }

    private static double knot(Vec3d a, Vec3d b, double alpha) {
        double d = Math.pow(a.distance(b), alpha);
        return Math.max(d, 1e-6);
    }

    private static Vec3d mix(Vec3d a, Vec3d b, double ta, double tb, double t) {
        double span = tb - ta;
        if (span < 1e-12) {
            return a;
        }
        return a.mul((tb - t) / span).add(b.mul((t - ta) / span));
    }

    // ------------------------------------------------------------------ rotation

    /** Yaw and pitch (degrees) interpolated as directions on the sphere. */
    public static double[] evaluateRotation(List<Keyframe> keys, double t) {
        int i = Track.segmentIndex(keys, t);
        Keyframe k0 = keys.get(i);
        Keyframe k1 = keys.get(i + 1);
        double h = k1.time - k0.time;
        double s = h <= 0 ? 1 : (t - k0.time) / h;
        if (k0.interpolation == Interpolation.HOLD) {
            return k0.value.clone();
        }
        double u = k0.ease(s);
        Quat q1 = direction(k0);
        Quat q2 = direction(k1);
        Quat q;
        if (k0.interpolation == Interpolation.LINEAR) {
            q = Quat.slerp(q1, q2, u);
        } else {
            Quat q0 = i > 0 ? direction(keys.get(i - 1)) : q1;
            Quat q3 = i + 2 < keys.size() ? direction(keys.get(i + 2)) : q2;
            q = Quat.squad(q0, q1, q2, q3, u);
        }
        double[] ypr = q.toYawPitchRoll();
        // Keep yaw continuous with the keys rather than wrapping into (-180, 180].
        double reference = k0.value[0] + (k1.value[0] - k0.value[0]) * u;
        double yaw = ypr[0] + 360 * Math.round((reference - ypr[0]) / 360);
        double[] out = keys.get(i).value.length > 2 ? new double[] {yaw, ypr[1], k0.value[2] + (k1.value[2] - k0.value[2]) * u} : new double[] {yaw, ypr[1]};
        return out;
    }

    private static Quat direction(Keyframe k) {
        return Quat.fromYawPitchRoll(k.value[0], k.value[1], 0);
    }
}
