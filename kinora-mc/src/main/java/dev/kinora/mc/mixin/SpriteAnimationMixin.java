package dev.kinora.mc.mixin;

import dev.kinora.mc.hooks.TimeHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In a replay, an animated texture (water, lava, fire, portals) shows the frame that belongs to the
 * replay tick, rather than one that depends on how many ticks ran since the textures loaded,
 * including loading-screen ticks whose count varies with machine speed. Without this, two renders of
 * the same shot differ wherever water is visible. The animation's cycle length is found once by
 * running vanilla's own tick until it wraps, so no private types are needed. See docs/mixins.md.
 */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimationState")
public abstract class SpriteAnimationMixin {
    @Shadow
    private int frame;

    @Shadow
    private int subFrame;

    @Shadow
    private boolean isDirty;

    @Shadow
    public abstract void tick();

    @Unique
    private int kinora$cycle = -1;

    @Unique
    private long kinora$shownTick = Long.MIN_VALUE;

    @Unique
    private boolean kinora$stepping;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kinora$replayPhase(CallbackInfo ci) {
        if (kinora$stepping) {
            return;
        }
        long replayTick = TimeHooks.textureTicks();
        if (replayTick < 0) {
            kinora$shownTick = Long.MIN_VALUE;
            return;
        }
        ci.cancel();
        if (replayTick == kinora$shownTick) {
            return;
        }
        kinora$stepping = true;
        try {
            if (kinora$cycle < 0) {
                frame = 0;
                subFrame = 0;
                int n = 0;
                do {
                    tick();
                    n++;
                } while ((frame != 0 || subFrame != 0) && n < 1_000_000);
                kinora$cycle = n;
            }
            frame = 0;
            subFrame = 0;
            long steps = Math.floorMod(replayTick, kinora$cycle);
            for (long i = 0; i < steps; i++) {
                tick();
            }
            // Upload the frame now shown, whatever was shown before.
            isDirty = true;
            kinora$shownTick = replayTick;
        } finally {
            kinora$stepping = false;
        }
    }
}
