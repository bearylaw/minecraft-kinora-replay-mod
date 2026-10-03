package dev.kinora.core.project;

import dev.kinora.core.timeline.Keyframe;
import dev.kinora.core.timeline.Track;

import java.util.Map;

/**
 * A shot's look: its grading and lens-effect tracks ({@code grade.*}, {@code fx.*}) and its camera
 * shake. Giving every shot of an edit the same look is the most repeated edit, so it is one action.
 * Camera moves, timing, depth of field and world tracks belong to the moment and are left alone.
 */
public final class ShotLook {
    private ShotLook() {}

    public static boolean isLookTrack(String trackId) {
        return trackId.startsWith("grade.") || trackId.startsWith("fx.");
    }

    /** Replaces {@code to}'s look with {@code from}'s; keyed changes are stretched to {@code to}'s length. */
    public static void apply(Shot from, Shot to) {
        if (from == to) {
            return;
        }
        to.tracks.keySet().removeIf(ShotLook::isLookTrack);
        double scale = from.duration > 0 ? to.duration / from.duration : 1;
        for (Map.Entry<String, Track> e : from.tracks.entrySet()) {
            if (!isLookTrack(e.getKey())) {
                continue;
            }
            Track copy = e.getValue().copy();
            for (Keyframe k : copy.keys) {
                k.time *= scale;
            }
            copy.changed();
            to.tracks.put(e.getKey(), copy);
        }
        to.shake.preset = from.shake.preset;
        to.shake.position = from.shake.position;
        to.shake.rotation = from.shake.rotation;
        to.shake.frequency = from.shake.frequency;
        to.shake.seed = from.shake.seed;
    }
}
