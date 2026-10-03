package dev.kinora.mc.ui;

import dev.kinora.core.camera.CameraState;
import dev.kinora.core.camera.Vec3d;
import dev.kinora.core.project.Shot;
import dev.kinora.core.project.ShotEvaluator;
import dev.kinora.core.project.Tracks;
import dev.kinora.core.timeline.Keyframe;
import dev.kinora.core.timeline.Track;
import dev.kinora.mc.editor.EditorState;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.render.RenderRunner;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the selected shot in the world while the editor is open: the camera's path as a line, a
 * dot per position key (selected keys larger and gold), the view direction at each key, where
 * the camera is at the playhead, and a fading green trail of the subjects' last three seconds.
 * Uses the game's gizmo system, so it is drawn with the world and never appears in renders (the
 * editor is closed while rendering).
 */
public final class PathGizmos {
    private static final int PATH_COLOR = 0xFF56B4E9;
    private static final int HANDLE_COLOR = 0xFFF0E442;
    /** Where the camera would be inside a block. */
    private static final int BLOCKED_COLOR = 0xFFE04040;
    private static final int KEY_COLOR = 0xFFFFFFFF;
    private static final int SELECTED_COLOR = 0xFFE69F00;
    private static final int PLAYHEAD_COLOR = 0xFFD55E00;
    private static final int SAMPLES_PER_SECOND = 20;

    private static boolean visible = true;

    private PathGizmos() {}

    public static boolean visible() {
        return visible;
    }

    public static void setVisible(boolean show) {
        visible = show;
    }

    /** Called once per frame while gizmos can be added (during the frame's update). */
    public static void emit() {
        Minecraft mc = Minecraft.getInstance();
        if (!visible || RenderRunner.rendering() || !(mc.gui.screen() instanceof EditorScreen)) {
            return;
        }
        EditorState editor = ReplayManager.INSTANCE.editor();
        Shot shot = editor.shot();
        if (shot == null || editor.view() != EditorState.View.SHOT || mc.level == null) {
            return;
        }
        var scene = editor.scene();
        // The path: the evaluated camera, so rigs, look-at and time remaps show as they will render.
        int samples = Math.max(2, (int) Math.ceil(shot.duration * SAMPLES_PER_SECOND));
        Vec3 previous = null;
        for (int i = 0; i <= samples; i++) {
            double t = shot.duration * i / samples;
            CameraState c = ShotEvaluator.evaluate(shot, t, scene).camera();
            if (c == null) {
                previous = null;
                continue;
            }
            Vec3 p = new Vec3(c.x(), c.y(), c.z());
            if (previous != null) {
                boolean blocked = scene.blocked(new dev.kinora.core.camera.Vec3d(c.x(), c.y(), c.z()));
                Gizmos.line(previous, p, blocked ? BLOCKED_COLOR : PATH_COLOR, blocked ? 3.0f : 2.0f);
            }
            previous = p;
        }
        // Keys: position dots, with the view direction from each.
        Track position = shot.existing(Tracks.POSITION);
        if (position != null && shot.rig.mode == Shot.RigMode.PATH) {
            for (Keyframe k : position.keys) {
                boolean selected = editor.selection().contains(k);
                Vec3 p = new Vec3(k.value[0], k.value[1], k.value[2]);
                Gizmos.point(p, selected ? SELECTED_COLOR : KEY_COLOR, selected ? 10f : 7f);
                if (k.interpolation == dev.kinora.core.timeline.Interpolation.BEZIER) {
                    // Bezier handles: where the curve heads as it leaves and arrives.
                    for (double[] h : new double[][] {k.inValue, k.outValue}) {
                        if (h != null && h.length >= 3) {
                            Vec3 hp = p.add(h[0], h[1], h[2]);
                            Gizmos.line(p, hp, HANDLE_COLOR, 1.0f);
                            Gizmos.point(hp, HANDLE_COLOR, 5f);
                        }
                    }
                }
                CameraState c = ShotEvaluator.evaluate(shot, k.time, scene).camera();
                if (c != null) {
                    Vec3d f = c.forward();
                    Gizmos.arrow(p, p.add(f.x() * 1.5, f.y() * 1.5, f.z() * 1.5), selected ? SELECTED_COLOR : KEY_COLOR, 1.5f);
                }
            }
        }
        // Motion trails: where the shot's subjects were over the last three seconds of replay.
        double now = ReplayManager.INSTANCE.session() == null ? 0 : ReplayManager.INSTANCE.session().clock().time();
        for (int entity : new int[] {shot.rig.targetEntity, shot.lookAt.targetEntity}) {
            if (entity == Integer.MIN_VALUE) {
                continue;
            }
            Vec3 previousTrail = null;
            for (double back = 60; back >= 0; back -= 2) {
                Vec3d p = scene.entityPosition(entity, now - back);
                if (p == null) {
                    continue;
                }
                Vec3 v = new Vec3(p.x(), p.y() + 0.1, p.z());
                if (previousTrail != null) {
                    // Older segments fainter.
                    int alpha = (int) (255 * (1 - back / 70.0));
                    Gizmos.line(previousTrail, v, (alpha << 24) | 0x009E73, 2.0f);
                }
                previousTrail = v;
            }
        }
        // The camera at the playhead, when flying freely (otherwise the view is the camera).
        if (!editor.previewCamera()) {
            CameraState c = ShotEvaluator.evaluate(shot, editor.playhead(), scene).camera();
            if (c != null) {
                Vec3 p = new Vec3(c.x(), c.y(), c.z());
                Vec3d f = c.forward();
                Gizmos.point(p, PLAYHEAD_COLOR, 12f);
                Gizmos.arrow(p, p.add(f.x() * 2.5, f.y() * 2.5, f.z() * 2.5), PLAYHEAD_COLOR, 2.5f);
            }
        }
    }
}
