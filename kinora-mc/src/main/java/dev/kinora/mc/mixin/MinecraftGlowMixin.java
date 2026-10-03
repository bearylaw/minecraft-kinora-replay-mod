package dev.kinora.mc.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dev.kinora.mc.hooks.SceneHooks;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws the glowing outline around entities the user highlighted in a replay. Setting the
 * entity's own glowing flag would be overwritten by the next recorded data packet. See
 * docs/mixins.md.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftGlowMixin {
    @ModifyReturnValue(method = "shouldEntityAppearGlowing", at = @At("RETURN"))
    private boolean kinora$highlight(boolean original, Entity entity) {
        return original || SceneHooks.glowing(entity);
    }
}
