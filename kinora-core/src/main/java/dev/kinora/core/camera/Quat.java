package dev.kinora.core.camera;

/**
 * A double-precision unit quaternion for camera orientation.
 *
 * <p>Orientation from Minecraft angles: yaw about the world Y axis (clockwise seen from above, 0
 * facing +Z), then pitch about the camera's X axis (positive looks down), then roll about the view
 * axis. {@link #fromYawPitchRoll} and {@link #toYawPitchRoll} convert both ways; the conversion is
 * exact except at the poles (pitch ±90°), where yaw and roll are not separable.
 */
public record Quat(double w, double x, double y, double z) {
    public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

    public static Quat axisAngle(double ax, double ay, double az, double radians) {
        double len = Math.sqrt(ax * ax + ay * ay + az * az);
        double s = Math.sin(radians / 2) / len;
        return new Quat(Math.cos(radians / 2), ax * s, ay * s, az * s);
    }

    /**
     * Orientation for Minecraft yaw, pitch and roll in degrees. The camera looks along +Z at
     * identity; yaw turns it toward -X (Minecraft's convention), pitch tilts it down.
     */
    public static Quat fromYawPitchRoll(double yaw, double pitch, double roll) {
        Quat qYaw = axisAngle(0, 1, 0, Math.toRadians(-yaw));
        Quat qPitch = axisAngle(1, 0, 0, Math.toRadians(pitch));
        Quat qRoll = axisAngle(0, 0, 1, Math.toRadians(roll));
        return qYaw.mul(qPitch).mul(qRoll).normalize();
    }

    /** Inverse of {@link #fromYawPitchRoll}: {yaw, pitch, roll} in degrees, yaw in (-180, 180]. */
    public double[] toYawPitchRoll() {
        Vec3d forward = rotate(new Vec3d(0, 0, 1));
        Vec3d up = rotate(new Vec3d(0, 1, 0));
        double yaw = forward.yawDegrees();
        double pitch = forward.pitchDegrees();
        // Roll: angle between the actual up vector and the up vector of the same yaw/pitch at roll 0.
        Quat noRoll = fromYawPitchRoll(yaw, pitch, 0);
        Vec3d up0 = noRoll.rotate(new Vec3d(0, 1, 0));
        Vec3d right0 = noRoll.rotate(new Vec3d(1, 0, 0));
        double roll = Math.toDegrees(Math.atan2(up.dot(right0), up.dot(up0)));
        // atan2's sign convention gives the image rotation; match fromYawPitchRoll's.
        return new double[] {yaw, pitch, -roll};
    }

    public Quat mul(Quat o) {
        return new Quat(
                w * o.w - x * o.x - y * o.y - z * o.z,
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w);
    }

    public Quat conjugate() {
        return new Quat(w, -x, -y, -z);
    }

    public Quat negate() {
        return new Quat(-w, -x, -y, -z);
    }

    public double dot(Quat o) {
        return w * o.w + x * o.x + y * o.y + z * o.z;
    }

    public Quat normalize() {
        double n = Math.sqrt(dot(this));
        return n < 1e-15 ? IDENTITY : new Quat(w / n, x / n, y / n, z / n);
    }

    public Vec3d rotate(Vec3d v) {
        // v' = q v q*
        Quat p = new Quat(0, v.x(), v.y(), v.z());
        Quat r = mul(p).mul(conjugate());
        return new Vec3d(r.x, r.y, r.z);
    }

    /** Spherical interpolation along the shorter arc. */
    public static Quat slerp(Quat a, Quat b, double t) {
        return slerp(a, b, t, true);
    }

    /**
     * Spherical interpolation.
     *
     * @param shortest take the shorter of the two arcs between the orientations; when false, the
     *                 quaternions are used as given, so the caller decides the direction
     */
    public static Quat slerp(Quat a, Quat b, double t, boolean shortest) {
        double cos = a.dot(b);
        Quat end = b;
        if (shortest && cos < 0) {
            cos = -cos;
            end = b.negate();
        }
        if (cos > 0.9995) {
            // Nearly parallel: linear interpolation is accurate and avoids dividing by ~0.
            return new Quat(a.w + (end.w - a.w) * t, a.x + (end.x - a.x) * t, a.y + (end.y - a.y) * t, a.z + (end.z - a.z) * t).normalize();
        }
        double theta = Math.acos(Math.max(-1, Math.min(1, cos)));
        double sin = Math.sin(theta);
        double wa = Math.sin((1 - t) * theta) / sin;
        double wb = Math.sin(t * theta) / sin;
        return new Quat(a.w * wa + end.w * wb, a.x * wa + end.x * wb, a.y * wa + end.y * wb, a.z * wa + end.z * wb);
    }

    /** Quaternion logarithm (of a unit quaternion): a pure quaternion. */
    public Quat log() {
        double v = Math.sqrt(x * x + y * y + z * z);
        if (v < 1e-12) {
            return new Quat(0, 0, 0, 0);
        }
        double a = Math.atan2(v, w) / v;
        return new Quat(0, x * a, y * a, z * a);
    }

    /** Quaternion exponential of a pure quaternion. */
    public Quat exp() {
        double v = Math.sqrt(x * x + y * y + z * z);
        if (v < 1e-12) {
            return IDENTITY;
        }
        double s = Math.sin(v) / v;
        return new Quat(Math.cos(v), x * s, y * s, z * s);
    }

    private Quat add(Quat o) {
        return new Quat(w + o.w, x + o.x, y + o.y, z + o.z);
    }

    private Quat scale(double s) {
        return new Quat(w * s, x * s, y * s, z * s);
    }

    /** Squad inner control point for {@code q1} between neighbours {@code q0} and {@code q2}. */
    public static Quat squadControl(Quat q0, Quat q1, Quat q2) {
        Quat inv = q1.conjugate();
        Quat a = inv.mul(q0).log();
        Quat b = inv.mul(q2).log();
        return q1.mul(a.add(b).scale(-0.25).exp()).normalize();
    }

    /**
     * Spherical quadrangle interpolation: a smooth (C1) curve through q1 and q2, shaped by their
     * neighbours, the rotational counterpart of a Catmull-Rom spline.
     */
    public static Quat squad(Quat q0, Quat q1, Quat q2, Quat q3, double t) {
        // Keep consecutive quaternions in the same hemisphere so the curve takes short arcs.
        Quat p0 = q0.dot(q1) < 0 ? q0.negate() : q0;
        Quat p2 = q1.dot(q2) < 0 ? q2.negate() : q2;
        Quat p3 = p2.dot(q3) < 0 ? q3.negate() : q3;
        Quat s1 = squadControl(p0, q1, p2);
        Quat s2 = squadControl(q1, p2, p3);
        return slerp(slerp(q1, p2, t, false), slerp(s1, s2, t, false), 2 * t * (1 - t), false).normalize();
    }

    /** Angle between two orientations, in degrees. */
    public static double angleBetween(Quat a, Quat b) {
        double d = Math.abs(a.dot(b));
        return Math.toDegrees(2 * Math.acos(Math.min(1, d)));
    }
}
