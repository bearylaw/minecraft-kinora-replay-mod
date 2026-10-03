package dev.kinora.core.render;

import dev.kinora.core.project.Project;
import dev.kinora.core.project.Shot;
import dev.kinora.core.project.Tracks;
import dev.kinora.core.timeline.Interpolation;
import dev.kinora.core.timeline.Keyframe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderPlanTest {
    private static RenderSettings fps(int num, int den) {
        RenderSettings s = new RenderSettings();
        s.fpsNumerator = num;
        s.fpsDenominator = den;
        return s;
    }

    @Test
    void frameCountsAreExact() {
        assertEquals(300, RenderPlan.frameCount(5, fps(60, 1)));
        assertEquals(120, RenderPlan.frameCount(5, fps(24, 1)));
        // 23.976: 5 s is 119.88 frames, so 120 are needed to cover it.
        assertEquals(120, RenderPlan.frameCount(5, fps(24000, 1001)));
        // Exactly 1001 frames at 24000/1001 is 41.7083... s; no extra frame from rounding error.
        assertEquals(1001, RenderPlan.frameCount(1001 * 1001 / 24000.0, fps(24000, 1001)));
        assertEquals(1, RenderPlan.frameCount(0, fps(60, 1)));
    }

    @Test
    void frameTimesNeverDrift() {
        RenderSettings s = fps(24000, 1001);
        // Frame 24000 is exactly 1001 s: computed as one product, not a running sum.
        assertEquals(1001.0, RenderPlan.frameTime(24000, s), 0);
        assertEquals(1001.0 / 24000 * 7, RenderPlan.frameTime(7, s), 1e-15);
    }

    @Test
    void normalShotRendersInOutputOrder() {
        Shot shot = Shot.create("a", 100, 2);
        RenderPlan plan = RenderPlan.shot(shot, fps(30, 1));
        assertEquals(60, plan.frameCount());
        assertTrue(plan.monotonic());
        for (int i = 0; i < 60; i++) {
            RenderPlan.Unit u = plan.renderOrder().get(i);
            assertEquals(i, u.frame());
            // 30 fps is 2/3 tick per frame.
            assertEquals(100 + i * 20.0 / 30, u.replayTicks(), 1e-9);
        }
    }

    @Test
    void reverseRemapRendersInReplayOrder() {
        Shot shot = new Shot();
        shot.duration = 1;
        var time = shot.track(Tracks.TIME);
        time.put(new Keyframe(0, 200).withInterpolation(Interpolation.LINEAR));
        time.put(new Keyframe(1, 180).withInterpolation(Interpolation.LINEAR));
        RenderPlan plan = RenderPlan.shot(shot, fps(10, 1));
        assertFalse(plan.monotonic());
        List<RenderPlan.Unit> order = plan.renderOrder();
        assertEquals(9, order.getFirst().frame());
        assertEquals(0, order.getLast().frame());
        for (int i = 1; i < order.size(); i++) {
            assertTrue(order.get(i).replayTicks() >= order.get(i - 1).replayTicks());
        }
    }

    @Test
    void motionBlurSamplesSpanTheShutter() {
        RenderSettings s = fps(20, 1);
        s.motionBlurSamples = 4;
        s.shutterAngle = 180;
        Shot shot = Shot.create("a", 0, 1);
        RenderPlan plan = RenderPlan.shot(shot, s);
        assertEquals(80, plan.renderOrder().size());
        // Frame 10 is at 0.5 s; the shutter is open 1/40 s around it.
        List<Double> times = new ArrayList<>();
        for (RenderPlan.Unit u : plan.renderOrder()) {
            if (u.frame() == 10) {
                times.add(u.shotTime());
                assertEquals(0.25, u.weight(), 1e-12);
            }
        }
        assertEquals(4, times.size());
        assertEquals(0.5 - 0.0125 + 0.025 * 0.125, times.get(0), 1e-12);
        assertEquals(0.5 - 0.0125 + 0.025 * 0.875, times.get(3), 1e-12);
    }

    @Test
    void dissolveHasTwoLayersAndFadesToBlack() {
        Project p = new Project();
        Shot a = p.add(Shot.create("a", 0, 2));
        Shot b = p.add(Shot.create("b", 1000, 2));
        p.sequence.get(1).transition = Project.Transition.DISSOLVE;
        p.sequence.get(1).transitionDuration = 1;
        RenderPlan plan = RenderPlan.sequence(p, fps(10, 1));
        // 2 + 2 - 1 s of overlap = 3 s.
        assertEquals(30, plan.frameCount());
        assertEquals(1, plan.recipe(5).units());
        assertEquals(2, plan.recipe(15).units());
        // The incoming shot's half of each dissolve frame is rendered later, with the rest of shot b.
        assertFalse(plan.monotonic());

        p.sequence.get(1).transition = Project.Transition.DIP_TO_BLACK;
        RenderPlan dip = RenderPlan.sequence(p, fps(10, 1));
        assertEquals(40, dip.frameCount());
        assertEquals(1.0, dip.recipe(20).fade(), 1e-9);
        assertEquals(0.0, dip.recipe(5).fade(), 1e-9);
        assertNotNull(a);
        assertNotNull(b);
    }

    @Test
    void assemblerRestoresOutputOrderAndMixes(@TempDir Path dir) throws IOException {
        Shot shot = new Shot();
        shot.duration = 1;
        var time = shot.track(Tracks.TIME);
        time.put(new Keyframe(0, 200).withInterpolation(Interpolation.LINEAR));
        time.put(new Keyframe(1, 180).withInterpolation(Interpolation.LINEAR));
        RenderSettings s = fps(10, 1);
        s.motionBlurSamples = 2;
        RenderPlan plan = RenderPlan.shot(shot, s);
        Map<Integer, byte[]> out = new TreeMap<>();
        List<Integer> order = new ArrayList<>();
        // A tiny memory budget forces spilling to disk.
        try (FrameStore store = new FrameStore(dir.resolve("spill"), 16)) {
            FrameAssembler assembler = new FrameAssembler(plan, 0, store, (i, rgba) -> {
                order.add(i);
                out.put(i, rgba);
            });
            for (RenderPlan.Unit u : plan.renderOrder()) {
                byte v = (byte) (u.frame() * 10 + u.subFrame() * 4);
                assembler.accept(u, new byte[] {v, v, v, (byte) 255});
            }
            assertTrue(assembler.done());
            assertEquals(0, store.size());
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), order);
        // Mean of f*10 and f*10+4.
        assertArrayEquals(new byte[] {32, 32, 32, (byte) 255}, out.get(3));
        assertFalse(Files.exists(dir.resolve("spill")));
    }

    @Test
    void fadeDarkensButKeepsAlpha() {
        RenderPlan.Unit u = new RenderPlan.Unit(0, 0, 0, 0, null, 0, 0, 0, 1);
        byte[] mixed = FrameAssembler.mix(List.of(u), List.of(new byte[] {(byte) 200, 100, 50, (byte) 255}), 0.5);
        assertArrayEquals(new byte[] {100, 50, 25, (byte) 255}, mixed);
    }

    @Test
    void ffmpegCommandsAreWellFormed() {
        RenderSettings s = fps(24000, 1001);
        s.videoCodec = "libx264";
        s.quality = 20;
        List<String> cmd = Ffmpeg.encodeCommand(Path.of("ffmpeg"), s, 1920, 1080, Path.of("out.mkv"));
        String line = String.join(" ", cmd);
        assertTrue(line.contains("-f rawvideo -pix_fmt rgba -s 1920x1080 -framerate 24000/1001 -i -"), line);
        assertTrue(line.contains("-c:v libx264 -crf 20"), line);
        assertTrue(line.endsWith("out.mkv"), line);
        String list = Ffmpeg.concatList(List.of(Path.of("it's.mkv")));
        assertTrue(list.startsWith("file '") && list.endsWith("it'\\''s.mkv'\n") && !list.contains("\\\\"), list);
        assertTrue(String.join(" ", Ffmpeg.concatCommand(Path.of("ffmpeg"), Path.of("l.txt"), Path.of("o.mp4"), s))
                .contains("-video_track_timescale 24000 -movflags +faststart"));
    }

    @Test
    void gradeIsNeutralByDefaultAndDoesWhatItSays() {
        byte[] grey = new byte[4 * 4 * 4];
        java.util.Arrays.fill(grey, (byte) 128);
        byte[] copy = grey.clone();
        Grade.NONE.apply(copy, 4, 4, 1);
        assertArrayEquals(grey, copy);

        byte[] brighter = grey.clone();
        new Grade(1, 1, 1, 0, 0, 0, 0, 0, 0, 0).apply(brighter, 4, 4, 1);
        assertTrue((brighter[0] & 0xFF) > 160, "one stop up is clearly brighter");

        byte[] bw = new byte[] {(byte) 200, 40, 40, (byte) 255};
        new Grade(0, 1, 0, 0, 0, 0, 0, 0, 0, 0).apply(bw, 1, 1, 1);
        assertEquals(bw[0], bw[1]);
        assertEquals(bw[1], bw[2]);

        byte[] boxed = grey.clone();
        // A 4x4 frame letterboxed to 4:1 keeps one row... rounded: bars of 1-2 rows top and bottom.
        new Grade(0, 1, 1, 0, 0, 0, 0, 0, 0, 4).apply(boxed, 4, 4, 1);
        assertEquals(0, boxed[0]);
        assertEquals(0, boxed[boxed.length - 4]);

        byte[] a = grey.clone();
        byte[] b = grey.clone();
        Grade grain = new Grade(0, 1, 1, 0, 0, 0, 1, 0, 0, 0);
        grain.apply(a, 4, 4, 7);
        grain.apply(b, 4, 4, 7);
        assertArrayEquals(a, b, "grain repeats exactly for the same frame");
    }

    @Test
    void encoderListIsParsed() {
        String out = """
                Encoders:
                 V..... = Video
                 ------
                 V....D libx264              libx264 H.264
                 VFS... prores_ks            Apple ProRes
                 A....D aac                  AAC
                """;
        assertEquals(java.util.Set.of("libx264", "prores_ks"), Ffmpeg.parseEncoders(out));
    }

    @Test
    void imageSequenceResumesAfterLastCompleteFrame(@TempDir Path dir) throws IOException {
        ImageSequenceSink sink = new ImageSequenceSink(dir, "shot", false, 1);
        sink.begin(2, 1, 0);
        sink.write(0, new byte[8]);
        sink.write(1, new byte[8]);
        sink.write(3, new byte[8]);
        sink.finish();
        assertEquals(2, sink.completeFrames(10));
        assertTrue(Files.size(sink.file(0)) > 0);
    }

    @Test
    void jobsRoundTrip(@TempDir Path dir) throws IOException {
        Project p = new Project();
        p.name = "Film";
        Shot shot = p.add(Shot.create("Opening", 40, 3));
        RenderJob job = RenderJob.create(p, shot, fps(30, 1), dir.resolve("r.kinora"), dir.resolve("out.mp4"));
        assertEquals(90, job.frameCount);
        job.parts.add(new RenderJob.Part("a.mkv", 30));
        job.save(dir.resolve("j." + RenderJob.EXTENSION));
        RenderJob back = RenderJob.load(dir.resolve("j." + RenderJob.EXTENSION));
        assertEquals("Film - Opening", back.title);
        assertEquals(30, back.parts.getFirst().frames);
        assertEquals(90, back.plan().frameCount());
    }
}
