package dev.kinora.core.project;

import dev.kinora.core.timeline.Keyframe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveCutTest {
    @Test
    void stretchesBecomeShotsAtNormalSpeed() {
        Shot wide = Shot.create("Wide", 0, 20);
        Shot close = Shot.create("Close", 100, 10);
        // The close-up dollies in over its 10 seconds.
        close.track(Tracks.FOV).put(new Keyframe(0, 70));
        close.track(Tracks.FOV).put(new Keyframe(10, 30));
        List<Shot> shots = LiveCut.build(List.of(wide, close), List.of(new LiveCut.Cut(40, 0), new LiveCut.Cut(140, 1), new LiveCut.Cut(180, 0)), 220);
        assertEquals(3, shots.size());
        assertEquals(5.0, shots.get(0).duration, 1e-9);
        assertEquals(40, shots.get(0).replayTicks(0), 1e-9);
        assertEquals(140, shots.get(0).replayTicks(5), 1e-9);
        // Cut to the close-up at tick 140: its own time 2 s (it starts at tick 100), so its dolly is at 62°.
        Shot c = shots.get(1);
        assertEquals(2.0, c.duration, 1e-9);
        assertEquals(140, c.replayTicks(0), 1e-9);
        assertEquals(62, c.value(Tracks.FOV, 0), 1e-6);
        assertEquals(54, c.value(Tracks.FOV, 2), 1e-6);
        assertEquals(180, shots.get(2).replayTicks(0), 1e-9);
        assertEquals(2.0, shots.get(2).duration, 1e-9);
    }

    @Test
    void cutsUnderATickAreDropped() {
        Shot a = Shot.create("A", 0, 20);
        List<Shot> shots = LiveCut.build(List.of(a, a), List.of(new LiveCut.Cut(10, 0), new LiveCut.Cut(10.5, 1), new LiveCut.Cut(60, 0)), 80);
        assertEquals(2, shots.size());
    }

    @Test
    void cameraTimeFindsTheMatchingMomentOrTheNearestEnd() {
        Shot s = Shot.create("S", 100, 10);
        assertEquals(2.5, LiveCut.cameraTime(s, 150), 1e-6);
        assertEquals(0, LiveCut.cameraTime(s, 20), 1e-6);
        assertEquals(10, LiveCut.cameraTime(s, 900), 1e-6);
    }
}
