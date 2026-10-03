package dev.kinora.mc.mixin;

import dev.kinora.mc.hooks.RenderHooks;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tiled renders: each tile shows its piece of the full frame, so the projection the world is drawn
 * with is scaled and shifted for that piece (see Views.tile). Culling keeps the whole frame's
 * frustum, which only draws a little more. See docs/mixins.md.
 */
@Mixin(Camera.class)
public abstract class CameraTileMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void kinora$tileProjection(CameraRenderState cameraState, float partialTicks, CallbackInfo ci) {
        double[] tile = RenderHooks.tile();
        if (tile == null) {
            return;
        }
        Matrix4f t = new Matrix4f();
        t.m00((float) tile[0]);
        t.m11((float) tile[0]);
        t.m30((float) tile[1]);
        t.m31((float) tile[2]);
        cameraState.projectionMatrix.set(t.mul(cameraState.projectionMatrix));
    }
}
