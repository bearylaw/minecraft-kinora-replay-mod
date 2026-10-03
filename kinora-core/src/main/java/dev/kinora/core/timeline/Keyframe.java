package dev.kinora.core.timeline;

import java.util.Arrays;
import java.util.Objects;

/**
 * A value at a moment, plus how the curve leaves it.
 *
 * <p>Handles are offsets from the key. For value tracks they are points on the time/value graph
 * ({@code outTime} seconds after the key, {@code outValue} added to its value); for path tracks
 * only the value offsets are used, as 3D control points, and timing comes from the easing.
 * A null handle means "automatic" (a smooth Catmull-Rom-like tangent).
 */
public final class Keyframe {
    /** Time on the shot's output timeline, in seconds. */
    public double time;
    public double[] value;
    /** Interpolation of the segment that starts at this key. */
    public Interpolation interpolation = Interpolation.CATMULL_ROM;
    /** Timing of the segment that starts at this key. */
    public Easing easing = Easing.LINEAR;
    /** Control points for {@link Easing#CUSTOM}: x1, y1, x2, y2. */
    public double[] easingCurve;
    public double inTime;
    public double outTime;
    public double[] inValue;
    public double[] outValue;
    /** Free-form label shown in the editor. */
    public String label;

    public Keyframe() {
    }

    public Keyframe(double time, double... value) {
        this.time = time;
        this.value = value.clone();
    }

    public Keyframe withInterpolation(Interpolation interpolation) {
        this.interpolation = interpolation;
        return this;
    }

    public Keyframe withEasing(Easing easing) {
        this.easing = easing;
        return this;
    }

    public Keyframe copy() {
        Keyframe k = new Keyframe(time, value);
        k.interpolation = interpolation;
        k.easing = easing;
        k.easingCurve = easingCurve == null ? null : easingCurve.clone();
        k.inTime = inTime;
        k.outTime = outTime;
        k.inValue = inValue == null ? null : inValue.clone();
        k.outValue = outValue == null ? null : outValue.clone();
        k.label = label;
        return k;
    }

    /** Applies this segment's easing to progress {@code s}. */
    public double ease(double s) {
        if (easing == Easing.CUSTOM && easingCurve != null && easingCurve.length == 4) {
            return Easing.cubicBezier(easingCurve[0], easingCurve[1], easingCurve[2], easingCurve[3], s);
        }
        return easing.apply(s);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Keyframe k && k.time == time && Arrays.equals(k.value, value) && k.interpolation == interpolation
                && k.easing == easing && Arrays.equals(k.easingCurve, easingCurve) && k.inTime == inTime && k.outTime == outTime
                && Arrays.equals(k.inValue, inValue) && Arrays.equals(k.outValue, outValue) && Objects.equals(k.label, label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(time, Arrays.hashCode(value), interpolation, easing);
    }

    @Override
    public String toString() {
        return "Keyframe[t=" + time + " v=" + Arrays.toString(value) + " " + interpolation + "/" + easing + "]";
    }
}
