package dev.kinora.core.timeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlesTest {
    private static Track ramp() {
        Track t = new Track("value", Track.Kind.VALUE, 1);
        t.put(new Keyframe(0, 0).withInterpolation(Interpolation.BEZIER));
        t.put(new Keyframe(3, 30).withInterpolation(Interpolation.BEZIER));
        return t;
    }

    @Test
    void easeComesToRestAtTheKeys() {
        Track t = ramp();
        for (Keyframe k : java.util.List.copyOf(t.keys)) {
            Handles.apply(t, k, Handles.Preset.EASE);
        }
        assertEquals(Handles.Preset.EASE, Handles.of(t, t.keys.getFirst()));
        // Slow at both ends, half way in the middle.
        assertEquals(15, t.evaluate(1.5)[0], 1e-6);
        assertTrue(t.evaluate(0.3)[0] < 3, "ease in: " + t.evaluate(0.3)[0]);
    }

    @Test
    void linearHandlesMakeAStraightLine() {
        Track t = ramp();
        for (Keyframe k : java.util.List.copyOf(t.keys)) {
            Handles.apply(t, k, Handles.Preset.LINEAR);
        }
        assertEquals(Handles.Preset.LINEAR, Handles.of(t, t.keys.getLast()));
        assertEquals(3, t.evaluate(0.3)[0], 1e-6);
        assertEquals(20, t.evaluate(2)[0], 1e-6);
    }

    @Test
    void autoClearsHandles() {
        Track t = ramp();
        Keyframe k = t.keys.getFirst();
        Handles.apply(t, k, Handles.Preset.EASE);
        Handles.apply(t, k, Handles.Preset.AUTO);
        assertEquals(Handles.Preset.AUTO, Handles.of(t, k));
    }
}
