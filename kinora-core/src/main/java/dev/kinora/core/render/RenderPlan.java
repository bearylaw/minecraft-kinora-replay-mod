package dev.kinora.core.render;

import dev.kinora.core.project.Project;
import dev.kinora.core.project.SequenceTimeline;
import dev.kinora.core.project.Shot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Every image a render needs, when it is shown and in which order to render it.
 *
 * <p>An output frame is made of one or more <em>units</em>: one per motion-blur sub-frame, times
 * two during a dissolve (outgoing and incoming shot). Each unit is a view of the world at one
 * replay time. Units are rendered sorted by replay time, so time remaps that slow down, freeze or
 * reverse never make the replay seek backwards more than once; a {@link FrameAssembler} puts the
 * images back in output order.
 *
 * <p>Output frame {@code i} is at exactly {@code i * den / num} seconds, so a render of {@code d}
 * seconds has {@code ceil(d * fps)} frames and never drops or doubles one.
 */
public final class RenderPlan {
    /**
     * One image to render.
     *
     * @param frame       output frame index
     * @param layer       0 for the primary shot, 1 for the incoming shot of a dissolve
     * @param subFrame    motion-blur sub-frame index
     * @param view        which render of a multi-view image (cube face, eye); 0 for normal renders
     * @param shot        the shot to evaluate
     * @param shotTime    shot time in seconds
     * @param replayTicks replay time to show
     * @param whipYaw     extra camera yaw for whip pans, degrees
     * @param weight      this image's share of the output frame (all units of a frame sum to 1)
     */
    public record Unit(int frame, int layer, int subFrame, int view, Shot shot, double shotTime, double replayTicks, double whipYaw,
                       double weight) {}

    /**
     * How to build one output frame from its units.
     *
     * @param units how many images make it, once the views of each are joined
     * @param fade  how far it fades to black, 0..1
     */
    public record Recipe(int units, double fade) {}

    private final RenderSettings settings;
    private final int frameCount;
    private final List<Unit> renderOrder;
    private final Recipe[] recipes;
    private final boolean monotonic;

    private RenderPlan(RenderSettings settings, int frameCount, List<Unit> units, Recipe[] recipes) {
        this.settings = settings;
        this.frameCount = frameCount;
        this.recipes = recipes;
        List<Unit> sorted = new ArrayList<>(units);
        sorted.sort(Comparator.comparingDouble(Unit::replayTicks).thenComparingInt(Unit::frame).thenComparingInt(Unit::layer)
                .thenComparingInt(Unit::subFrame).thenComparingInt(Unit::view));
        this.renderOrder = Collections.unmodifiableList(sorted);
        this.monotonic = sorted.equals(units);
    }

    /** Plans one shot. */
    public static RenderPlan shot(Shot shot, RenderSettings settings) {
        int frames = frameCount(shot.duration, settings);
        List<Unit> units = new ArrayList<>();
        Recipe[] recipes = new Recipe[frames];
        int samples = Math.max(1, settings.motionBlurSamples);
        int views = Views.count(settings);
        for (int f = 0; f < frames; f++) {
            for (int s = 0; s < samples; s++) {
                double t = Math.min(shot.duration, sampleTime(f, s, settings));
                for (int v = 0; v < views; v++) {
                    units.add(new Unit(f, 0, s, v, shot, t, shot.replayTicks(t), 0, 1.0 / samples));
                }
            }
            recipes[f] = new Recipe(samples, 0);
        }
        return new RenderPlan(settings, frames, units, recipes);
    }

    /** Plans a project's whole edit, transitions included. */
    public static RenderPlan sequence(Project project, RenderSettings settings) {
        SequenceTimeline timeline = new SequenceTimeline(project);
        int frames = frameCount(timeline.duration(), settings);
        List<Unit> units = new ArrayList<>();
        Recipe[] recipes = new Recipe[frames];
        int samples = Math.max(1, settings.motionBlurSamples);
        int views = Views.count(settings);
        for (int f = 0; f < frames; f++) {
            int count = 0;
            double fade = 0;
            for (int s = 0; s < samples; s++) {
                double t = Math.min(timeline.duration(), sampleTime(f, s, settings));
                SequenceTimeline.Sample sample = timeline.sample(t);
                if (sample == null) {
                    continue;
                }
                double primaryWeight = (1 - sample.mix()) / samples;
                for (int v = 0; v < views; v++) {
                    units.add(new Unit(f, 0, s, v, sample.primary(), sample.primaryTime(), sample.primary().replayTicks(sample.primaryTime()),
                            sample.whipYaw(), primaryWeight));
                }
                count++;
                if (sample.secondary() != null && sample.mix() > 0) {
                    for (int v = 0; v < views; v++) {
                        units.add(new Unit(f, 1, s, v, sample.secondary(), sample.secondaryTime(),
                                sample.secondary().replayTicks(sample.secondaryTime()), 0, sample.mix() / samples));
                    }
                    count++;
                }
                // The fade of the frame's middle sub-frame: fades change slowly enough.
                if (s == samples / 2) {
                    fade = sample.fade();
                }
            }
            recipes[f] = new Recipe(count, fade);
        }
        return new RenderPlan(settings, frames, units, recipes);
    }

    /** Frames needed to show {@code seconds} of output. */
    public static int frameCount(double seconds, RenderSettings settings) {
        double exact = seconds * settings.fpsNumerator / settings.fpsDenominator;
        return Math.max(1, (int) Math.ceil(exact - 1e-9));
    }

    /** Output time of frame {@code frame}: exactly {@code frame * den / num} seconds. */
    public static double frameTime(int frame, RenderSettings settings) {
        return frame * (double) settings.fpsDenominator / settings.fpsNumerator;
    }

    /**
     * Shot time of a motion-blur sub-frame: samples are spread evenly over the open shutter, which
     * is centred on the frame time.
     */
    static double sampleTime(int frame, int subFrame, RenderSettings settings) {
        double t = frameTime(frame, settings);
        int samples = Math.max(1, settings.motionBlurSamples);
        if (samples == 1) {
            return t;
        }
        double interval = (double) settings.fpsDenominator / settings.fpsNumerator;
        double open = interval * settings.shutterAngle / 360.0;
        return Math.max(0, t - open / 2 + open * (subFrame + 0.5) / samples);
    }

    public RenderSettings settings() {
        return settings;
    }

    public int frameCount() {
        return frameCount;
    }

    /** Units in the order to render them: by replay time. */
    public List<Unit> renderOrder() {
        return renderOrder;
    }

    public Recipe recipe(int frame) {
        return recipes[frame];
    }

    /** True when render order is output order, so frames stream straight to the output. */
    public boolean monotonic() {
        return monotonic;
    }

    /** Units of frames from {@code firstFrame} on, in render order (for resuming). */
    public List<Unit> renderOrderFrom(int firstFrame) {
        if (firstFrame <= 0) {
            return renderOrder;
        }
        List<Unit> rest = new ArrayList<>();
        for (Unit u : renderOrder) {
            if (u.frame() >= firstFrame) {
                rest.add(u);
            }
        }
        return rest;
    }
}
