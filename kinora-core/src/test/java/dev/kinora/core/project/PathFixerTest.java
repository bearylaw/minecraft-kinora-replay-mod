package dev.kinora.core.project;

import dev.kinora.core.camera.SceneQuery;
import dev.kinora.core.camera.Vec3d;
import dev.kinora.core.timeline.Keyframe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathFixerTest {
    /** A wall 2 blocks thick (x 4..6) and 3 high, with the camera's 0.15 margin. */
    private static final SceneQuery WALL = new SceneQuery() {
        @Override
        public Vec3d entityPosition(int entityId, double replayTicks) {
            return null;
        }

        @Override
        public boolean blocked(Vec3d p) {
            return p.x() > 4 - 0.15 && p.x() < 6 + 0.15 && p.y() < 3 + 0.15;
        }
    };

    private static Shot straightThroughTheWall() {
        Shot s = Shot.create("Through", 0, 5);
        s.track(Tracks.POSITION).put(new Keyframe(0, 0, 1, 0));
        s.track(Tracks.POSITION).put(new Keyframe(5, 10, 1, 0));
        return s;
    }

    @Test
    void findsTheStretchInsideTheWall() {
        var stretches = PathFixer.blocked(straightThroughTheWall(), WALL);
        assertEquals(1, stretches.size());
        assertEquals(1.93, stretches.getFirst()[0], 0.03);
        assertEquals(3.07, stretches.getFirst()[1], 0.03);
    }

    @Test
    void fixRoutesTheCameraOverTheWall() {
        Shot s = straightThroughTheWall();
        int added = PathFixer.fix(s, WALL);
        assertTrue(added >= 1, "added " + added);
        assertTrue(PathFixer.blocked(s, WALL).isEmpty(), "still blocked");
        // The ends stay where they were.
        assertEquals(0, s.track(Tracks.POSITION).evaluate(0)[0], 1e-9);
        assertEquals(10, s.track(Tracks.POSITION).evaluate(5)[0], 1e-9);
    }

    @Test
    void aClearPathIsLeftAlone() {
        Shot s = Shot.create("Clear", 0, 5);
        s.track(Tracks.POSITION).put(new Keyframe(0, 0, 5, 0));
        s.track(Tracks.POSITION).put(new Keyframe(5, 10, 5, 0));
        assertEquals(0, PathFixer.fix(s, WALL));
        assertEquals(2, s.track(Tracks.POSITION).keys.size());
    }

    /** A floor whose top is at y 0, with the camera margin. */
    private static final SceneQuery FLOOR = new SceneQuery() {
        @Override
        public Vec3d entityPosition(int entityId, double replayTicks) {
            return null;
        }

        @Override
        public boolean blocked(Vec3d p) {
            return p.y() < 0.15;
        }
    };

    @Test
    void aKeyDippingIntoTheFloorIsLiftedAndTheCurveWithIt() {
        Shot s = Shot.create("Low", 0, 5);
        s.track(Tracks.POSITION).put(new Keyframe(0, 20, 0.5, 70));
        s.track(Tracks.POSITION).put(new Keyframe(2.5, 30, -0.6, 70));
        s.track(Tracks.POSITION).put(new Keyframe(5, 40, 0.5, 70));
        assertTrue(!PathFixer.blocked(s, FLOOR).isEmpty());
        PathFixer.fix(s, FLOOR);
        assertTrue(PathFixer.blocked(s, FLOOR).isEmpty(), "still blocked: " + PathFixer.blocked(s, FLOOR).size());
    }
}
