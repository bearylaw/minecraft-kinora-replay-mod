package dev.kinora.mc.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import dev.kinora.mc.hooks.RenderHooks;

import net.minecraft.client.renderer.LevelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * During offline renders, every visible section with translucent geometry (water, glass) is
 * re-sorted for the camera each frame, not vanilla's rolling eighth. Otherwise a frame can be
 * captured with some sections sorted for an earlier camera position, and the same render differs
 * at a few translucent edges from run to run. The settle wait then covers the sorting. See
 * docs/mixins.md.
 */
@Mixin(LevelRenderer.class)
public abstract class TranslucentResortMixin {
    @ModifyExpressionValue(method = "scheduleTranslucentSectionResort", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"))
    private int kinora$resortAllWhileRendering(int vanilla) {
        return RenderHooks.rendering() ? Math.max(vanilla, ((LevelRenderer) (Object) this).visibleSections().size()) : vanilla;
    }
}
