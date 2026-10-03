package dev.kinora.core.project;

import dev.kinora.core.camera.SceneQuery;
import dev.kinora.core.camera.Vec3d;
import dev.kinora.core.timeline.Keyframe;
import dev.kinora.core.timeline.Track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps a keyframed camera path out of blocks. {@link #blocked} finds where the path runs through
 * them; {@link #fix} adds keys that steer each such stretch to the nearest clear place (preferring
 * up), then checks again, since a new key bends the curve around it.
 */
public final class PathFixer {
    /** Seconds between checks along the path. */
    static final double STEP = 0.02;
    /** How far from the path a clear place is looked for, in blocks. */
    static final double SEARCH_RADIUS = 4;
    private static final int MAX_ROUNDS = 12;
    private static final List<Vec3d> OFFSETS = offsets();

    private PathFixer() {}

    /** Stretches of shot time {@code [from, to]} where the path's camera is inside a block. */
    public static List<double[]> blocked(Shot shot, SceneQuery scene) {
        List<double[]> out = new ArrayList<>();
        Track position = shot.existing(Tracks.POSITION);
        if (position == null || shot.rig.mode != Shot.RigMode.PATH) {
            return out;
        }
        int steps = Math.max(1, (int) Math.ceil(shot.duration / STEP));
        double start = -1;
        for (int i = 0; i <= steps; i++) {
            double t = shot.duration * i / steps;
            boolean inside = scene.blocked(at(position, t));
            if (inside && start < 0) {
                start = t;
            } else if (!inside && start >= 0) {
                out.add(new double[] {start, shot.duration * (i - 1) / steps});
                start = -1;
            }
        }
        if (start >= 0) {
            out.add(new double[] {start, shot.duration});
        }
        return out;
    }

    /**
     * Adds keys until the path is clear, or no clear place is near. Returns the number of keys added;
     * {@link #blocked} tells whether some stretch is left.
     */
    public static int fix(Shot shot, SceneQuery scene) {
        Track position = shot.existing(Tracks.POSITION);
        if (position == null || shot.rig.mode != Shot.RigMode.PATH) {
            return 0;
        }
        int added = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<double[]> stretches = blocked(shot, scene);
            if (stretches.isEmpty()) {
                break;
            }
            boolean progress = false;
            for (double[] s : stretches) {
                double t = (s[0] + s[1]) / 2;
                Keyframe near = closestKey(position, t);
                if (Math.abs(near.time - t) < 0.02) {
                    Vec3d keyAt = new Vec3d(near.value[0], near.value[1], near.value[2]);
                    if (scene.blocked(keyAt)) {
                        // The key itself is inside: move it.
                        Vec3d clear = clearPlace(scene, keyAt, tangent(position, near.time));
                        if (clear != null) {
                            near.value = new double[] {clear.x(), clear.y(), clear.z()};
                            position.changed();
                            progress = true;
                        }
                        continue;
                    }
                    // The key is clear but the curve next to it is not: steer the curve beside it.
                    t = Math.abs(s[0] - near.time) > Math.abs(s[1] - near.time) ? (s[0] + near.time) / 2 : (s[1] + near.time) / 2;
                    if (Math.abs(t - near.time) < 0.01) {
                        continue;
                    }
                }
                Vec3d clear = clearPlace(scene, at(position, t), tangent(position, t));
                if (clear == null) {
                    continue;
                }
                Keyframe k = new Keyframe(t, clear.x(), clear.y(), clear.z()).withInterpolation(near.interpolation);
                k.label = "clear";
                position.put(k);
                added++;
                progress = true;
            }
            if (!progress) {
                break;
            }
        }
        return added;
    }

    /**
     * A clear place near {@code p}, a little further out than the nearest one when there is room, so
     * the curve through it does not graze the block again.
     */
    static Vec3d clearPlace(SceneQuery scene, Vec3d p, Vec3d tangent) {
        Vec3d q = nearestClear(scene, p, tangent);
        if (q == null) {
            return null;
        }
        Vec3d o = q.sub(p);
        double len = o.length();
        if (len < 1e-9) {
            return q;
        }
        Vec3d further = p.add(o.mul((len + 0.2) / len));
        return scene.blocked(further) ? q : further;
    }

    /**
     * The clear place nearest to {@code p} within the search radius (ties go up), or null. Only
     * places across the path count: moving along it would not take the path out of the block.
     */
    static Vec3d nearestClear(SceneQuery scene, Vec3d p, Vec3d tangent) {
        for (Vec3d o : OFFSETS) {
            double len = o.length();
            if (len > 0 && Math.abs((o.x() * tangent.x() + o.y() * tangent.y() + o.z() * tangent.z()) / len) > 0.35) {
                continue;
            }
            Vec3d q = p.add(o);
            if (!scene.blocked(q)) {
                return q;
            }
        }
        return null;
    }

    private static List<Vec3d> offsets() {
        List<Vec3d> out = new ArrayList<>();
        double step = 0.25;
        int n = (int) Math.round(SEARCH_RADIUS / step);
        for (int x = -n; x <= n; x++) {
            for (int y = -n; y <= n; y++) {
                for (int z = -n; z <= n; z++) {
                    Vec3d o = new Vec3d(x * step, y * step, z * step);
                    if (o.length() <= SEARCH_RADIUS + 1e-9) {
                        out.add(o);
                    }
                }
            }
        }
        // Nearest first; at equal distance, up before sideways before down.
        out.sort(Comparator.comparingDouble(Vec3d::length).thenComparingDouble(o -> -o.y()));
        return out;
    }

    private static boolean nearKey(Track track, double t) {
        return Math.abs(closestKey(track, t).time - t) < 0.1;
    }

    private static Keyframe closestKey(Track track, double t) {
        Keyframe best = track.keys.getFirst();
        for (Keyframe k : track.keys) {
            if (Math.abs(k.time - t) < Math.abs(best.time - t)) {
                best = k;
            }
        }
        return best;
    }

    /** Unit direction of travel at {@code t} (zero when the path stands still). */
    private static Vec3d tangent(Track position, double t) {
        double dt = 1e-3;
        Vec3d a = at(position, Math.max(position.startTime(), t - dt));
        Vec3d b = at(position, Math.min(position.endTime(), t + dt));
        Vec3d d = b.sub(a);
        return d.lengthSquared() < 1e-18 ? new Vec3d(0, 0, 0) : d.normalize();
    }

    private static Vec3d at(Track position, double t) {
        double[] p = position.evaluate(t);
        return new Vec3d(p[0], p[1], p[2]);
    }
}
