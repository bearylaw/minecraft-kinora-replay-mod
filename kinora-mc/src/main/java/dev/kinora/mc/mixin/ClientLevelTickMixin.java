package dev.kinora.mc.mixin;

import dev.kinora.mc.hooks.TimeHooks;

import net.minecraft.client.multiplayer.ClientLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a replay restores its world (after opening or a seek), entities do not tick. Loading takes
 * a varying number of vanilla ticks; entities ticking through them would start the replay with
 * different animation state each time (tick counts, walk cycles), and renders would differ. See
 * docs/mixins.md.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelTickMixin {
    @Inject(method = "tickEntities", at = @At("HEAD"), cancellable = true)
    private void kinora$frozenWhileRestoring(CallbackInfo ci) {
        if (TimeHooks.entitiesFrozen()) {
            ci.cancel();
        }
    }
}
