package dev.kinora.mc.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dev.kinora.mc.hooks.RenderHooks;

import com.mojang.blaze3d.platform.FramerateLimitTracker;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lifts the frame cap during offline renders. Vanilla limits a minimised window to 10 fps and an
 * idle player to 30, which would make a render left running in the background crawl. See
 * docs/mixins.md.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitMixin {
    @ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
    private int kinora$noLimitWhileRendering(int vanilla) {
        return RenderHooks.framerateLimit(vanilla);
    }
}
