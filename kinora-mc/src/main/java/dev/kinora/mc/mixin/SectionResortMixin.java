package dev.kinora.mc.mixin;

import dev.kinora.mc.hooks.RenderHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts translucent re-sorts as they are queued, so a render waits for them like for chunk
 * builds (TerrainReadiness): a task can be queued and finish within one frame, before its result
 * is drawn. See docs/mixins.md.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public abstract class SectionResortMixin {
    @Inject(method = "resortTransparency", at = @At("HEAD"))
    private void kinora$countResort(CallbackInfo ci) {
        RenderHooks.resortScheduled();
    }
}
