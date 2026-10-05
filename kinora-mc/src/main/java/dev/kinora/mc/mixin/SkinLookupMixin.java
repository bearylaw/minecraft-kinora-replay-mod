package dev.kinora.mc.mixin;

import dev.kinora.mc.hooks.SceneHooks;

import net.minecraft.client.resources.SkinManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Shows recorded players' own skins in a replay. Vanilla wants a signed skin for every player but
 * the local one, and the recorded player is not the local player in a replay; where the signature
 * cannot be checked (an offline or development client) everybody would wear a default skin. The
 * texture URLs are still held to Mojang's domains by authlib. See docs/mixins.md.
 */
@Mixin(SkinManager.class)
public abstract class SkinLookupMixin {
    @ModifyVariable(method = "createLookup", at = @At("HEAD"), argsOnly = true)
    private boolean kinora$recordedSkins(boolean requireSecure) {
        return SceneHooks.requireSecureSkin(requireSecure);
    }
}
