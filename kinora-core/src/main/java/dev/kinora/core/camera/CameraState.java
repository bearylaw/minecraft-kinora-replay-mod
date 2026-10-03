package dev.kinora.core.camera;

/**
 * Where a camera is and how it looks, in Minecraft's conventions: degrees, yaw 0 facing +Z
 * (south) and increasing clockwise seen from above, pitch positive looking down, roll positive
 * tilting the image clockwise. All double precision so nothing jitters far from the origin.
 *
 * @param fov vertical field of view in degrees
 */
public record CameraState(double x, double y, double z, double yaw, double pitch, double roll, double fov) {
    public static final double DEFAULT_FOV = 70.0;

    public CameraState withPosition(double nx, double ny, double nz) {
        return new CameraState(nx, ny, nz, yaw, pitch, roll, fov);
    }

    public CameraState withRotation(double nyaw, double npitch, double nroll) {
        return new CameraState(x, y, z, nyaw, npitch, nroll, fov);
    }

    public CameraState withFov(double nfov) {
        return new CameraState(x, y, z, yaw, pitch, roll, nfov);
    }

    public Vec3d position() {
        return new Vec3d(x, y, z);
    }

    /** Unit vector the camera looks along. */
    public Vec3d forward() {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        return new Vec3d(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
    }
}
