package dev.kinora.core.project;

import dev.kinora.core.camera.CameraState;
import dev.kinora.core.camera.Vec3d;
import dev.kinora.core.timeline.Easing;
import dev.kinora.core.timeline.Interpolation;
import dev.kinora.core.timeline.Keyframe;
import dev.kinora.core.timeline.Track;

/**
 * Classic camera moves, inserted in one click at the current camera and target, then edited like
 * any other shot.
 */
public final class ShotTemplates {
    public enum Template {
        ORBIT, DOLLY_ZOOM, CRANE_UP, FLY_THROUGH, WHIP_PAN, REVEAL, PUSH_IN, TURNTABLE
    }

    private ShotTemplates() {}

    /**
     * Builds a shot.
     *
     * @param camera       where the camera is now
     * @param target       point of interest (an entity's position or the block looked at)
     * @param targetEntity entity id to follow or orbit, or {@code Integer.MIN_VALUE}
     * @param replayTicks  replay time the shot starts at
     */
    public static Shot create(Template template, CameraState camera, Vec3d target, int targetEntity, double replayTicks, double seconds) {
        Shot shot = Shot.create(name(template), replayTicks, seconds);
        Vec3d from = camera.position();
        Vec3d aim = target != null ? target : from.add(camera.forward().mul(10));
        double distance = Math.max(2, from.distance(aim));
        switch (template) {
            case ORBIT, TURNTABLE -> {
                if (targetEntity != Integer.MIN_VALUE) {
                    shot.rig.mode = Shot.RigMode.ORBIT;
                    shot.rig.targetEntity = targetEntity;
                    shot.rig.damping = 0.1;
                } else {
                    // Orbit a fixed point: a circular keyframed path aimed at it.
                    circle(shot, aim, distance, from.y() - aim.y(), seconds, template == Template.TURNTABLE ? 360 : 90, from);
                    shot.lookAt.enabled = true;
                    shot.lookAt.point = aim;
                    return shot;
                }
                Vec3d rel = from.sub(aim);
                double startAngle = Math.toDegrees(Math.atan2(-rel.x(), rel.z()));
                Track angle = shot.track(Tracks.ORBIT_ANGLE);
                double sweep = template == Template.TURNTABLE ? 360 : 90;
                angle.put(new Keyframe(0, startAngle).withInterpolation(Interpolation.LINEAR)
                        .withEasing(template == Template.TURNTABLE ? Easing.LINEAR : Easing.SINE_IN_OUT));
                angle.put(new Keyframe(seconds, startAngle + sweep));
                shot.track(Tracks.ORBIT_RADIUS).put(new Keyframe(0, Math.hypot(rel.x(), rel.z())));
                shot.track(Tracks.ORBIT_HEIGHT).put(new Keyframe(0, rel.y()));
            }
            case DOLLY_ZOOM -> {
                Vec3d end = from.lerp(aim, 0.5);
                double d2 = end.distance(aim);
                double fov2 = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(camera.fov()) / 2) * distance / d2));
                path(shot, seconds, Easing.SINE_IN_OUT, from, end);
                Track fov = shot.track(Tracks.FOV);
                fov.put(new Keyframe(0, camera.fov()).withInterpolation(Interpolation.LINEAR).withEasing(Easing.SINE_IN_OUT));
                fov.put(new Keyframe(seconds, Math.min(150, fov2)));
                look(shot, aim);
            }
            case CRANE_UP -> {
                path(shot, seconds, Easing.SINE_IN_OUT, from, from.add(0, 8, 0).sub(camera.forward().mul(2)));
                look(shot, aim);
            }
            case FLY_THROUGH -> {
                Vec3d forward = camera.forward();
                Vec3d level = new Vec3d(forward.x(), 0, forward.z()).normalize();
                path(shot, seconds, Easing.LINEAR, from, from.add(level.mul(15)).add(0, 1.5, 0), from.add(level.mul(30)).add(0, 3, 0));
                shot.track(Tracks.POSITION).constantSpeed = true;
            }
            case WHIP_PAN -> {
                path(shot, seconds, Easing.LINEAR, from, from);
                Track rotation = shot.track(Tracks.ROTATION);
                rotation.rotationMode = Track.RotationMode.FREE;
                rotation.put(new Keyframe(0, camera.yaw(), camera.pitch()).withInterpolation(Interpolation.LINEAR).withEasing(Easing.EXPO_IN_OUT));
                rotation.put(new Keyframe(seconds, camera.yaw() + 180, camera.pitch()));
            }
            case REVEAL -> {
                Vec3d back = from.sub(aim).normalize();
                Vec3d start = aim.add(back.mul(Math.max(2, distance * 0.3))).add(0, -1, 0);
                Vec3d end = aim.add(back.mul(distance * 1.4)).add(0, distance * 0.6, 0);
                path(shot, seconds, Easing.SINE_IN_OUT, start, end);
                look(shot, aim);
            }
            case PUSH_IN -> {
                path(shot, seconds, Easing.SINE_IN_OUT, from, from.lerp(aim, 0.25));
                Track fov = shot.track(Tracks.FOV);
                fov.put(new Keyframe(0, camera.fov()).withInterpolation(Interpolation.LINEAR).withEasing(Easing.SINE_IN_OUT));
                fov.put(new Keyframe(seconds, camera.fov() * 0.9));
                look(shot, aim);
            }
        }
        if (camera.roll() != 0) {
            shot.track(Tracks.ROLL).put(new Keyframe(0, camera.roll()));
        }
        return shot;
    }

    private static void path(Shot shot, double seconds, Easing easing, Vec3d... points) {
        Track position = shot.track(Tracks.POSITION);
        for (int i = 0; i < points.length; i++) {
            double t = points.length == 1 ? 0 : seconds * i / (points.length - 1);
            position.put(new Keyframe(t, points[i].x(), points[i].y(), points[i].z())
                    .withInterpolation(Interpolation.CENTRIPETAL).withEasing(easing));
        }
    }

    private static void look(Shot shot, Vec3d point) {
        shot.lookAt.enabled = true;
        shot.lookAt.point = point;
    }

    private static void circle(Shot shot, Vec3d center, double radius, double height, double seconds, double sweep, Vec3d from) {
        Vec3d rel = from.sub(center);
        double start = Math.atan2(-rel.x(), rel.z());
        int keys = Math.max(3, (int) Math.ceil(sweep / 45) + 1);
        Track position = shot.track(Tracks.POSITION);
        position.constantSpeed = true;
        for (int i = 0; i < keys; i++) {
            double a = start + Math.toRadians(sweep) * i / (keys - 1);
            position.put(new Keyframe(seconds * i / (keys - 1), center.x() - Math.sin(a) * radius, center.y() + height, center.z() + Math.cos(a) * radius)
                    .withInterpolation(Interpolation.CATMULL_ROM));
        }
    }

    public static String name(Template template) {
        return switch (template) {
            case ORBIT -> "Orbit";
            case DOLLY_ZOOM -> "Dolly zoom";
            case CRANE_UP -> "Crane up";
            case FLY_THROUGH -> "Fly-through";
            case WHIP_PAN -> "Whip pan";
            case REVEAL -> "Reveal";
            case PUSH_IN -> "Slow push-in";
            case TURNTABLE -> "360 turntable";
        };
    }
}
