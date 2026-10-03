package dev.kinora.core.timeline;

import java.util.Arrays;

/**
 * Ready-made Bezier handles for a key, so curves can be shaped without dragging: automatic (smooth
 * through the neighbours), ease (flat: the value comes to rest at the key) and linear (aimed at the
 * neighbouring keys).
 */
public final class Handles {
    public enum Preset { AUTO, EASE, LINEAR, CUSTOM }

    private Handles() {}

    /** Which preset a key's handles match. */
    public static Preset of(Track track, Keyframe k) {
        if (k.inValue == null && k.outValue == null) {
            return Preset.AUTO;
        }
        if (isZero(k.inValue) && isZero(k.outValue)) {
            return Preset.EASE;
        }
        Keyframe linear = k.copy();
        apply(track, linear, Preset.LINEAR);
        return Arrays.equals(linear.inValue, k.inValue) && Arrays.equals(linear.outValue, k.outValue) ? Preset.LINEAR : Preset.CUSTOM;
    }

    /** Sets the key's handles to a preset (CUSTOM leaves them). Handle times go back to a third of each segment. */
    public static void apply(Track track, Keyframe k, Preset preset) {
        int n = k.value.length;
        switch (preset) {
            case AUTO -> {
                k.inValue = null;
                k.outValue = null;
                k.inTime = 0;
                k.outTime = 0;
            }
            case EASE -> {
                k.inValue = new double[n];
                k.outValue = new double[n];
                k.inTime = 0;
                k.outTime = 0;
            }
            case LINEAR -> {
                int i = track.keys.indexOf(k);
                Keyframe prev = i > 0 ? track.keys.get(i - 1) : null;
                Keyframe next = i >= 0 && i + 1 < track.keys.size() ? track.keys.get(i + 1) : null;
                k.inValue = new double[n];
                k.outValue = new double[n];
                for (int c = 0; c < n; c++) {
                    k.inValue[c] = prev == null ? 0 : (prev.value[c] - k.value[c]) / 3;
                    k.outValue[c] = next == null ? 0 : (next.value[c] - k.value[c]) / 3;
                }
                k.inTime = 0;
                k.outTime = 0;
            }
            case CUSTOM -> {
                // Unchanged.
            }
        }
        track.changed();
    }

    private static boolean isZero(double[] v) {
        if (v == null) {
            return false;
        }
        for (double d : v) {
            if (d != 0) {
                return false;
            }
        }
        return true;
    }
}
