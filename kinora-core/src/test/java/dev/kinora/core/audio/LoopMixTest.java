package dev.kinora.core.audio;

import dev.kinora.core.render.RenderSettings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopMixTest {
    /** A 1 kHz tone, 0.1 s long, at 48 kHz. */
    private static final AudioClip TONE;

    static {
        float[] s = new float[4800];
        for (int i = 0; i < s.length; i++) {
            s[i] = (float) Math.sin(2 * Math.PI * 1000 * i / 48000.0) * 0.5f;
        }
        TONE = new AudioClip(s, 1, 48000);
    }

    private static List<List<AudioTimeline.Lane>> lanes(int frames, double ticksPerFrame) {
        List<List<AudioTimeline.Lane>> out = new ArrayList<>();
        for (int f = 0; f <= frames; f++) {
            out.add(List.of(new AudioTimeline.Lane("shot", f * ticksPerFrame, 1)));
        }
        return out;
    }

    private static List<LoopSample> samples(int fromTick, int toTick, boolean looping) {
        List<LoopSample> out = new ArrayList<>();
        for (int t = fromTick; t < toTick; t++) {
            out.add(new LoopSample("bee#1", t, "tone", 2, 0, 0, false, true, 16, 1, 1, "neutral", looping));
        }
        return out;
    }

    private static double energy(float[] mix, double fromSeconds, double toSeconds) {
        double e = 0;
        for (int i = (int) (fromSeconds * 48000) * 2; i < (int) (toSeconds * 48000) * 2; i++) {
            e += mix[i] * mix[i];
        }
        return e;
    }

    @Test
    void loopPlaysWhileItHasSamplesAndRepeats() {
        // 20 fps, one tick per frame: 1 second of video covers ticks 0..20; the sound lives at ticks 5..15.
        float[] mix = AudioMixer.mix(List.of(), lanes(20, 1), samples(5, 15, true), 0.05, s -> TONE, t -> new double[] {0, 0, 0, 0}, 1.0,
                RenderSettings.PitchMode.FOLLOW, 1);
        assertEquals(0, energy(mix, 0, 0.24), 1e-9);
        // 0.5 s of sound from a 0.1 s file: it must keep repeating.
        assertTrue(energy(mix, 0.6, 0.74) > 1);
        assertEquals(0, energy(mix, 0.76, 1.0), 1e-9);
    }

    @Test
    void oneShotStopsAtTheEndOfItsFile() {
        float[] mix = AudioMixer.mix(List.of(), lanes(20, 1), samples(0, 20, false), 0.05, s -> TONE, t -> new double[] {0, 0, 0, 0}, 1.0,
                RenderSettings.PitchMode.FOLLOW, 1);
        assertTrue(energy(mix, 0, 0.1) > 1);
        assertEquals(0, energy(mix, 0.11, 1.0), 1e-9);
    }

    @Test
    void frozenTimeIsSilent() {
        float[] mix = AudioMixer.mix(List.of(), lanes(20, 0), samples(0, 20, true), 0.05, s -> TONE, t -> new double[] {0, 0, 0, 0}, 1.0,
                RenderSettings.PitchMode.FOLLOW, 1);
        assertEquals(0, energy(mix, 0, 1.0), 1e-9);
    }
}
