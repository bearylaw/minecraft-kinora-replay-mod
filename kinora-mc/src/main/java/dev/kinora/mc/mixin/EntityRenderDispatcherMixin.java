package dev.kinora.mc.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dev.kinora.mc.hooks.SceneHooks;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a replay hide entities (by category or one by one) without touching them: they keep
 * ticking, colliding and making sounds, they are only not drawn. NeoForge's render events fire
 * per renderer type with render states, not entities, and cannot cover every entity type. See
 * docs/mixins.md.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
    private <E extends Entity> boolean kinora$replayVisibility(boolean original, E entity) {
        return original && SceneHooks.visible(entity);
    }
}
