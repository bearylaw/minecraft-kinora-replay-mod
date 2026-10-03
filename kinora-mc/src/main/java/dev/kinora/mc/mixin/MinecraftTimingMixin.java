package dev.kinora.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import dev.kinora.mc.hooks.TimeHooks;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Lets a replay drive the client's ticks from its own clock (pause, 0.01x to 10x, frame steps,
 * fast-forward, offline rendering at exact times). Timing is vanilla unless a replay has installed
 * a {@link TimeHooks.Source}. See docs/mixins.md.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftTimingMixin {
    /** Replaces the number of ticks the wall-clock timer asks for. */
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I"))
    private int kinora$replayTicks(DeltaTracker.Timer timer, long currentMs, Operation<Integer> original) {
        return TimeHooks.ticksForFrame(original.call(timer, currentMs));
    }

    /** Lifts the 10-ticks-per-frame cap while a replay fast-forwards. */
    @ModifyConstant(method = "runTick", constant = @Constant(intValue = 10))
    private int kinora$tickCap(int cap) {
        return TimeHooks.tickCap(cap);
    }

    /** Animates textures once per replay tick rather than once per frame. */
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"))
    private void kinora$textureTicks(TextureManager textures, Operation<Void> original, @Local(ordinal = 0) int ticksToDo) {
        int steps = TimeHooks.textureSteps(ticksToDo);
        for (int i = 0; i < steps; i++) {
            original.call(textures);
        }
    }
}
