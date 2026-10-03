package dev.kinora.mc.mixin;

import dev.kinora.mc.hooks.RenderHooks;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the finished world image for offline renders: after the level, entity outlines and post
 * effects are drawn, before the GUI is drawn on top. No NeoForge event fires at this point
 * ({@code RenderLevelStageEvent.AfterLevel} comes before outlines and post effects). See docs/mixins.md.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCaptureMixin {
    @Shadow
    @Final
    private RenderTarget mainRenderTarget;

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void kinora$beforeGui(CallbackInfo ci) {
        RenderHooks.beforeGui(mainRenderTarget);
    }
}
