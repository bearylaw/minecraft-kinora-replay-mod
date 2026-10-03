package dev.kinora.core.audio;

import dev.kinora.core.project.Project;
import dev.kinora.core.project.SequenceTimeline;
import dev.kinora.core.project.Shot;
import dev.kinora.core.render.RenderPlan;
import dev.kinora.core.render.RenderSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where in the video each recorded sound is heard. The video's time remap is sampled once per
 * output frame; wherever replay time passes a sound's tick between two frames of the same shot,
 * the sound starts there, at the speed replay time is moving.
 *
 * <p>A freeze plays nothing new, reverse plays sounds forwards as replay time passes them, and a
 * cut never plays the sounds of the replay time it skips.
 */
public final class AudioTimeline {
    /** One shot visible in a frame. During a dissolve a frame has two. */
    public record Lane(String shotId, double replayTicks, double weight) {}

    /**
     * A sound placed in the video.
     *
     * @param outputSeconds when it starts in the video
     * @param speed         replay ticks per second divided by 20 (1 is normal speed)
     * @param weight        share of the frame its shot has (dissolves)
     */
    public record Placement(SoundCue cue, double outputSeconds, double speed, double weight) {}

    private AudioTimeline() {}

    /** Lanes at the start of every output frame, plus one at the very end. */
    public static List<List<Lane>> sample(Project project, Shot shotOrNull, RenderSettings settings) {
        double duration = shotOrNull != null ? shotOrNull.duration : new SequenceTimeline(project).duration();
        int frames = RenderPlan.frameCount(duration, settings);
        SequenceTimeline timeline = shotOrNull == null ? new SequenceTimeline(project) : null;
        List<List<Lane>> out = new ArrayList<>(frames + 1);
        for (int f = 0; f <= frames; f++) {
            double t = Math.min(duration, RenderPlan.frameTime(f, settings));
            List<Lane> lanes = new ArrayList<>(2);
            if (shotOrNull != null) {
                lanes.add(new Lane(shotOrNull.id, shotOrNull.replayTicks(t), 1));
            } else {
                SequenceTimeline.Sample s = timeline.sample(t);
                if (s != null) {
                    lanes.add(new Lane(s.primary().id, s.primary().replayTicks(s.primaryTime()), (1 - s.mix()) * (1 - s.fade())));
                    if (s.secondary() != null && s.mix() > 0) {
                        lanes.add(new Lane(s.secondary().id, s.secondary().replayTicks(s.secondaryTime()), s.mix() * (1 - s.fade())));
                    }
                }
            }
            out.add(lanes);
        }
        return out;
    }

    /**
     * Places the cues.
     *
     * @param frameSeconds length of one output frame
     */
    public static List<Placement> place(List<List<Lane>> lanes, double frameSeconds, List<SoundCue> cues, RenderSettings.PitchMode mode) {
        List<SoundCue> sorted = new ArrayList<>(cues);
        sorted.sort(Comparator.comparingDouble(SoundCue::replayTicks));
        double[] ticks = sorted.stream().mapToDouble(SoundCue::replayTicks).toArray();
        List<Placement> out = new ArrayList<>();
        for (int f = 0; f + 1 < lanes.size(); f++) {
            for (Lane a : lanes.get(f)) {
                Lane b = null;
                for (Lane candidate : lanes.get(f + 1)) {
                    if (candidate.shotId().equals(a.shotId())) {
                        b = candidate;
                    }
                }
                if (b == null || a.replayTicks() == b.replayTicks()) {
                    continue;
                }
                double dr = b.replayTicks() - a.replayTicks();
                double speed = Math.abs(dr) / frameSeconds / 20.0;
                if (!audible(speed, mode)) {
                    continue;
                }
                double lo = Math.min(a.replayTicks(), b.replayTicks());
                double hi = Math.max(a.replayTicks(), b.replayTicks());
                for (int i = lowerBound(ticks, lo); i < ticks.length && ticks[i] < hi; i++) {
                    double fraction = (ticks[i] - a.replayTicks()) / dr;
                    double weight = (a.weight() + b.weight()) / 2;
                    if (weight > 0.01) {
                        out.add(new Placement(sorted.get(i), (f + fraction) * frameSeconds, speed, weight));
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(Placement::outputSeconds));
        return out;
    }

    /** Sounds are left out where replay time moves too fast or too slowly to make sense of them. */
    static boolean audible(double speed, RenderSettings.PitchMode mode) {
        return switch (mode) {
            case MUTE -> Math.abs(speed - 1) < 0.02;
            case FOLLOW, PRESERVE -> speed >= 0.125 && speed <= 8;
        };
    }

    private static int lowerBound(double[] a, double v) {
        int lo = 0;
        int hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
