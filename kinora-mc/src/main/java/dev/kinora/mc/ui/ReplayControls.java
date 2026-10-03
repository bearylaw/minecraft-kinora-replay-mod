package dev.kinora.mc.ui;

import dev.kinora.core.format.Marker;
import dev.kinora.mc.camera.CameraDirector;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Keyboard control of a replay while flying (no screen open). These keys only act inside a replay,
 * where vanilla's own uses of them (inventory, chat, ...) are switched off, so they cannot clash
 * with gameplay bindings of the game or other mods.
 */
public final class ReplayControls {
    private ReplayControls() {}

    /** Handles a key press; returns true if it was a replay control. */
    public static boolean onKey(int key, int modifiers) {
        ReplayManager manager = ReplayManager.INSTANCE;
        ReplaySession session = manager.session();
        Minecraft mc = Minecraft.getInstance();
        if (session == null || mc.gui.screen() != null) {
            return false;
        }
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        CameraDirector camera = manager.camera();
        ReplayKeys.Action action = ReplayKeys.action(key);
        var live = dev.kinora.mc.editor.LiveCutSession.active();
        if (live != null) {
            // Live cut: 1-9 cut, the live-cut key finishes, Backspace cancels. Jumps in time are off.
            if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
                live.cut(key - GLFW.GLFW_KEY_1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                dev.kinora.mc.editor.LiveCutSession.cancel();
                return true;
            }
            if (action == ReplayKeys.Action.LIVE_CUT) {
                dev.kinora.mc.editor.LiveCutSession.finish();
                return true;
            }
            if (action != ReplayKeys.Action.PLAY_PAUSE && action != ReplayKeys.Action.SLOWER && action != ReplayKeys.Action.FASTER
                    && action != ReplayKeys.Action.NORMAL_SPEED) {
                return action != null;
            }
        }
        if (action == null) {
            return false;
        }
        switch (action) {
            case PLAY_PAUSE -> session.setPaused(!session.paused());
            case BACK -> session.seek(session.clock().time() - (shift ? 600 : 100));
            case FORWARD -> session.seek(session.clock().time() + (shift ? 600 : 100));
            case TICK_BACK -> {
                session.setPaused(true);
                session.seek(session.clock().time() - (shift ? 0.25 : 1));
            }
            case TICK_FORWARD -> {
                session.setPaused(true);
                session.seek(session.clock().time() + (shift ? 0.25 : 1));
            }
            case SLOWER -> session.clock().setSpeed(nextSpeed(session.clock().speed(), -1));
            case FASTER -> session.clock().setSpeed(nextSpeed(session.clock().speed(), 1));
            case NORMAL_SPEED -> session.clock().setSpeed(1.0);
            case PREVIOUS_MARKER -> jumpMarker(session, -1);
            case NEXT_MARKER -> jumpMarker(session, 1);
            case FREE_CAMERA -> camera.free();
            case SPECTATE -> {
                if (camera.mode() == CameraDirector.Mode.SPECTATE) {
                    camera.free();
                } else {
                    Entity target = targeted(mc);
                    camera.spectate(target != null ? target.getId() : session.recordedPlayerId());
                }
            }
            case ORBIT -> {
                if (camera.mode() == CameraDirector.Mode.ORBIT) {
                    camera.free();
                } else {
                    Entity target = targeted(mc);
                    camera.orbit(target != null ? target.getId() : session.recordedPlayerId());
                }
            }
            case ROLL_LEFT -> camera.adjustRoll(shift ? -1 : -5);
            case ROLL_RIGHT -> camera.adjustRoll(shift ? 1 : 5);
            case ROLL_RESET -> camera.resetRoll();
            case EDITOR -> KinoraUi.openEditor();
            case PHOTO -> dev.kinora.mc.render.PhotoMode.take((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 ? 15360 : shift ? 7680 : 3840);
            case LIVE_CUT -> dev.kinora.mc.editor.LiveCutSession.start();
        }
        return true;
    }

    /** Speeds the [ and ] keys step through. */
    private static final double[] SPEEDS = {0.01, 0.02, 0.05, 0.1, 0.2, 0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4, 5, 8, 10};

    static double nextSpeed(double current, int direction) {
        if (direction > 0) {
            for (double s : SPEEDS) {
                if (s > current + 1e-9) {
                    return s;
                }
            }
            return SPEEDS[SPEEDS.length - 1];
        }
        for (int i = SPEEDS.length - 1; i >= 0; i--) {
            if (SPEEDS[i] < current - 1e-9) {
                return SPEEDS[i];
            }
        }
        return SPEEDS[0];
    }

    private static void jumpMarker(ReplaySession session, int direction) {
        List<Marker> markers = session.markers();
        double now = session.clock().time();
        Marker best = null;
        for (Marker m : markers) {
            if (direction > 0 && m.tick() > now + 1 && (best == null || m.tick() < best.tick())) {
                best = m;
            } else if (direction < 0 && m.tick() < now - 1 && (best == null || m.tick() > best.tick())) {
                best = m;
            }
        }
        if (best != null) {
            session.seek(best.tick());
        }
    }

    private static Entity targeted(Minecraft mc) {
        HitResult hit = mc.hitResult;
        return hit instanceof EntityHitResult entityHit && hit.getType() == HitResult.Type.ENTITY ? entityHit.getEntity() : null;
    }
}
