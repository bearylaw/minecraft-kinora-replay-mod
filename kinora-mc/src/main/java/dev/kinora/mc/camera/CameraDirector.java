package dev.kinora.mc.camera;

import dev.kinora.core.camera.CameraState;
import dev.kinora.mc.KinoraConfig;
import dev.kinora.mc.hooks.CameraHooks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * Decides, every frame, where the replay camera is. Free flight is computed per frame from real
 * time, so it stays smooth while the replay is paused or in slow motion; the other modes follow
 * entities at the frame's partial tick.
 *
 * <p>Rotation in free mode is the camera player's rotation, which vanilla turns from mouse input
 * every frame. Position is Kinora's own, in double precision.
 */
public final class CameraDirector implements CameraHooks.Source {
    public enum Mode { FREE, SPECTATE, ORBIT, PATH }

    /** Something that can drive the camera completely (the timeline). */
    public interface PathSource {
        @Nullable CameraState camera(float partialTick);
    }

    private Mode mode = Mode.FREE;
    private double x;
    private double y;
    private double z;
    private double roll;
    private double fov = Double.NaN;
    private double vx;
    private double vy;
    private double vz;
    private double speedMultiplier = 1.0;
    private long lastNanos = -1;
    private int targetEntity = Integer.MIN_VALUE;
    private double orbitDistance = 6.0;
    private double orbitYaw;
    private double orbitPitch = 20.0;
    private boolean positioned;
    private @Nullable PathSource path;
    private @Nullable CameraState last;
    private boolean flyingInPath;
    /** Movement input from a screen that flies the camera itself (the editor's right-drag). */
    private double inputForward;
    private double inputStrafe;
    private double inputUp;
    private boolean inputBoost;
    private boolean screenInput;
    /** The camera player the view direction was last applied to. */
    private @Nullable LocalPlayer boundPlayer;

    public Mode mode() {
        return mode;
    }

    /** Free flight from wherever the camera is now. */
    public void free() {
        CameraState current = last;
        mode = Mode.FREE;
        restoreCameraEntity();
        if (current != null) {
            placeAt(current);
        }
    }

    public void spectate(int entityId) {
        mode = Mode.SPECTATE;
        targetEntity = entityId;
        // Seen from inside, an entity's own model fills the view; vanilla hides the camera entity,
        // and gives spectators the creeper/spider/enderman views, so look through it.
        Minecraft mc = Minecraft.getInstance();
        Entity target = mc.level == null ? null : mc.level.getEntity(entityId);
        if (target != null) {
            mc.setCameraEntity(target);
        }
    }

    private void restoreCameraEntity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.getCameraEntity() != mc.player) {
            mc.setCameraEntity(mc.player);
        }
    }

    public void orbit(int entityId) {
        restoreCameraEntity();
        mode = Mode.ORBIT;
        targetEntity = entityId;
        CameraState current = last;
        if (current != null) {
            orbitYaw = current.yaw();
            orbitPitch = current.pitch();
        }
    }

    public void path(PathSource source) {
        restoreCameraEntity();
        mode = Mode.PATH;
        path = source;
    }

    public int target() {
        return targetEntity;
    }

    public double speedMultiplier() {
        return speedMultiplier;
    }

    /** Scroll-wheel speed change: each notch multiplies by about 1.25. */
    public void adjustSpeed(double notches) {
        speedMultiplier = Math.max(0.02, Math.min(50.0, speedMultiplier * Math.pow(1.25, notches)));
        if (mode == Mode.ORBIT) {
            orbitDistance = Math.max(1.0, Math.min(200.0, orbitDistance / Math.pow(1.15, notches)));
        }
    }

    public void adjustRoll(double degrees) {
        roll = Math.max(-180, Math.min(180, roll + degrees));
    }

    public void resetRoll() {
        roll = 0;
    }

    public void setFov(double degrees) {
        fov = Math.max(1, Math.min(170, degrees));
    }

    public void resetFov() {
        fov = Double.NaN;
    }

    /** Puts the free camera at a state (used when switching modes, or "go to keyframe"). */
    public void placeAt(CameraState state) {
        x = state.x();
        y = state.y();
        z = state.z();
        roll = state.roll();
        if (!Double.isNaN(state.fov())) {
            fov = state.fov();
        }
        positioned = true;
        vx = vy = vz = 0;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.setYRot((float) state.yaw());
            player.setXRot((float) state.pitch());
            player.yRotO = player.getYRot();
            player.xRotO = player.getXRot();
        }
    }

    public @Nullable CameraState last() {
        return last;
    }

    @Override
    public @Nullable CameraState camera(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return null;
        }
        long now = System.nanoTime();
        double dt = lastNanos < 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        double baseFov = Double.isNaN(fov) ? mc.options.fov().get() : fov;
        if (player != boundPlayer) {
            // A seek rebuilt the world with a new camera player: give it the view direction it had.
            boundPlayer = player;
            CameraState before = last;
            if (positioned && before != null) {
                player.setYRot((float) before.yaw());
                player.setXRot((float) before.pitch());
                player.yRotO = player.getYRot();
                player.xRotO = player.getXRot();
            }
        }
        if (!positioned) {
            // Start where the recording player is, not at the camera player's spawn point.
            var session = dev.kinora.mc.playback.ReplayManager.INSTANCE.session();
            Entity puppet = session == null ? null : session.puppet();
            if (puppet == null || session.phase() != dev.kinora.mc.playback.ReplaySession.Phase.PLAYING || session.fastForwarding()) {
                return null;
            }
            // A few blocks behind and above the player, looking past them: the player's own head
            // would otherwise fill the view.
            Vec3 eye = puppet.getEyePosition();
            double yawRad = Math.toRadians(puppet.getYRot());
            player.setYRot(puppet.getYRot());
            player.setXRot(15);
            x = eye.x + Math.sin(yawRad) * 3;
            y = eye.y + 1;
            z = eye.z - Math.cos(yawRad) * 3;
            positioned = true;
        }
        CameraState state = switch (mode) {
            case FREE -> fly(mc, player, dt, baseFov);
            case SPECTATE -> {
                Entity target = mc.level.getEntity(targetEntity);
                if (target == null) {
                    restoreCameraEntity();
                    yield fly(mc, player, dt, baseFov);
                }
                if (mc.getCameraEntity() != target) {
                    // The level was rebuilt (seek, respawn): the entity is a new object.
                    mc.setCameraEntity(target);
                }
                Vec3 eye = target.getEyePosition(partialTick);
                yield new CameraState(eye.x, eye.y, eye.z, target.getViewYRot(partialTick), target.getViewXRot(partialTick), roll, baseFov);
            }
            case ORBIT -> {
                Entity target = mc.level.getEntity(targetEntity);
                if (target == null) {
                    yield fly(mc, player, dt, baseFov);
                }
                // Mouse turns the orbit.
                orbitYaw = player.getYRot();
                orbitPitch = Math.max(-89, Math.min(89, player.getXRot()));
                Vec3 center = target.getPosition(partialTick).add(0, target.getBbHeight() * 0.6, 0);
                CameraState look = new CameraState(0, 0, 0, orbitYaw, orbitPitch, roll, baseFov);
                var forward = look.forward();
                yield new CameraState(center.x - forward.x() * orbitDistance, center.y - forward.y() * orbitDistance,
                        center.z - forward.z() * orbitDistance, orbitYaw, orbitPitch, roll, baseFov);
            }
            case PATH -> {
                CameraState fromPath = path == null ? null : path.camera(partialTick);
                flyingInPath = fromPath == null;
                yield fromPath != null ? fromPath : fly(mc, player, dt, baseFov);
            }
        };
        last = state;
        if (mode != Mode.FREE) {
            x = state.x();
            y = state.y();
            z = state.z();
            if (mode != Mode.ORBIT && !flyingInPath) {
                // So that flying off from here starts with this view.
                player.setYRot((float) state.yaw());
                player.setXRot((float) state.pitch());
                roll = state.roll();
            }
        }
        return state;
    }

    /** Movement from a screen (editor right-drag): -1..1 per axis. Call every frame while held. */
    public void screenInput(double forward, double strafe, double up, boolean boost) {
        inputForward = forward;
        inputStrafe = strafe;
        inputUp = up;
        inputBoost = boost;
        screenInput = true;
    }

    public void endScreenInput() {
        screenInput = false;
        inputForward = 0;
        inputStrafe = 0;
        inputUp = 0;
    }

    private CameraState fly(Minecraft mc, LocalPlayer player, double dt, double baseFov) {
        boolean input = mc.gui.screen() == null && mc.mouseHandler.isMouseGrabbed();
        double forward = 0;
        double strafe = 0;
        double up = 0;
        boolean boost = false;
        if (input) {
            forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
            strafe = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
            up = (mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0);
            boost = mc.options.keySprint.isDown();
        } else if (screenInput) {
            forward = inputForward;
            strafe = inputStrafe;
            up = inputUp;
            boost = inputBoost;
        }
        double yaw = player.getYRot();
        double pitch = player.getXRot();
        double speed = KinoraConfig.freecamSpeed() * speedMultiplier * (boost ? 3.0 : 1.0);
        double yr = Math.toRadians(yaw);
        // Horizontal movement follows the view's heading, vertical is world up: how filming rigs fly.
        double fx = -Math.sin(yr);
        double fz = Math.cos(yr);
        double sx = Math.cos(yr);
        double sz = Math.sin(yr);
        double len = Math.sqrt(forward * forward + strafe * strafe + up * up);
        double tx = 0;
        double ty = 0;
        double tz = 0;
        if (len > 0) {
            tx = (fx * forward + sx * strafe) / len * speed;
            ty = up / len * speed;
            tz = (fz * forward + sz * strafe) / len * speed;
        }
        // Exponential approach: smooth starts and stops, independent of frame rate.
        double response = 1.0 - Math.exp(-dt * 10.0);
        vx += (tx - vx) * response;
        vy += (ty - vy) * response;
        vz += (tz - vz) * response;
        x += vx * dt;
        y += vy * dt;
        z += vz * dt;
        return new CameraState(x, y, z, yaw, pitch, roll, baseFov);
    }

    /** Keeps the camera player where the camera is, so player-centred effects follow the view. */
    public void syncPlayer() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        CameraState state = last;
        if (player == null || state == null) {
            return;
        }
        double eye = player.getEyeHeight();
        player.setPos(state.x(), state.y() - eye, state.z());
        player.setDeltaMovement(Vec3.ZERO);
        if (mode != Mode.FREE && mode != Mode.ORBIT) {
            player.setYRot((float) state.yaw());
            player.setXRot((float) state.pitch());
        }
    }

    /** Forget position so the next frame starts at the camera player's eye (new replay). */
    public void reset() {
        mode = Mode.FREE;
        positioned = false;
        roll = 0;
        fov = Double.NaN;
        vx = vy = vz = 0;
        lastNanos = -1;
        last = null;
        path = null;
        speedMultiplier = 1.0;
    }
}
