package dev.kinora.core.project;

import dev.kinora.core.camera.CameraState;
import dev.kinora.core.camera.Noise;
import dev.kinora.core.camera.Quat;
import dev.kinora.core.camera.SceneQuery;
import dev.kinora.core.camera.Vec3d;
import dev.kinora.core.timeline.Track;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Computes what a shot shows at a moment: the camera, the replay time, and every other keyframed
 * value (effects, world overrides).
 *
 * <p>Evaluation is a pure function of the shot, the time and the {@link SceneQuery}: no state is
 * carried between frames. Smoothing (rig damping, look-at smoothing) is computed from the
 * target's recent positions, so a frame renders the same whether it is rendered alone, in order,
 * or twice.
 */
public final class ShotEvaluator {
    /** The camera tracks; everything else ends up in {@link Frame#values}. */
    private static final Set<String> CAMERA_TRACKS = Set.of(Tracks.POSITION, Tracks.ROTATION, Tracks.ROLL, Tracks.FOV, Tracks.TIME,
            Tracks.LOOK_AT_WEIGHT, Tracks.SHAKE, Tracks.ORBIT_ANGLE, Tracks.ORBIT_RADIUS, Tracks.ORBIT_HEIGHT);
    private static final int SMOOTHING_SAMPLES = 12;

    /** What a shot shows at one moment. */
    public record Frame(CameraState camera, double replayTicks, Map<String, Double> values) {
        public double value(String trackId) {
            Double v = values.get(trackId);
            return v != null ? v : Tracks.defaultValue(trackId);
        }
    }

    private ShotEvaluator() {}

    /**
     * Evaluates {@code shot} at shot time {@code t} (seconds). The camera is null when the shot
     * does not control it (a path rig with no position keys).
     */
    public static Frame evaluate(Shot shot, double t, SceneQuery scene) {
        double replay = shot.replayTicks(t);
        double fov = shot.value(Tracks.FOV, t);
        double roll = shot.value(Tracks.ROLL, t);
        Vec3d position;
        double yaw;
        double pitch;
        Vec3d aimFallback = null;
        switch (shot.rig.mode) {
            case FOLLOW -> {
                Vec3d target = smoothedTarget(scene, shot.rig.targetEntity, replay, shot.rig.damping, 0);
                if (target == null) {
                    return pathFrame(shot, t, replay, fov, roll, scene);
                }
                Vec3d offset = shot.rig.offset;
                if (shot.rig.relative) {
                    double bodyYaw = scene.entityYaw(shot.rig.targetEntity, replay);
                    if (!Double.isNaN(bodyYaw)) {
                        offset = rotateYaw(offset, bodyYaw);
                    }
                }
                Vec3d aim = target.add(0, scene.entityAimHeight(shot.rig.targetEntity), 0);
                position = target.add(offset);
                if (shot.rig.avoidCollisions) {
                    position = pullIn(scene, aim, position);
                }
                aimFallback = aim;
                Vec3d dir = aim.sub(position);
                yaw = dir.yawDegrees();
                pitch = dir.pitchDegrees();
            }
            case ORBIT -> {
                Vec3d target = smoothedTarget(scene, shot.rig.targetEntity, replay, shot.rig.damping, 0);
                if (target == null) {
                    return pathFrame(shot, t, replay, fov, roll, scene);
                }
                Vec3d center = target.add(0, scene.entityAimHeight(shot.rig.targetEntity), 0);
                Track angleTrack = shot.existing(Tracks.ORBIT_ANGLE);
                double angle = angleTrack != null ? angleTrack.evaluate(t)[0] : shot.rig.orbitSpeed * t;
                double radius = shot.value(Tracks.ORBIT_RADIUS, t);
                double height = shot.value(Tracks.ORBIT_HEIGHT, t);
                double a = Math.toRadians(angle);
                position = center.add(-Math.sin(a) * radius, height, Math.cos(a) * radius);
                if (shot.rig.avoidCollisions) {
                    position = pullIn(scene, center, position);
                }
                aimFallback = center;
                Vec3d dir = center.sub(position);
                yaw = dir.yawDegrees();
                pitch = dir.pitchDegrees();
            }
            default -> {
                return pathFrame(shot, t, replay, fov, roll, scene);
            }
        }
        CameraState camera = new CameraState(position.x(), position.y(), position.z(), yaw, pitch, roll, fov);
        camera = applyLookAt(shot, t, replay, camera, scene, aimFallback);
        camera = applyShake(shot, t, camera);
        return new Frame(camera, replay, values(shot, t));
    }

    private static Frame pathFrame(Shot shot, double t, double replay, double fov, double roll, SceneQuery scene) {
        Track positionTrack = shot.existing(Tracks.POSITION);
        if (positionTrack == null) {
            return new Frame(null, replay, values(shot, t));
        }
        double[] p = positionTrack.evaluate(t);
        Track rotationTrack = shot.existing(Tracks.ROTATION);
        double yaw;
        double pitch;
        if (rotationTrack != null) {
            double[] r = rotationTrack.evaluate(t);
            yaw = r[0];
            pitch = r[1];
        } else {
            // No rotation keys: look along the direction of travel.
            double dt = 1e-3;
            double[] a = positionTrack.evaluate(Math.max(positionTrack.startTime(), t - dt));
            double[] b = positionTrack.evaluate(Math.min(positionTrack.endTime(), t + dt));
            Vec3d dir = new Vec3d(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
            yaw = dir.lengthSquared() < 1e-12 ? 0 : dir.yawDegrees();
            pitch = dir.lengthSquared() < 1e-12 ? 0 : dir.pitchDegrees();
        }
        CameraState camera = new CameraState(p[0], p[1], p[2], yaw, pitch, roll, fov);
        camera = applyLookAt(shot, t, replay, camera, scene, null);
        camera = applyShake(shot, t, camera);
        return new Frame(camera, replay, values(shot, t));
    }

    private static CameraState applyLookAt(Shot shot, double t, double replay, CameraState camera, SceneQuery scene, Vec3d fallback) {
        Shot.LookAt look = shot.lookAt;
        if (!look.enabled) {
            return camera;
        }
        Vec3d aim;
        if (look.targetEntity != Integer.MIN_VALUE) {
            Vec3d target = smoothedTarget(scene, look.targetEntity, replay, look.smoothing, look.lead);
            if (target == null) {
                return camera;
            }
            aim = target.add(0, scene.entityAimHeight(look.targetEntity), 0);
        } else if (look.point != null) {
            aim = look.point;
        } else if (fallback != null) {
            aim = fallback;
        } else {
            return camera;
        }
        aim = aim.add(look.offset);
        Vec3d dir = aim.sub(camera.position());
        if (dir.lengthSquared() < 1e-9) {
            return camera;
        }
        double weight = Math.max(0, Math.min(1, shot.value(Tracks.LOOK_AT_WEIGHT, t)));
        if (weight >= 1) {
            return camera.withRotation(dir.yawDegrees(), dir.pitchDegrees(), camera.roll());
        }
        Quat from = Quat.fromYawPitchRoll(camera.yaw(), camera.pitch(), 0);
        Quat to = Quat.fromYawPitchRoll(dir.yawDegrees(), dir.pitchDegrees(), 0);
        double[] ypr = Quat.slerp(from, to, weight).toYawPitchRoll();
        double yaw = ypr[0] + 360 * Math.round((camera.yaw() - ypr[0]) / 360);
        return camera.withRotation(yaw, ypr[1], camera.roll());
    }

    private static CameraState applyShake(Shot shot, double t, CameraState camera) {
        Shot.Shake shake = shot.shake;
        double posAmp = shake.positionAmplitude();
        double rotAmp = shake.rotationAmplitude();
        if (posAmp == 0 && rotAmp == 0) {
            return camera;
        }
        double intensity = shot.value(Tracks.SHAKE, t);
        double x = t * shake.frequency();
        long seed = shake.seed;
        return new CameraState(
                camera.x() + posAmp * intensity * Noise.fractal(seed, 0, x),
                camera.y() + posAmp * intensity * Noise.fractal(seed, 1, x),
                camera.z() + posAmp * intensity * Noise.fractal(seed, 2, x),
                camera.yaw() + rotAmp * intensity * Noise.fractal(seed, 3, x),
                camera.pitch() + rotAmp * intensity * Noise.fractal(seed, 4, x),
                camera.roll() + rotAmp * 0.5 * intensity * Noise.fractal(seed, 5, x),
                camera.fov());
    }

    /**
     * Target position smoothed over the last {@code smoothingSeconds} of replay time (exponential
     * weights), optionally extrapolated {@code leadSeconds} ahead from its velocity.
     */
    static Vec3d smoothedTarget(SceneQuery scene, int entity, double replayTicks, double smoothingSeconds, double leadSeconds) {
        if (entity == Integer.MIN_VALUE) {
            return null;
        }
        Vec3d now = scene.entityPosition(entity, replayTicks);
        if (now == null) {
            return null;
        }
        Vec3d result = now;
        if (smoothingSeconds > 1e-3) {
            double tau = smoothingSeconds * 20;
            double sumW = 0;
            Vec3d sum = Vec3d.ZERO;
            for (int i = 0; i < SMOOTHING_SAMPLES; i++) {
                double back = tau * 3.0 * i / (SMOOTHING_SAMPLES - 1);
                Vec3d p = scene.entityPosition(entity, Math.max(0, replayTicks - back));
                if (p == null) {
                    continue;
                }
                double w = Math.exp(-back / tau);
                sum = sum.add(p.mul(w));
                sumW += w;
            }
            if (sumW > 0) {
                result = sum.mul(1.0 / sumW);
            }
        }
        if (leadSeconds != 0) {
            Vec3d before = scene.entityPosition(entity, Math.max(0, replayTicks - 5));
            if (before != null) {
                Vec3d velocityPerTick = now.sub(before).mul(1.0 / 5);
                result = result.add(velocityPerTick.mul(leadSeconds * 20));
            }
        }
        return result;
    }

    private static Vec3d rotateYaw(Vec3d v, double yawDegrees) {
        double r = Math.toRadians(yawDegrees);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        // Entity-local: +Z forward, +X left; turned by the entity's yaw.
        return new Vec3d(v.x() * cos - v.z() * sin, v.y(), v.x() * sin + v.z() * cos);
    }

    private static Vec3d pullIn(SceneQuery scene, Vec3d from, Vec3d to) {
        Vec3d hit = scene.clip(from, to);
        if (hit.equals(to)) {
            return to;
        }
        Vec3d back = from.sub(hit).normalize().mul(0.25);
        return hit.add(back);
    }

    private static Map<String, Double> values(Shot shot, double t) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Track> e : shot.tracks.entrySet()) {
            Track track = e.getValue();
            if (CAMERA_TRACKS.contains(e.getKey()) || track.isEmpty() || track.muted || track.kind != Track.Kind.VALUE) {
                continue;
            }
            out.put(e.getKey(), track.evaluate(t)[0]);
        }
        return out;
    }
}
