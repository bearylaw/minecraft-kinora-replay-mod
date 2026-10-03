package dev.kinora.core.camera;

/** An immutable double-precision 3D vector. */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0, 0, 0);
    public static final Vec3d UP = new Vec3d(0, 1, 0);

    public Vec3d add(Vec3d o) {
        return new Vec3d(x + o.x, y + o.y, z + o.z);
    }

    public Vec3d add(double ax, double ay, double az) {
        return new Vec3d(x + ax, y + ay, z + az);
    }

    public Vec3d sub(Vec3d o) {
        return new Vec3d(x - o.x, y - o.y, z - o.z);
    }

    public Vec3d mul(double s) {
        return new Vec3d(x * s, y * s, z * s);
    }

    public double dot(Vec3d o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3d cross(Vec3d o) {
        return new Vec3d(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double distance(Vec3d o) {
        return sub(o).length();
    }

    public Vec3d normalize() {
        double l = length();
        return l < 1e-12 ? ZERO : mul(1.0 / l);
    }

    public Vec3d lerp(Vec3d o, double t) {
        return new Vec3d(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** Yaw (degrees) of this direction in Minecraft's convention. */
    public double yawDegrees() {
        return Math.toDegrees(Math.atan2(-x, z));
    }

    /** Pitch (degrees, positive down) of this direction. */
    public double pitchDegrees() {
        double horizontal = Math.sqrt(x * x + z * z);
        return Math.toDegrees(Math.atan2(-y, horizontal));
    }
}
