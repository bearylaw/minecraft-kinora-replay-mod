package dev.kinora.mc.editor;

import dev.kinora.core.camera.CameraState;
import dev.kinora.core.camera.SceneQuery;
import dev.kinora.core.camera.Vec3d;
import dev.kinora.core.project.Shot;
import dev.kinora.core.project.ShotTemplates;

import java.util.ArrayList;
import java.util.List;

/**
 * Suggests camera moves around a subject: an orbit, a push-in and a crane reveal, each starting from
 * the direction with the clearest view of the subject (fewest blocks between camera and subject
 * along the move). The user keeps, tweaks or deletes them.
 */
public final class AutoDirector {
    private static final int DIRECTIONS = 16;

    private AutoDirector() {}

    public static List<Shot> suggest(SceneQuery scene, int entity, String subjectName, double replayTicks) {
        Vec3d feet = scene.entityPosition(entity, replayTicks);
        if (feet == null) {
            return List.of();
        }
        Vec3d target = feet.add(0, scene.entityAimHeight(entity), 0);
        List<Shot> shots = new ArrayList<>();
        shots.add(named(make(ShotTemplates.Template.ORBIT, scene, target, entity, replayTicks, 6, 7, 3), "Orbit", subjectName));
        shots.add(named(make(ShotTemplates.Template.PUSH_IN, scene, target, entity, replayTicks, 4, 9, 1.5), "Push-in", subjectName));
        shots.add(named(make(ShotTemplates.Template.CRANE_UP, scene, target, entity, replayTicks, 6, 8, 1), "Crane reveal", subjectName));
        return shots;
    }

    private static Shot named(Shot shot, String move, String subject) {
        shot.name = "Auto: " + move + " (" + subject + ")";
        shot.notes = "Suggested by the auto-director; adjust or delete.";
        return shot;
    }

    /** A template started from the clearest of 16 directions around the target. */
    private static Shot make(ShotTemplates.Template template, SceneQuery scene, Vec3d target, int entity, double ticks, double seconds,
                             double distance, double height) {
        double bestYaw = 0;
        double bestScore = -1;
        for (int i = 0; i < DIRECTIONS; i++) {
            double yaw = 360.0 * i / DIRECTIONS;
            double score = clearance(scene, target, yaw, distance, height);
            if (score > bestScore + 1e-9) {
                bestScore = score;
                bestYaw = yaw;
            }
        }
        // Camera placed at bestYaw around the target, looking at it.
        double a = Math.toRadians(bestYaw);
        Vec3d position = target.add(-Math.sin(a) * distance, height, Math.cos(a) * distance);
        Vec3d dir = target.sub(position);
        CameraState camera = new CameraState(position.x(), position.y(), position.z(), dir.yawDegrees(), dir.pitchDegrees(), 0, 70);
        return ShotTemplates.create(template, camera, target, entity, ticks, seconds);
    }

    /** How far the view from this side reaches before a block, summed over a few nearby angles (0..1 each). */
    private static double clearance(SceneQuery scene, Vec3d target, double yaw, double distance, double height) {
        double total = 0;
        for (int d = -1; d <= 1; d++) {
            double a = Math.toRadians(yaw + d * 15);
            Vec3d camera = target.add(-Math.sin(a) * distance, height, Math.cos(a) * distance);
            Vec3d hit = scene.clip(target, camera);
            total += Math.min(1, hit.sub(target).length() / camera.sub(target).length());
        }
        return total;
    }
}
