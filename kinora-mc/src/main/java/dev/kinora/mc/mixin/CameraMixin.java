package dev.kinora.mc.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dev.kinora.core.camera.CameraState;
import dev.kinora.mc.hooks.CameraHooks;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Places the camera exactly where a replay's camera is: double-precision position, yaw, pitch,
 * roll and field of view, applied after vanilla aligns the camera with its entity and before the
 * frustum and projection are built. NeoForge's {@code ViewportEvent.ComputeCameraAngles} sets
 * angles but not the position, and fires before the position is set. See docs/mixins.md.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot, float roll);

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
    private void kinora$applyReplayCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!CameraHooks.active()) {
            return;
        }
        CameraState state = CameraHooks.beginFrame(deltaTracker.getGameTimeDeltaPartialTick(true));
        if (state != null) {
            this.setPosition(new Vec3(state.x(), state.y(), state.z()));
            this.setRotation((float) state.yaw(), (float) state.pitch(), (float) state.roll());
            this.detached = false;
        }
    }

    @ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
    private float kinora$fov(float vanilla) {
        return CameraHooks.fov(vanilla);
    }
}
