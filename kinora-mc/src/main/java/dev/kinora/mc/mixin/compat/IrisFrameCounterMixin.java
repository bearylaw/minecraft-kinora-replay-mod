package dev.kinora.mc.mixin.compat;

import dev.kinora.mc.hooks.RenderHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris (shaders): during an offline render, {@code frameCounter} counts output images rather than
 * game frames, so shaders that jitter or dither by frame number (TAA) do the same in every render.
 * See docs/mixins.md.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$FrameCounter", remap = false)
public abstract class IrisFrameCounterMixin {
    @Shadow
    private int count;

    @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true, remap = false)
    private void kinora$renderFrames(CallbackInfo ci) {
        long frame = RenderHooks.shaderFrameIndex();
        if (frame >= 0) {
            count = (int) (frame % 720720);
            ci.cancel();
        }
    }
}
