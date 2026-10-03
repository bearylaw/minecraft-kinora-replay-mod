package dev.kinora.core.timeline;

/** How a track moves from one keyframe to the next. Set per segment, on the segment's first key. */
public enum Interpolation {
    /** Keep this key's value until the next key. */
    HOLD,
    /** Straight line. */
    LINEAR,
    /** Smooth curve through the keys (uniform Catmull-Rom). Can overshoot. */
    CATMULL_ROM,
    /**
     * Smooth curve that never overshoots: centripetal Catmull-Rom for paths (no loops or cusps),
     * monotone cubic for values.
     */
    CENTRIPETAL,
    /** Cubic Bezier shaped by the keys' handles. */
    BEZIER
}
