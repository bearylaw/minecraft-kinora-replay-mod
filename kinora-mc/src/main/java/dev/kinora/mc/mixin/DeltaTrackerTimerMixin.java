package dev.kinora.mc.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dev.kinora.mc.hooks.TimeHooks;

import net.minecraft.client.DeltaTracker;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes the frame's partial tick exactly the replay clock's fraction, so everything that
 * interpolates (entities, particles, sky, clouds, weather) shows the replay at an exact time. The
 * timer has no setter for it. See docs/mixins.md.
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin {
    @ModifyReturnValue(method = "getGameTimeDeltaPartialTick", at = @At("RETURN"))
    private float kinora$partialTick(float vanilla) {
        return TimeHooks.partialTick(vanilla);
    }

    @ModifyReturnValue(method = "getGameTimeDeltaTicks", at = @At("RETURN"))
    private float kinora$deltaTicks(float vanilla) {
        return TimeHooks.deltaTicks(vanilla);
    }
}
