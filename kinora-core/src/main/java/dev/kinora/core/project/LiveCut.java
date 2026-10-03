package dev.kinora.core.project;

import dev.kinora.core.timeline.Interpolation;
import dev.kinora.core.timeline.Keyframe;
import dev.kinora.core.timeline.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * Live-cut directing: the replay plays, and the director switches between cameras (shots) as it
 * goes. Each stretch between two cuts becomes a shot of its camera showing exactly that stretch of
 * replay time, and the shots play one after another as the edit.
 *
 * <p>A camera shows the replay time it is cut to: its own timing is replaced, and its moves continue
 * from the moment of its own time that matches the cut ({@link #cameraTime}).
 */
public final class LiveCut {
    /** At replay time {@code replayTicks}, the edit switches to camera {@code camera} (an index into the cameras). */
    public record Cut(double replayTicks, int camera) {}

    private LiveCut() {}

    /**
     * The camera's own time for a replay time: where its time track shows that replay tick (the
     * first such moment), else its start or end, whichever is nearer.
     */
    public static double cameraTime(Shot camera, double replayTicks) {
        int steps = Math.max(4, (int) Math.ceil(camera.duration * 40));
        double best = 0;
        double bestDistance = Double.MAX_VALUE;
        double previous = camera.replayTicks(0);
        if (Math.abs(previous - replayTicks) < bestDistance) {
            bestDistance = Math.abs(previous - replayTicks);
        }
        for (int i = 1; i <= steps; i++) {
            double t = camera.duration * i / steps;
            double r = camera.replayTicks(t);
            double lo = Math.min(previous, r);
            double hi = Math.max(previous, r);
            if (replayTicks >= lo && replayTicks <= hi && hi > lo) {
                // Inside this step: interpolate, and take the first crossing.
                double tPrev = camera.duration * (i - 1) / steps;
                return tPrev + (t - tPrev) * (replayTicks - camera.replayTicks(tPrev)) / (r - camera.replayTicks(tPrev));
            }
            double distance = Math.abs(r - replayTicks);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = t;
            }
            previous = r;
        }
        return best;
    }

    /**
     * The shots of a live cut, in order.
     *
     * @param cuts     in the order they were made; the first is where the edit starts
     * @param endTicks where the edit ends
     */
    public static List<Shot> build(List<Shot> cameras, List<Cut> cuts, double endTicks) {
        List<Shot> out = new ArrayList<>();
        for (int i = 0; i < cuts.size(); i++) {
            Cut cut = cuts.get(i);
            double end = i + 1 < cuts.size() ? cuts.get(i + 1).replayTicks() : endTicks;
            if (end - cut.replayTicks() < 1 || cut.camera() < 0 || cut.camera() >= cameras.size()) {
                // Under a tick (two cuts at once): nothing to show.
                continue;
            }
            Shot camera = cameras.get(cut.camera());
            out.add(segment(camera, cut.replayTicks(), end, camera.name + " · live " + (out.size() + 1)));
        }
        return out;
    }

    /** A copy of the camera that shows replay ticks {@code from..to} at normal speed, its moves continuing from the matching moment. */
    public static Shot segment(Shot camera, double from, double to, String name) {
        double t0 = cameraTime(camera, from);
        Shot s = camera.copy();
        s.name = name;
        s.duration = (to - from) / 20.0;
        for (Track track : s.tracks.values()) {
            for (Keyframe k : track.keys) {
                k.time -= t0;
            }
            track.changed();
        }
        Track time = Tracks.create(Tracks.TIME);
        time.put(new Keyframe(0, from).withInterpolation(Interpolation.LINEAR));
        time.put(new Keyframe(s.duration, to).withInterpolation(Interpolation.LINEAR));
        s.tracks.put(Tracks.TIME, time);
        for (Shot.Overlay o : s.overlays) {
            o.start -= t0;
            o.end -= t0;
        }
        s.overlays.removeIf(o -> o.end <= 0 || o.start >= s.duration);
        return s;
    }
}
