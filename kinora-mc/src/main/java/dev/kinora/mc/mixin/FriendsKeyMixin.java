package dev.kinora.mc.mixin;

import dev.kinora.mc.playback.ReplayManager;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the friends list shut in a replay. Its key (O) is checked as a global key before key mappings
 * are read, so {@link KeyMappingMixin} does not reach it, and it took the key from Kinora's orbit
 * camera. Left unhandled, the key goes on to Kinora. See docs/mixins.md.
 */
@Mixin(Minecraft.class)
public abstract class FriendsKeyMixin {
    @Inject(method = "toggleFriendsScreen", at = @At("HEAD"), cancellable = true)
    private void kinora$noFriendsInReplays(CallbackInfoReturnable<Boolean> cir) {
        if (ReplayManager.INSTANCE.active()) {
            cir.setReturnValue(false);
        }
    }
}
