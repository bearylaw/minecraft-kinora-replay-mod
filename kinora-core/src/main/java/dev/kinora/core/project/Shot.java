package dev.kinora.core.project;

import dev.kinora.core.camera.Vec3d;
import dev.kinora.core.timeline.Interpolation;
import dev.kinora.core.timeline.Keyframe;
import dev.kinora.core.timeline.Track;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One shot: a camera move over a stretch of the replay. Its own timeline runs from 0 to
 * {@link #duration} seconds of output video; the {@link Tracks#TIME} track says which replay
 * moment each output moment shows (speed ramps, freezes, reverse).
 */
public final class Shot {
    /** How the camera position is driven. */
    public enum RigMode {
        /** Keyframed path. */
        PATH,
        /** Attached to an entity with an offset and damping. */
        FOLLOW,
        /** Circles an entity; angle, radius and height are tracks. */
        ORBIT
    }

    /** Camera shake presets: amplitude in blocks and degrees, frequency in Hz. */
    public enum ShakePreset {
        STEADY(0, 0, 0), HANDHELD(0.02, 0.6, 0.6), JOG(0.06, 1.5, 2.2), EARTHQUAKE(0.25, 3.0, 8.0), DRONE(0.05, 0.3, 0.25), CUSTOM(0, 0, 0);

        public final double position;
        public final double rotation;
        public final double frequency;

        ShakePreset(double position, double rotation, double frequency) {
            this.position = position;
            this.rotation = rotation;
            this.frequency = frequency;
        }
    }

    /** Follow / orbit rig settings. */
    public static final class Rig {
        public RigMode mode = RigMode.PATH;
        public int targetEntity = Integer.MIN_VALUE;
        /** Offset from the target, in blocks; rotated with the target's yaw when {@link #relative}. */
        public Vec3d offset = new Vec3d(0, 2, -5);
        public boolean relative = true;
        /** Seconds the camera lags behind its target (exponential smoothing). */
        public double damping = 0.3;
        /** Orbit speed in degrees per second, used when the orbit angle track is empty. */
        public double orbitSpeed = 20;
        /** Keep the camera out of solid blocks between it and its target. */
        public boolean avoidCollisions = true;
    }

    /** Aim constraint. */
    public static final class LookAt {
        public boolean enabled;
        public int targetEntity = Integer.MIN_VALUE;
        /** Fixed world point, used when no entity is set. */
        public Vec3d point;
        public Vec3d offset = Vec3d.ZERO;
        /** Seconds of smoothing on the aim. */
        public double smoothing = 0.15;
        /** Seconds to aim ahead of (positive) or behind (negative) a moving target. */
        public double lead;
    }

    public static final class Shake {
        public ShakePreset preset = ShakePreset.STEADY;
        public double position;
        public double rotation;
        public double frequency = 1;
        public long seed = 1;

        public double positionAmplitude() {
            return preset == ShakePreset.CUSTOM ? position : preset.position;
        }

        public double rotationAmplitude() {
            return preset == ShakePreset.CUSTOM ? rotation : preset.rotation;
        }

        public double frequency() {
            return preset == ShakePreset.CUSTOM ? frequency : preset.frequency;
        }
    }

    /** Text or image drawn over the frame. */
    public static final class Overlay {
        public String text = "";
        /** Path of an image (PNG) relative to the project folder, or empty for text. */
        public String image = "";
        public double start;
        public double end = 3;
        /** Centre in 0..1 of the frame. */
        public double x = 0.5;
        public double y = 0.85;
        public double scale = 1;
        public int color = 0xFFFFFFFF;
        public double fadeIn = 0.4;
        public double fadeOut = 0.4;
        public String font = "default";
        public boolean shadow = true;
    }

    public String id = UUID.randomUUID().toString();
    public String name = "Shot";
    public double duration = 5;
    public final Map<String, Track> tracks = new LinkedHashMap<>();
    public Rig rig = new Rig();
    public LookAt lookAt = new LookAt();
    public Shake shake = new Shake();
    public List<Overlay> overlays = new ArrayList<>();
    public String notes = "";

    /** A new shot showing the replay from {@code replayStartTicks} at normal speed for {@code seconds}. */
    public static Shot create(String name, double replayStartTicks, double seconds) {
        Shot shot = new Shot();
        shot.name = name;
        shot.duration = seconds;
        Track time = shot.track(Tracks.TIME);
        time.put(new Keyframe(0, replayStartTicks).withInterpolation(Interpolation.LINEAR));
        time.put(new Keyframe(seconds, replayStartTicks + seconds * 20).withInterpolation(Interpolation.LINEAR));
        return shot;
    }

    /** The track with this id, created empty if the shot does not have it yet. */
    public Track track(String trackId) {
        return tracks.computeIfAbsent(trackId, Tracks::create);
    }

    /** The track if present and non-empty, else null. */
    public Track existing(String trackId) {
        Track t = tracks.get(trackId);
        return t == null || t.isEmpty() || t.muted ? null : t;
    }

    /** Value of a 1D track at time t, or the track's default. */
    public double value(String trackId, double t) {
        Track track = existing(trackId);
        if (track == null) {
            return Tracks.defaultValue(trackId);
        }
        return track.evaluate(t)[0];
    }

    /** Replay time (ticks) shown at shot time {@code t}. */
    public double replayTicks(double t) {
        Track time = existing(Tracks.TIME);
        if (time == null) {
            return t * 20;
        }
        if (time.keys.size() == 1) {
            // One key pins a moment; time flows at normal speed from it.
            Keyframe k = time.keys.getFirst();
            return k.value[0] + (t - k.time) * 20;
        }
        return Math.max(0, time.evaluate(t)[0]);
    }

    /** First and last replay tick the shot shows (for pre-loading and pre-roll). */
    public double[] replayRange() {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        int steps = Math.max(2, (int) (duration * 20));
        for (int i = 0; i <= steps; i++) {
            double r = replayTicks(duration * i / steps);
            lo = Math.min(lo, r);
            hi = Math.max(hi, r);
        }
        return new double[] {lo, hi};
    }

    public Shot copy() {
        Shot s = ProjectIO.fromJson(ProjectIO.toJson(Project.single(this))).shots.getFirst();
        s.id = UUID.randomUUID().toString();
        return s;
    }
}
