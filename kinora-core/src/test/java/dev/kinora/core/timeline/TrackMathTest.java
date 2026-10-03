package dev.kinora.core.timeline;

import dev.kinora.core.camera.Quat;
import dev.kinora.core.camera.Vec3d;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackMathTest {

    @ParameterizedTest
    @EnumSource(Easing.class)
    void easingsStartAtZeroEndAtOneAndAreMonotone(Easing easing) {
        assertEquals(0, easing.apply(0), 1e-9);
        assertEquals(1, easing.apply(1), 1e-9);
        double previous = 0;
        for (int i = 1; i <= 1000; i++) {
            double v = easing.apply(i / 1000.0);
            assertTrue(v >= previous - 1e-12, easing + " decreases at " + i);
            previous = v;
        }
    }

    @Test
    void cubicBezierMatchesTheCssEaseCurve() {
        // CSS "ease" = cubic-bezier(0.25, 0.1, 0.25, 1); its value at x = 0.5 is 0.8024033877399112.
        assertEquals(0.8024033877, Easing.cubicBezier(0.25, 0.1, 0.25, 1, 0.5), 1e-6);
        assertEquals(0.5, Easing.cubicBezier(0, 0, 1, 1, 0.5), 1e-9);
    }

    @Test
    void quaternionRoundTripsMinecraftAngles() {
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            double yaw = random.nextDouble() * 360 - 180;
            double pitch = random.nextDouble() * 170 - 85;
            double roll = random.nextDouble() * 120 - 60;
            double[] back = Quat.fromYawPitchRoll(yaw, pitch, roll).toYawPitchRoll();
            assertEquals(0, wrap(back[0] - yaw), 1e-6, "yaw");
            assertEquals(pitch, back[1], 1e-6, "pitch");
            assertEquals(roll, back[2], 1e-6, "roll");
        }
    }

    @Test
    void quaternionForwardMatchesMinecraftViewVector() {
        Vec3d f = Quat.fromYawPitchRoll(90, 0, 0).rotate(new Vec3d(0, 0, 1));
        assertEquals(-1, f.x(), 1e-9);
        assertEquals(0, f.z(), 1e-9);
        Vec3d down = Quat.fromYawPitchRoll(0, 90, 0).rotate(new Vec3d(0, 0, 1));
        assertEquals(-1, down.y(), 1e-9);
    }

    @Test
    void slerpTakesTheShortWayAcrossTheSeam() {
        Track track = new Track("rot", Track.Kind.ROTATION, 2);
        track.put(new Keyframe(0, 170, 0).withInterpolation(Interpolation.LINEAR));
        track.put(new Keyframe(1, -170, 0));
        double mid = track.evaluate(0.5)[0];
        assertEquals(180, Math.abs(wrap(mid)), 1e-6);
        // FREE mode goes the long way, through 0.
        track.rotationMode = Track.RotationMode.FREE;
        assertEquals(0, track.evaluate(0.5)[0], 1e-9);
    }

    @ParameterizedTest
    @EnumSource(value = Interpolation.class, names = {"LINEAR", "CATMULL_ROM", "CENTRIPETAL", "BEZIER"})
    void valueCurvesPassThroughKeysAndAreContinuous(Interpolation mode) {
        Track track = new Track("v", Track.Kind.VALUE, 2);
        double[][] values = {{0, 10}, {3, -2}, {3.5, 4}, {-1, 4}, {8, 0}};
        double[] times = {0, 1, 1.4, 3, 3.2};
        for (int i = 0; i < values.length; i++) {
            track.put(new Keyframe(times[i], values[i]).withInterpolation(mode));
        }
        for (int i = 0; i < values.length; i++) {
            assertArrayEquals(values[i], track.evaluate(times[i]), 1e-9);
            if (i > 0 && i < values.length - 1) {
                double[] left = track.evaluate(times[i] - 1e-7);
                double[] right = track.evaluate(times[i] + 1e-7);
                assertArrayEquals(left, right, 1e-4, mode + " jumps at key " + i);
            }
        }
    }

    @Test
    void monotoneCurvesNeverOvershoot() {
        Track track = new Track("v", Track.Kind.VALUE, 1);
        double[] v = {0, 10, 10, 11, 0, 0, 5};
        for (int i = 0; i < v.length; i++) {
            track.put(new Keyframe(i * 0.7 + (i % 2) * 0.3, v[i]).withInterpolation(Interpolation.CENTRIPETAL));
        }
        for (int i = 0; i < v.length - 1; i++) {
            double lo = Math.min(v[i], v[i + 1]);
            double hi = Math.max(v[i], v[i + 1]);
            double t0 = track.keys.get(i).time;
            double t1 = track.keys.get(i + 1).time;
            for (int j = 0; j <= 100; j++) {
                double x = track.evaluate(t0 + (t1 - t0) * j / 100.0)[0];
                assertTrue(x >= lo - 1e-9 && x <= hi + 1e-9, "overshoot in segment " + i + ": " + x);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Interpolation.class, names = {"LINEAR", "CATMULL_ROM", "CENTRIPETAL", "BEZIER"})
    void pathsPassThroughKeys(Interpolation mode) {
        Track path = path(mode, false);
        for (Keyframe k : path.keys) {
            assertArrayEquals(k.value, path.evaluate(k.time), 1e-9);
        }
    }

    @Test
    void constantSpeedTravelsEqualDistancesInEqualTimes() {
        Track path = path(Interpolation.CENTRIPETAL, true);
        double t0 = path.startTime();
        double t1 = path.endTime();
        int steps = 400;
        double total = path.arcTable().totalLength();
        double expected = total / steps;
        double[] previous = path.evaluate(t0);
        double worst = 0;
        for (int i = 1; i <= steps; i++) {
            double[] p = path.evaluate(t0 + (t1 - t0) * i / steps);
            double d = Math.sqrt(Math.pow(p[0] - previous[0], 2) + Math.pow(p[1] - previous[1], 2) + Math.pow(p[2] - previous[2], 2));
            worst = Math.max(worst, Math.abs(d - expected) / expected);
            previous = p;
        }
        assertTrue(worst < 0.01, "speed varies by " + worst * 100 + "%");
        assertArrayEquals(path.keys.getLast().value, path.evaluate(t1), 1e-9);
    }

    @Test
    void centripetalPathsHaveNoLoopsOnTightCorners() {
        // Uniform Catmull-Rom loops around a close pair of points; centripetal must not
        // travel further from the segment than the neighbouring keys do.
        Track path = new Track("p", Track.Kind.PATH, 3);
        path.put(new Keyframe(0, 0, 0, 0).withInterpolation(Interpolation.CENTRIPETAL));
        path.put(new Keyframe(1, 10, 0, 0).withInterpolation(Interpolation.CENTRIPETAL));
        path.put(new Keyframe(2, 10.1, 0, 0.2).withInterpolation(Interpolation.CENTRIPETAL));
        path.put(new Keyframe(3, 0, 0, 10).withInterpolation(Interpolation.CENTRIPETAL));
        for (int j = 0; j <= 100; j++) {
            double[] p = path.evaluate(1 + j / 100.0);
            assertTrue(p[0] <= 10.5 && p[0] >= 9.5, "loop: " + p[0]);
        }
    }

    private static Track path(Interpolation mode, boolean constantSpeed) {
        Track path = new Track("p", Track.Kind.PATH, 3);
        path.constantSpeed = constantSpeed;
        double[][] points = {{0, 64, 0}, {10, 70, 5}, {12, 70, 30}, {-5, 66, 40}, {-20, 80, 10}};
        double[] times = {0, 0.5, 4, 4.5, 9};
        for (int i = 0; i < points.length; i++) {
            path.put(new Keyframe(times[i], points[i]).withInterpolation(mode));
        }
        return path;
    }

    private static double wrap(double degrees) {
        double d = degrees % 360;
        if (d > 180) {
            d -= 360;
        }
        if (d <= -180) {
            d += 360;
        }
        return d;
    }
}
