package dev.kinora.mc.mixin;

import dev.kinora.mc.hooks.InputHooks;

import net.minecraft.client.KeyMapping;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In a replay, key mappings other than Kinora's and the camera's movement read as not pressed and
 * never clicked (see InputHooks), so other mods' bindings do not fire on the director's keys. See
 * docs/mixins.md.
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
    @Shadow
    private int clickCount;

    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void kinora$silentInReplays(CallbackInfoReturnable<Boolean> cir) {
        if (InputHooks.blocked((KeyMapping) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "consumeClick", at = @At("HEAD"), cancellable = true)
    private void kinora$noClicksInReplays(CallbackInfoReturnable<Boolean> cir) {
        if (InputHooks.blocked((KeyMapping) (Object) this)) {
            // Dropped, not kept: they must not fire all at once when the replay closes.
            clickCount = 0;
            cir.setReturnValue(false);
        }
    }
}
