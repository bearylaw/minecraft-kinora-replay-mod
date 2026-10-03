package dev.kinora.core.project;

import dev.kinora.core.timeline.Keyframe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShotLookTest {
    @Test
    void copiesGradingAndShakeStretchedToTheTarget() {
        Shot a = Shot.create("A", 0, 4);
        a.track(Tracks.EXPOSURE).put(new Keyframe(0, 0));
        a.track(Tracks.EXPOSURE).put(new Keyframe(4, 1));
        a.track(Tracks.VIGNETTE).put(new Keyframe(0, 0.3));
        a.track(Tracks.FOV).put(new Keyframe(0, 30));
        a.shake.preset = Shot.ShakePreset.values()[1];
        Shot b = Shot.create("B", 100, 8);
        b.track(Tracks.CONTRAST).put(new Keyframe(0, 2));
        b.track(Tracks.FOV).put(new Keyframe(0, 90));

        ShotLook.apply(a, b);
        assertEquals(0.5, b.value(Tracks.EXPOSURE, 4), 1e-9);
        assertEquals(0.3, b.value(Tracks.VIGNETTE, 0), 1e-9);
        assertNull(b.existing(Tracks.CONTRAST));
        assertEquals(90, b.value(Tracks.FOV, 0), 1e-9);
        assertEquals(a.shake.preset, b.shake.preset);
        // The source keeps its own keys.
        assertEquals(1, a.value(Tracks.EXPOSURE, 4), 1e-9);
    }
}
