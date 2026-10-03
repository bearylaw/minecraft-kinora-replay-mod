package dev.kinora.core.project;

import dev.kinora.core.render.RenderPresets;
import dev.kinora.core.render.RenderSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * A camera project for one replay: its shots, the order they play in with transitions, and render
 * settings. Saved as JSON next to (or inside a share package with) the replay.
 */
public final class Project {
    public static final int FORMAT_VERSION = 1;

    public enum Transition {
        CUT, DISSOLVE, DIP_TO_BLACK, WHIP_PAN
    }

    /** A shot's place in the edit, and how it begins. */
    public static final class Clip {
        public String shotId;
        /** How this clip comes in from the one before. */
        public Transition transition = Transition.CUT;
        /** Transition length in seconds (overlap for dissolves and whip pans). */
        public double transitionDuration = 0.5;

        public Clip() {
        }

        public Clip(String shotId) {
            this.shotId = shotId;
        }
    }

    /** A named moment on the editor timeline, separate from the replay's recorded markers. */
    public static final class EditorMarker {
        public double replayTicks;
        public String name = "";
        public int color = 0xFF56B4E9;
    }

    public int version = FORMAT_VERSION;
    public String name = "Untitled";
    /** File id of the replay this project films (from the replay header). */
    public String replayFileId = "";
    /** File name of the replay when the project was saved, a hint for finding it. */
    public String replayFileName = "";
    public final List<Shot> shots = new ArrayList<>();
    public final List<Clip> sequence = new ArrayList<>();
    public final List<EditorMarker> markers = new ArrayList<>();
    public RenderSettings render = RenderPresets.defaultSettings();
    public long modifiedMillis;

    public static Project single(Shot shot) {
        Project p = new Project();
        p.shots.add(shot);
        p.sequence.add(new Clip(shot.id));
        return p;
    }

    public Shot shot(String id) {
        for (Shot s : shots) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** Adds a shot at the end of the edit. */
    public Shot add(Shot shot) {
        shots.add(shot);
        sequence.add(new Clip(shot.id));
        return shot;
    }

    public void remove(Shot shot) {
        shots.remove(shot);
        sequence.removeIf(c -> c.shotId.equals(shot.id));
    }

    /** Output length of the whole edit in seconds, with transition overlaps subtracted. */
    public double sequenceDuration() {
        double total = 0;
        boolean first = true;
        for (Clip clip : sequence) {
            Shot shot = shot(clip.shotId);
            if (shot == null) {
                continue;
            }
            total += shot.duration;
            if (!first && overlaps(clip.transition)) {
                total -= Math.min(clip.transitionDuration, shot.duration);
            }
            first = false;
        }
        return Math.max(0, total);
    }

    /** Transitions that blend two shots (and so overlap them); the others play back to back. */
    public static boolean overlaps(Transition t) {
        return t == Transition.DISSOLVE || t == Transition.WHIP_PAN;
    }
}
