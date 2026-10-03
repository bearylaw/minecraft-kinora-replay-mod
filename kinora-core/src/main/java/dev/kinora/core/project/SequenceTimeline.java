package dev.kinora.core.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where each shot of a project's edit sits on the output timeline, and what is on screen at any
 * output time: one shot, or two during a dissolve or whip pan.
 */
public final class SequenceTimeline {
    /** A shot placed on the output timeline. */
    public record Placement(Shot shot, Project.Clip clip, double start, double end, double inOverlap, double outOverlap) {
        public double duration() {
            return end - start;
        }
    }

    /**
     * What to show at one output time.
     *
     * @param primary      the shot that is on screen (the outgoing one during a transition)
     * @param primaryTime  its shot time
     * @param secondary    the incoming shot during an overlapping transition, else null
     * @param mix          how much of the secondary shows, 0..1
     * @param fade         fade to black, 0 (none) .. 1 (black), for dip-to-black transitions
     * @param whipYaw      extra yaw in degrees for whip pans
     */
    public record Sample(Shot primary, double primaryTime, Shot secondary, double secondaryTime, double mix,
                         Project.Transition transition, double fade, double whipYaw) {}

    private final List<Placement> placements;
    private final double duration;

    public SequenceTimeline(Project project) {
        List<Placement> list = new ArrayList<>();
        double cursor = 0;
        List<Project.Clip> clips = new ArrayList<>();
        for (Project.Clip clip : project.sequence) {
            if (project.shot(clip.shotId) != null) {
                clips.add(clip);
            }
        }
        for (int i = 0; i < clips.size(); i++) {
            Project.Clip clip = clips.get(i);
            Shot shot = project.shot(clip.shotId);
            double inOverlap = 0;
            if (i > 0 && Project.overlaps(clip.transition)) {
                Shot previous = project.shot(clips.get(i - 1).shotId);
                inOverlap = Math.min(clip.transitionDuration, Math.min(shot.duration, previous.duration));
            }
            double start = cursor - inOverlap;
            double end = start + shot.duration;
            double outOverlap = 0;
            if (i + 1 < clips.size() && Project.overlaps(clips.get(i + 1).transition)) {
                Shot next = project.shot(clips.get(i + 1).shotId);
                outOverlap = Math.min(clips.get(i + 1).transitionDuration, Math.min(shot.duration, next.duration));
            }
            list.add(new Placement(shot, clip, start, end, inOverlap, outOverlap));
            cursor = end;
        }
        this.placements = Collections.unmodifiableList(list);
        this.duration = cursor;
    }

    public List<Placement> placements() {
        return placements;
    }

    public double duration() {
        return duration;
    }

    /** What is on screen at output time {@code t}; null for an empty edit. */
    public Sample sample(double t) {
        if (placements.isEmpty()) {
            return null;
        }
        double time = Math.max(0, Math.min(duration, t));
        for (int i = 0; i < placements.size(); i++) {
            Placement p = placements.get(i);
            boolean last = i == placements.size() - 1;
            if (time < p.end() || last) {
                Placement next = last ? null : placements.get(i + 1);
                double local = time - p.start();
                // Inside the overlap with the next shot?
                if (next != null && p.outOverlap() > 0 && time >= next.start()) {
                    double progress = (time - next.start()) / p.outOverlap();
                    double nextLocal = time - next.start();
                    if (next.clip().transition == Project.Transition.WHIP_PAN) {
                        // First half: the outgoing shot whips away; second half: the incoming one whips in.
                        if (progress < 0.5) {
                            double e = ease(progress * 2);
                            return new Sample(p.shot(), local, null, 0, 0, Project.Transition.WHIP_PAN, 0, 90 * e);
                        }
                        double e = ease((1 - progress) * 2);
                        return new Sample(next.shot(), nextLocal, null, 0, 0, Project.Transition.WHIP_PAN, 0, -90 * e);
                    }
                    return new Sample(p.shot(), local, next.shot(), nextLocal, progress, Project.Transition.DISSOLVE, 0, 0);
                }
                double fade = 0;
                if (next != null && next.clip().transition == Project.Transition.DIP_TO_BLACK) {
                    double half = Math.min(next.clip().transitionDuration / 2, p.duration());
                    if (time > p.end() - half) {
                        fade = (time - (p.end() - half)) / half;
                    }
                }
                if (i > 0 && p.clip().transition == Project.Transition.DIP_TO_BLACK) {
                    double half = Math.min(p.clip().transitionDuration / 2, p.duration());
                    if (local < half) {
                        fade = Math.max(fade, 1 - local / half);
                    }
                }
                return new Sample(p.shot(), Math.min(local, p.shot().duration), null, 0, 0, p.clip().transition, Math.min(1, fade), 0);
            }
        }
        return null;
    }

    /** Start of the given shot on the output timeline, or -1. */
    public double startOf(Shot shot) {
        for (Placement p : placements) {
            if (p.shot() == shot) {
                return p.start();
            }
        }
        return -1;
    }

    private static double ease(double s) {
        return s * s * (3 - 2 * s);
    }
}
