package dev.kinora.core.project;

import dev.kinora.core.camera.CameraState;
import dev.kinora.core.camera.PathTools;
import dev.kinora.core.camera.SceneQuery;
import dev.kinora.core.camera.Vec3d;
import dev.kinora.core.timeline.Interpolation;
import dev.kinora.core.timeline.Keyframe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTest {
    @TempDir
    Path dir;

    /** An entity walking +X at 0.2 blocks per tick from (0, 64, 0). */
    private static final SceneQuery WALKER = new SceneQuery() {
        @Override
        public Vec3d entityPosition(int entityId, double replayTicks) {
            return entityId == 7 ? new Vec3d(replayTicks * 0.2, 64, 0) : null;
        }

        @Override
        public double entityYaw(int entityId, double replayTicks) {
            return -90;
        }
    };

    @Test
    void timeRemapMapsShotTimeToReplayTime() {
        Shot shot = Shot.create("s", 200, 4);
        assertEquals(200, shot.replayTicks(0), 1e-9);
        assertEquals(260, shot.replayTicks(3), 1e-9);
        // A freeze: the same replay time for two seconds.
        shot.track(Tracks.TIME).keys.clear();
        shot.track(Tracks.TIME).put(new Keyframe(0, 200).withInterpolation(Interpolation.HOLD));
        shot.track(Tracks.TIME).put(new Keyframe(2, 200).withInterpolation(Interpolation.LINEAR));
        shot.track(Tracks.TIME).put(new Keyframe(4, 160));
        assertEquals(200, shot.replayTicks(1.5), 1e-9);
        assertEquals(180, shot.replayTicks(3), 1e-9);
        double[] range = shot.replayRange();
        assertEquals(160, range[0], 1e-9);
        assertEquals(200, range[1], 1e-9);
    }

    @Test
    void pathShotWithoutKeysDoesNotControlTheCamera() {
        Shot shot = Shot.create("s", 0, 2);
        assertNull(ShotEvaluator.evaluate(shot, 1, SceneQuery.EMPTY).camera());
    }

    @Test
    void lookAtAimsTheCameraAtItsTarget() {
        Shot shot = Shot.create("s", 0, 2);
        shot.track(Tracks.POSITION).put(new Keyframe(0, 0, 70, -10));
        shot.lookAt.enabled = true;
        shot.lookAt.point = new Vec3d(0, 70, 10);
        CameraState c = ShotEvaluator.evaluate(shot, 1, SceneQuery.EMPTY).camera();
        assertEquals(0, c.yaw(), 1e-9);
        assertEquals(0, c.pitch(), 1e-9);
    }

    @Test
    void followRigStaysBehindItsTarget() {
        Shot shot = Shot.create("s", 100, 2);
        shot.rig.mode = Shot.RigMode.FOLLOW;
        shot.rig.targetEntity = 7;
        shot.rig.damping = 0;
        shot.rig.offset = new Vec3d(0, 2, -5);
        CameraState c = ShotEvaluator.evaluate(shot, 0, WALKER).camera();
        // Facing +X (yaw -90), "behind" is -X.
        assertEquals(20 - 5, c.x(), 1e-6);
        assertEquals(66, c.y(), 1e-6);
        assertEquals(0, c.z(), 1e-6);
    }

    @Test
    void orbitKeepsItsRadius() {
        Shot shot = Shot.create("s", 0, 4);
        shot.rig.mode = Shot.RigMode.ORBIT;
        shot.rig.targetEntity = 7;
        shot.rig.damping = 0;
        for (double t = 0; t <= 4; t += 0.25) {
            CameraState c = ShotEvaluator.evaluate(shot, t, WALKER).camera();
            Vec3d center = WALKER.entityPosition(7, shot.replayTicks(t)).add(0, 1, 0);
            double r = Math.hypot(c.x() - center.x(), c.z() - center.z());
            assertEquals(Tracks.defaultValue(Tracks.ORBIT_RADIUS), r, 1e-6);
        }
    }

    @Test
    void shakeIsDeterministicAndOffWhenSteady() {
        Shot shot = Shot.create("s", 0, 2);
        shot.track(Tracks.POSITION).put(new Keyframe(0, 0, 70, 0));
        shot.track(Tracks.ROTATION).put(new Keyframe(0, 0, 0));
        CameraState steady = ShotEvaluator.evaluate(shot, 1.3, SceneQuery.EMPTY).camera();
        assertEquals(0, steady.x(), 0);
        shot.shake.preset = Shot.ShakePreset.HANDHELD;
        CameraState a = ShotEvaluator.evaluate(shot, 1.3, SceneQuery.EMPTY).camera();
        CameraState b = ShotEvaluator.evaluate(shot, 1.3, SceneQuery.EMPTY).camera();
        assertEquals(a, b);
        assertTrue(Math.abs(a.x()) > 0 && Math.abs(a.x()) < 0.1);
    }

    @ParameterizedTest
    @EnumSource(ShotTemplates.Template.class)
    void everyTemplateProducesACamera(ShotTemplates.Template template) {
        CameraState camera = new CameraState(0, 70, -10, 0, 10, 0, 70);
        int entity = template == ShotTemplates.Template.ORBIT ? 7 : Integer.MIN_VALUE;
        Shot shot = ShotTemplates.create(template, camera, new Vec3d(0, 66, 10), entity, 100, 4);
        for (double t = 0; t <= 4; t += 0.5) {
            assertNotNull(ShotEvaluator.evaluate(shot, t, WALKER).camera(), template + " at " + t);
        }
    }

    @Test
    void sequencePlacesShotsWithTransitions() {
        Project project = new Project();
        Shot a = project.add(Shot.create("a", 0, 4));
        Shot b = project.add(Shot.create("b", 100, 3));
        Shot c = project.add(Shot.create("c", 200, 2));
        project.sequence.get(1).transition = Project.Transition.DISSOLVE;
        project.sequence.get(1).transitionDuration = 1;
        project.sequence.get(2).transition = Project.Transition.DIP_TO_BLACK;
        project.sequence.get(2).transitionDuration = 1;
        SequenceTimeline timeline = new SequenceTimeline(project);
        assertEquals(4 + 3 - 1 + 2, timeline.duration(), 1e-9);
        assertEquals(project.sequenceDuration(), timeline.duration(), 1e-9);
        SequenceTimeline.Sample mid = timeline.sample(3.5);
        assertEquals(a, mid.primary());
        assertEquals(b, mid.secondary());
        assertEquals(0.5, mid.mix(), 1e-9);
        SequenceTimeline.Sample dip = timeline.sample(5.95);
        assertEquals(b, dip.primary());
        assertEquals(0.9, dip.fade(), 1e-9);
        SequenceTimeline.Sample fadeIn = timeline.sample(6.25);
        assertEquals(c, fadeIn.primary());
        assertEquals(0.5, fadeIn.fade(), 1e-9);
        SequenceTimeline.Sample end = timeline.sample(8);
        assertEquals(c, end.primary());
        assertEquals(2, end.primaryTime(), 1e-9);
    }

    @Test
    void projectRoundTripsThroughJson() throws Exception {
        Project project = new Project();
        project.name = "Test";
        Shot shot = project.add(ShotTemplates.create(ShotTemplates.Template.DOLLY_ZOOM, new CameraState(0, 70, 0, 0, 0, 0, 70),
                new Vec3d(0, 70, 20), Integer.MIN_VALUE, 0, 3));
        shot.shake.preset = Shot.ShakePreset.JOG;
        Path path = dir.resolve("p." + ProjectIO.EXTENSION);
        ProjectIO.save(project, path);
        Project loaded = ProjectIO.load(path);
        assertEquals("Test", loaded.name);
        Shot back = loaded.shots.getFirst();
        assertEquals(Shot.ShakePreset.JOG, back.shake.preset);
        for (double t = 0; t <= 3; t += 0.37) {
            assertEquals(ShotEvaluator.evaluate(shot, t, SceneQuery.EMPTY), ShotEvaluator.evaluate(back, t, SceneQuery.EMPTY));
        }
    }

    @Test
    void simplifyKeepsAPathWithinTolerance() {
        List<PathTools.Sample> samples = new ArrayList<>();
        for (int i = 0; i <= 400; i++) {
            double t = i / 20.0;
            samples.add(new PathTools.Sample(t, Math.sin(t) * 10, 64 + t, Math.cos(t * 0.5) * 5, t * 20, 5 * Math.sin(t), 0, 70));
        }
        List<Integer> kept = PathTools.simplify(samples, 0.1, 1.0);
        assertTrue(kept.size() < samples.size() / 4, "kept " + kept.size());
        assertEquals(0, kept.getFirst());
        assertEquals(samples.size() - 1, kept.getLast());
        // Linear interpolation between kept samples stays close to every original sample.
        for (int k = 0; k + 1 < kept.size(); k++) {
            PathTools.Sample a = samples.get(kept.get(k));
            PathTools.Sample b = samples.get(kept.get(k + 1));
            for (int i = kept.get(k); i <= kept.get(k + 1); i++) {
                PathTools.Sample s = samples.get(i);
                double f = (s.time() - a.time()) / (b.time() - a.time());
                double dx = a.x() + (b.x() - a.x()) * f - s.x();
                assertTrue(Math.abs(dx) <= 0.1 + 1e-9);
            }
        }
    }

    @Test
    void stabilizeRemovesJitterButKeepsTheCourse() {
        List<PathTools.Sample> samples = new ArrayList<>();
        java.util.Random random = new java.util.Random(3);
        for (int i = 0; i <= 200; i++) {
            double t = i / 20.0;
            samples.add(new PathTools.Sample(t, t * 2 + (random.nextDouble() - 0.5) * 0.4, 64, 0, 179 + (random.nextDouble() - 0.5) * 6, 0, 0, 70));
        }
        List<PathTools.Sample> smooth = PathTools.stabilize(samples, 0.25);
        double jitterBefore = 0;
        double jitterAfter = 0;
        for (int i = 1; i < samples.size(); i++) {
            jitterBefore += Math.abs((samples.get(i).x() - samples.get(i - 1).x()) - 0.1);
            jitterAfter += Math.abs((smooth.get(i).x() - smooth.get(i - 1).x()) - 0.1);
        }
        assertTrue(jitterAfter < jitterBefore / 5, jitterBefore + " -> " + jitterAfter);
        assertEquals(10, smooth.get(100).x(), 0.2);
        // Yaw crossing the ±180 seam is smoothed around 180, not towards 0.
        assertTrue(Math.abs(Math.abs(smooth.get(100).yaw()) - 180) < 3 || Math.abs(smooth.get(100).yaw() - 179) < 3);
    }

    @Test
    void handWrittenProjectsNeedOnlyKeys() {
        String json = """
                {
                  "name": "Hand written",
                  "shots": [{
                    "name": "Dolly",
                    "duration": 4,
                    "tracks": {
                      "time": {"keys": [{"time": 0, "value": [100]}, {"time": 4, "value": [180]}]},
                      "camera.position": {"keys": [{"time": 4, "value": [10, 70, 0]}, {"time": 0, "value": [0, 70, 0]}]},
                      "camera.rotation": {"keys": [{"time": 0, "value": [90, 10]}]}
                    }
                  }],
                  "sequence": []
                }
                """;
        Project p = ProjectIO.fromJson(json);
        Shot shot = p.shots.getFirst();
        assertEquals(dev.kinora.core.timeline.Track.Kind.PATH, shot.track(Tracks.POSITION).kind);
        assertEquals(3, shot.track(Tracks.POSITION).dimension);
        assertEquals(140, shot.replayTicks(2), 1e-9);
        // Keys out of order are sorted; the path runs from x 0 to x 10.
        assertEquals(5, shot.track(Tracks.POSITION).evaluate(2)[0], 1.0);
        org.junit.jupiter.api.Assertions.assertNotNull(shot.id);
        assertEquals(dev.kinora.core.timeline.Interpolation.CENTRIPETAL, shot.track(Tracks.POSITION).keys.getFirst().interpolation);
        assertEquals(dev.kinora.core.timeline.Interpolation.CENTRIPETAL, shot.track(Tracks.TIME).keys.getFirst().interpolation);
    }
}
