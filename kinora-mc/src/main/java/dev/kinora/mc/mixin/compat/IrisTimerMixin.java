package dev.kinora.mc.mixin.compat;

import dev.kinora.mc.hooks.RenderHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris (shaders): during an offline render, the shader clock ({@code frameTimeCounter},
 * {@code frameTime}) follows the video's time instead of the wall clock, so animated water, clouds
 * and wind look the same in every render and move at the right speed in the video. Inert when Iris is
 * not installed ({@code @Pseudo}, optional config). See docs/mixins.md.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
public abstract class IrisTimerMixin {
    @Shadow
    private float frameTimeCounter;

    @Shadow
    private float lastFrameTime;

    @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true, remap = false)
    private void kinora$renderClock(long frameStartNanos, CallbackInfo ci) {
        double seconds = RenderHooks.shaderSeconds();
        if (!Double.isNaN(seconds)) {
            // Iris wraps the counter at an hour; so does this.
            frameTimeCounter = (float) (seconds % 3600.0);
            lastFrameTime = (float) RenderHooks.shaderFrameSeconds();
            ci.cancel();
        }
    }
}
