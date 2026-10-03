package dev.kinora.core.audio;

import dev.kinora.core.render.RenderSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mixes placed sounds into a stereo track the way the game's sound engine plays them: mono sounds
 * fade linearly with distance from the camera and are panned by direction; stereo sounds (music,
 * some ambience) play unpanned at full level, as OpenAL does. Pitch is playback rate.
 */
public final class AudioMixer {
    public static final int SAMPLE_RATE = 48_000;
    /** Gains and panning are recomputed this often, so sounds follow a moving camera. */
    private static final int BLOCK = 256;

    /** Decodes a sound file; returns null if it cannot. Called once per distinct file. */
    public interface ClipSource {
        AudioClip clip(String sound);
    }

    /** The camera at an output time: position and yaw (degrees, Minecraft convention). */
    public interface Listener {
        double[] at(double outputSeconds);
    }

    private AudioMixer() {}

    /**
     * @return interleaved stereo floats at {@link #SAMPLE_RATE}, {@code durationSeconds} long
     */
    public static float[] mix(List<AudioTimeline.Placement> placements, ClipSource source, Listener listener, double durationSeconds,
                              RenderSettings.PitchMode mode, double gameVolume) {
        return mix(placements, List.of(), List.of(), 1, source, listener, durationSeconds, mode, gameVolume);
    }

    /**
     * As above, plus continuous sounds ({@link LoopSample}s), placed with the video's lanes.
     *
     * @param lanes        from {@link AudioTimeline#sample}
     * @param frameSeconds length of one output frame
     */
    public static float[] mix(List<AudioTimeline.Placement> placements, List<List<AudioTimeline.Lane>> lanes, List<LoopSample> loops,
                              double frameSeconds, ClipSource source, Listener listener, double durationSeconds, RenderSettings.PitchMode mode,
                              double gameVolume) {
        int length = (int) Math.round(durationSeconds * SAMPLE_RATE);
        float[] out = new float[length * 2];
        Map<String, AudioClip> clips = new HashMap<>();
        if (!loops.isEmpty()) {
            mixLoops(out, lanes, frameSeconds, loops, clips, source, listener, mode, gameVolume);
        }
        for (AudioTimeline.Placement p : placements) {
            SoundCue cue = p.cue();
            AudioClip clip = clips.computeIfAbsent(cue.sound(), source::clip);
            if (clip == null || clip.frames() == 0) {
                continue;
            }
            double pitch = Math.max(0.5, Math.min(2.0, cue.pitch()));
            if (mode == RenderSettings.PitchMode.FOLLOW) {
                pitch *= p.speed();
            }
            double step = clip.sampleRate() / (double) SAMPLE_RATE * pitch;
            int start = (int) Math.round(p.outputSeconds() * SAMPLE_RATE);
            int frames = (int) Math.ceil(clip.frames() / step);
            double level = cue.volume() * gameVolume * p.weight();
            float gl = 0;
            float gr = 0;
            for (int i = 0; i < frames; i++) {
                int o = start + i;
                if (o >= length) {
                    break;
                }
                if (i % BLOCK == 0) {
                    float[] g = gains(cue, clip.channels(), listener.at(o / (double) SAMPLE_RATE), level);
                    gl = g[0];
                    gr = g[1];
                }
                if (o < 0 || gl == 0 && gr == 0) {
                    continue;
                }
                double pos = i * step;
                out[o * 2] += clip.sample(pos, 0) * gl;
                out[o * 2 + 1] += clip.sample(pos, 1) * gr;
            }
        }
        limit(out);
        return out;
    }

    /**
     * Continuous sounds. For every output frame and shot, each sound with a sample at the frame's
     * replay tick plays through the frame; its level and panning move from the frame's start to its
     * end, and its playback position carries on into the next frame.
     */
    static void mixLoops(float[] out, List<List<AudioTimeline.Lane>> lanes, double frameSeconds, List<LoopSample> loops,
                         Map<String, AudioClip> clips, ClipSource source, Listener listener, RenderSettings.PitchMode mode, double gameVolume) {
        java.util.TreeMap<Long, Map<String, LoopSample>> byTick = new java.util.TreeMap<>();
        for (LoopSample s : loops) {
            byTick.computeIfAbsent(tick(s.replayTicks()), k -> new java.util.LinkedHashMap<>()).putIfAbsent(s.key(), s);
        }
        int length = out.length / 2;
        Map<String, Double> positions = new HashMap<>();
        for (int f = 0; f + 1 < lanes.size(); f++) {
            int o0 = (int) Math.round(f * frameSeconds * SAMPLE_RATE);
            int o1 = Math.min(length, (int) Math.round((f + 1) * frameSeconds * SAMPLE_RATE));
            if (o1 <= o0) {
                continue;
            }
            for (AudioTimeline.Lane a : lanes.get(f)) {
                AudioTimeline.Lane b = null;
                for (AudioTimeline.Lane candidate : lanes.get(f + 1)) {
                    if (candidate.shotId().equals(a.shotId())) {
                        b = candidate;
                    }
                }
                if (b == null || a.replayTicks() == b.replayTicks()) {
                    continue;
                }
                double speed = Math.abs(b.replayTicks() - a.replayTicks()) / frameSeconds / 20.0;
                Map<String, LoopSample> now = byTick.get(tick(a.replayTicks()));
                if (now == null || !AudioTimeline.audible(speed, mode)) {
                    continue;
                }
                double weight = (a.weight() + b.weight()) / 2;
                // The same sound heard twice (two entries for one minecart after a rebuilt world) plays once.
                java.util.Set<String> playing = new java.util.HashSet<>();
                for (LoopSample s : now.values()) {
                    String same = s.sound() + "|" + Math.round(s.x() * 10) + "|" + Math.round(s.y() * 10) + "|" + Math.round(s.z() * 10);
                    if (!playing.add(same)) {
                        continue;
                    }
                    AudioClip clip = clips.computeIfAbsent(s.sound(), source::clip);
                    if (clip == null || clip.frames() == 0) {
                        continue;
                    }
                    LoopSample start = at(byTick, s.key(), a.replayTicks(), s);
                    LoopSample end = at(byTick, s.key(), b.replayTicks(), start);
                    double pitch = Math.max(0.5, Math.min(2.0, start.pitch()));
                    if (mode == RenderSettings.PitchMode.FOLLOW) {
                        pitch *= speed;
                    }
                    double step = clip.sampleRate() / (double) SAMPLE_RATE * pitch;
                    String voice = a.shotId() + "|" + s.key();
                    double pos = positions.getOrDefault(voice, 0.0);
                    float[] g0 = gains(start.asCue(), clip.channels(), listener.at(o0 / (double) SAMPLE_RATE), start.volume() * gameVolume * weight);
                    float[] g1 = gains(end.asCue(), clip.channels(), listener.at(o1 / (double) SAMPLE_RATE), end.volume() * gameVolume * weight);
                    for (int o = o0; o < o1; o++) {
                        if (!s.looping() && pos >= clip.frames()) {
                            break;
                        }
                        float k = (o - o0) / (float) (o1 - o0);
                        float gl = g0[0] + (g1[0] - g0[0]) * k;
                        float gr = g0[1] + (g1[1] - g0[1]) * k;
                        out[o * 2] += (s.looping() ? clip.sampleLooped(pos, 0) : clip.sample(pos, 0)) * gl;
                        out[o * 2 + 1] += (s.looping() ? clip.sampleLooped(pos, 1) : clip.sample(pos, 1)) * gr;
                        pos += step;
                    }
                    positions.put(voice, s.looping() ? pos % clip.frames() : pos);
                }
            }
        }
    }

    private static long tick(double replayTicks) {
        return (long) Math.floor(replayTicks + 1e-6);
    }

    /** The sound {@code key} at a replay time, between its samples at the ticks around it; {@code fallback} if it has none. */
    private static LoopSample at(java.util.TreeMap<Long, Map<String, LoopSample>> byTick, String key, double replayTicks, LoopSample fallback) {
        long t = tick(replayTicks);
        Map<String, LoopSample> here = byTick.get(t);
        LoopSample a = here == null ? null : here.get(key);
        if (a == null) {
            return fallback;
        }
        Map<String, LoopSample> next = byTick.get(t + 1);
        LoopSample b = next == null ? null : next.get(key);
        if (b == null) {
            return a;
        }
        double f = replayTicks - t;
        return new LoopSample(key, replayTicks, a.sound(), a.x() + (b.x() - a.x()) * f, a.y() + (b.y() - a.y()) * f, a.z() + (b.z() - a.z()) * f,
                a.relative(), a.attenuate(), a.range(), a.volume() + (b.volume() - a.volume()) * f, a.pitch() + (b.pitch() - a.pitch()) * f,
                a.category(), a.looping());
    }

    /** Left and right gain for a cue heard by a listener {x, y, z, yaw}. */
    static float[] gains(SoundCue cue, int channels, double[] listener, double level) {
        if (channels > 1) {
            // OpenAL does not position stereo sources.
            return new float[] {(float) level, (float) level};
        }
        double dx = cue.x();
        double dy = cue.y();
        double dz = cue.z();
        if (!cue.relative()) {
            dx -= listener[0];
            dy -= listener[1];
            dz -= listener[2];
        }
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double gain = level;
        if (cue.attenuate() && !cue.relative()) {
            gain *= Math.max(0, 1 - distance / Math.max(1e-6, cue.range()));
        }
        if (gain <= 0) {
            return new float[] {0, 0};
        }
        double pan = 0;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 1e-6) {
            double yaw = Math.toRadians(listener[3]);
            // The listener's right side: facing south (yaw 0) it is west (-X).
            double rx = -Math.cos(yaw);
            double rz = -Math.sin(yaw);
            pan = (dx * rx + dz * rz) / horizontal;
            // Gentle: sounds to the side lean towards one ear without leaving the other.
            pan *= 0.7 * Math.min(1, horizontal / 2);
        }
        return new float[] {(float) (gain * Math.min(1, 1 - pan)), (float) (gain * Math.min(1, 1 + pan))};
    }

    /** Soft limiter: transparent below 0.8, rounds peaks off instead of clipping. */
    static void limit(float[] samples) {
        for (int i = 0; i < samples.length; i++) {
            float x = samples[i];
            float a = Math.abs(x);
            if (a > 0.8f) {
                samples[i] = Math.signum(x) * (0.8f + 0.2f * (float) Math.tanh((a - 0.8f) / 0.2f));
            }
        }
    }
}
