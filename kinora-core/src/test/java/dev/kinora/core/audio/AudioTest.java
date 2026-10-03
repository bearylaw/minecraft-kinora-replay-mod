package dev.kinora.core.audio;

import dev.kinora.core.project.Project;
import dev.kinora.core.project.Shot;
import dev.kinora.core.project.Tracks;
import dev.kinora.core.render.Ffmpeg;
import dev.kinora.core.render.RenderSettings;
import dev.kinora.core.timeline.Interpolation;
import dev.kinora.core.timeline.Keyframe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioTest {
    private static RenderSettings fps(int fps) {
        RenderSettings s = new RenderSettings();
        s.fpsNumerator = fps;
        s.fpsDenominator = 1;
        return s;
    }

    private static SoundCue cue(double tick) {
        return new SoundCue(tick, "test:beep", 0, 0, 0, false, true, 16, 1, 1, "neutral");
    }

    @Test
    void normalSpeedPlacesSoundsAtTheirReplayTime() {
        Shot shot = Shot.create("a", 100, 2);
        var lanes = AudioTimeline.sample(Project.single(shot), shot, fps(30));
        var placed = AudioTimeline.place(lanes, 1 / 30.0, List.of(cue(90), cue(110), cue(150)), RenderSettings.PitchMode.FOLLOW);
        // Tick 90 is before the shot, 150 is after it.
        assertEquals(1, placed.size());
        assertEquals(0.5, placed.getFirst().outputSeconds(), 1e-9);
        assertEquals(1.0, placed.getFirst().speed(), 1e-9);
    }

    @Test
    void slowMotionStretchesAndFreezeIsSilent() {
        Shot slow = Shot.create("slow", 0, 4);
        slow.track(Tracks.TIME).keys.clear();
        slow.track(Tracks.TIME).put(new Keyframe(0, 0).withInterpolation(Interpolation.LINEAR));
        slow.track(Tracks.TIME).put(new Keyframe(4, 40).withInterpolation(Interpolation.LINEAR));
        var placed = AudioTimeline.place(AudioTimeline.sample(Project.single(slow), slow, fps(20)), 0.05, List.of(cue(20)),
                RenderSettings.PitchMode.FOLLOW);
        assertEquals(2.0, placed.getFirst().outputSeconds(), 1e-9);
        assertEquals(0.5, placed.getFirst().speed(), 1e-9);

        Shot frozen = Shot.create("frozen", 0, 2);
        frozen.track(Tracks.TIME).keys.clear();
        frozen.track(Tracks.TIME).put(new Keyframe(0, 50).withInterpolation(Interpolation.HOLD));
        frozen.track(Tracks.TIME).put(new Keyframe(2, 50));
        assertTrue(AudioTimeline.place(AudioTimeline.sample(Project.single(frozen), frozen, fps(20)), 0.05, List.of(cue(50)),
                RenderSettings.PitchMode.FOLLOW).isEmpty());
    }

    @Test
    void cutsDoNotPlayTheSkippedReplay() {
        Project p = new Project();
        p.add(Shot.create("a", 0, 1));
        p.add(Shot.create("b", 1000, 1));
        var placed = AudioTimeline.place(AudioTimeline.sample(p, null, fps(20)), 0.05, List.of(cue(10), cue(500), cue(1010)),
                RenderSettings.PitchMode.FOLLOW);
        assertEquals(2, placed.size());
        assertEquals(0.5, placed.get(0).outputSeconds(), 1e-9);
        assertEquals(1.5, placed.get(1).outputSeconds(), 1e-9);
    }

    @Test
    void distanceAndDirection() {
        double[] facingSouth = {0, 0, 0, 0};
        // 8 blocks away with a 16-block range: half volume. Due west of a listener facing south is its right.
        float[] g = AudioMixer.gains(new SoundCue(0, "s", -8, 0, 0, false, true, 16, 1, 1, "x"), 1, facingSouth, 1);
        assertTrue(g[1] > g[0], "louder on the right");
        assertEquals(0.5, Math.max(g[0], g[1]), 1e-6);
        float[] out = AudioMixer.gains(new SoundCue(0, "s", 0, 0, 20, false, true, 16, 1, 1, "x"), 1, facingSouth, 1);
        assertArrayEquals(new float[] {0, 0}, out);
        float[] stereo = AudioMixer.gains(new SoundCue(0, "s", 0, 0, 100, false, true, 16, 1, 1, "x"), 2, facingSouth, 0.7);
        assertArrayEquals(new float[] {0.7f, 0.7f}, stereo);
    }

    @Test
    void mixPlacesAndPitchesClips() {
        float[] beep = new float[480];
        java.util.Arrays.fill(beep, 0.5f);
        AudioClip clip = new AudioClip(beep, 1, 48_000);
        var placement = new AudioTimeline.Placement(new SoundCue(0, "b", 0, 0, 0, true, false, 16, 1, 1, "x"), 0.5, 2.0, 1);
        float[] mix = AudioMixer.mix(List.of(placement), s -> clip, t -> new double[] {0, 0, 0, 0}, 1.0, RenderSettings.PitchMode.FOLLOW, 1);
        assertEquals(96_000, mix.length);
        // Double speed: 480 frames play in 240.
        assertEquals(0.5f, mix[24_000 * 2], 1e-6);
        assertEquals(0.5f, mix[(24_000 + 238) * 2], 1e-6);
        assertEquals(0f, mix[(24_000 + 250) * 2], 1e-6);
    }

    @Test
    void logSurvivesARestart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.audio.jsonl");
        try (AudioLog log = AudioLog.open(file)) {
            log.cue(cue(10));
            log.frame(0, 1, 2, 3, 90);
        }
        // A line cut short by a crash.
        Files.writeString(file, "{\"cue\": {\"replayTi", java.nio.file.StandardOpenOption.APPEND);
        try (AudioLog log = AudioLog.open(file)) {
            log.cue(cue(10));
            assertEquals(1, log.cues().size());
            assertArrayEquals(new double[] {1, 2, 3, 90}, log.listener(0.05).at(0), 1e-9);
        }
    }

    @Test
    void wavHeaderIsValid(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("a.wav");
        WavWriter.writeFloat(f, new float[] {0, 0.5f, -0.5f, 1}, 2, 48_000);
        byte[] b = Files.readAllBytes(f);
        assertEquals(58 + 16, b.length);
        assertEquals("RIFF", new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("data", new String(b, 50, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void muxKeepsThePictureAndPicksACodec() {
        RenderSettings s = fps(60);
        String cmd = String.join(" ", Ffmpeg.muxAudioCommand(Path.of("ffmpeg"), Path.of("v.mp4"), Path.of("a.wav"), null, s, Path.of("o.mp4")));
        assertTrue(cmd.contains("-c:v copy -c:a aac"), cmd);
        s.container = "mov";
        s.videoCodec = "prores_ks";
        String withMusic = String.join(" ", Ffmpeg.muxAudioCommand(Path.of("ffmpeg"), Path.of("v.mov"), Path.of("a.wav"), Path.of("m.mp3"), s,
                Path.of("o.mov")));
        assertTrue(withMusic.contains("amix=inputs=2") && withMusic.contains("pcm_s24le"), withMusic);
    }
}
