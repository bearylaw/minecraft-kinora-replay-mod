package dev.kinora.core.camera;

/**
 * Physical lens model: converts between focal length and field of view for a sensor, the way
 * cinematographers think ("a 35 mm lens on Super 35").
 */
public final class Lens {
    /** Common sensors, with the height of the image area used for vertical FOV (16:9 crops). */
    public enum Sensor {
        SUPER_35(24.89, 14.0),
        FULL_FRAME(36.0, 20.25),
        MICRO_FOUR_THIRDS(17.3, 9.73),
        IMAX(70.41, 39.6);

        public final double widthMm;
        public final double heightMm;

        Sensor(double widthMm, double heightMm) {
            this.widthMm = widthMm;
            this.heightMm = heightMm;
        }
    }

    private Lens() {}

    /** Vertical field of view in degrees for a focal length on a sensor of the given height. */
    public static double verticalFov(double focalLengthMm, double sensorHeightMm) {
        return Math.toDegrees(2 * Math.atan(sensorHeightMm / (2 * focalLengthMm)));
    }

    /** Focal length in mm giving the vertical field of view on a sensor of the given height. */
    public static double focalLength(double verticalFovDegrees, double sensorHeightMm) {
        return sensorHeightMm / (2 * Math.tan(Math.toRadians(verticalFovDegrees) / 2));
    }

    /** Horizontal FOV for a vertical FOV and an aspect ratio (width / height). */
    public static double horizontalFov(double verticalFovDegrees, double aspect) {
        return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(verticalFovDegrees) / 2) * aspect));
    }
}
