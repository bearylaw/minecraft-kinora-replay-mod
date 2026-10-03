package dev.kinora.core.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A keyframed property of a shot: a value curve (FOV, roll, time remap...), a camera path, or a
 * view direction.
 *
 * <p>Keys are kept sorted by time. Evaluation before the first key gives the first key's value,
 * after the last key the last key's value.
 */
public final class Track {
    public enum Kind {
        /** Each component is an independent curve over time. */
        VALUE,
        /** A 3D path through space; supports constant-speed travel along it. */
        PATH,
        /** View direction as yaw and pitch, interpolated on the sphere (no gimbal lock). */
        ROTATION
    }

    public enum RotationMode {
        /** Quaternion interpolation along the shortest arc between directions. */
        SHORTEST,
        /** Yaw and pitch interpolated as plain numbers: 0° to 720° spins twice. */
        FREE
    }

    public String id;
    public Kind kind = Kind.VALUE;
    public int dimension = 1;
    public boolean constantSpeed;
    public RotationMode rotationMode = RotationMode.SHORTEST;
    public boolean muted;
    public boolean locked;
    /** Display colour in the editor, 0xRRGGBB. */
    public int color = 0x7FB3FF;
    public final List<Keyframe> keys = new ArrayList<>();

    private transient ArcLengthTable arcTable;
    private transient int arcVersion = -1;
    private transient int version;

    public Track() {
    }

    public Track(String id, Kind kind, int dimension) {
        this.id = id;
        this.kind = kind;
        this.dimension = dimension;
    }

    /** Adds a key, replacing any key at the same time (within a microsecond). Returns the key. */
    public Keyframe put(Keyframe key) {
        if (key.value == null || key.value.length != dimension) {
            throw new IllegalArgumentException("track " + id + " needs " + dimension + " values per key");
        }
        keys.removeIf(k -> Math.abs(k.time - key.time) < 1e-6);
        keys.add(key);
        keys.sort(Comparator.comparingDouble(k -> k.time));
        changed();
        return key;
    }

    public boolean remove(Keyframe key) {
        boolean removed = keys.remove(key);
        if (removed) {
            changed();
        }
        return removed;
    }

    /** Re-sorts after keys were edited in place (dragged), and invalidates caches. */
    public void changed() {
        keys.sort(Comparator.comparingDouble(k -> k.time));
        version++;
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public double startTime() {
        return keys.isEmpty() ? 0 : keys.getFirst().time;
    }

    public double endTime() {
        return keys.isEmpty() ? 0 : keys.getLast().time;
    }

    /** Value at time {@code t}; null if the track has no keys. */
    public double[] evaluate(double t) {
        if (keys.isEmpty()) {
            return null;
        }
        if (keys.size() == 1 || t <= keys.getFirst().time) {
            return keys.getFirst().value.clone();
        }
        if (t >= keys.getLast().time) {
            return keys.getLast().value.clone();
        }
        return switch (kind) {
            case VALUE -> TrackMath.evaluateValue(keys, t);
            case PATH -> constantSpeed ? arcTable().pointAtFraction((t - startTime()) / (endTime() - startTime())) : TrackMath.evaluatePath(keys, t);
            case ROTATION -> rotationMode == RotationMode.FREE ? TrackMath.evaluateValue(keys, t) : TrackMath.evaluateRotation(keys, t);
        };
    }

    /** Arc-length table of a path track, rebuilt when keys change. */
    public ArcLengthTable arcTable() {
        if (arcTable == null || arcVersion != version) {
            arcTable = ArcLengthTable.build(keys, 128);
            arcVersion = version;
        }
        return arcTable;
    }

    /** Index of the segment containing {@code t}: the last key at or before it. */
    public static int segmentIndex(List<Keyframe> keys, double t) {
        int lo = 0;
        int hi = keys.size() - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (keys.get(mid).time <= t) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return Math.max(0, lo);
    }

    public Track copy() {
        Track t = new Track(id, kind, dimension);
        t.constantSpeed = constantSpeed;
        t.rotationMode = rotationMode;
        t.muted = muted;
        t.locked = locked;
        t.color = color;
        for (Keyframe k : keys) {
            t.keys.add(k.copy());
        }
        return t;
    }
}
