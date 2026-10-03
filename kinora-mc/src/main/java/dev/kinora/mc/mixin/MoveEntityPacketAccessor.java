package dev.kinora.mc.mixin;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the entity id of a relative move packet, which the packet only offers through
 * {@code getEntity(Level)}. The snapshot model needs it on the network thread, where there is no
 * level to ask. See docs/mixins.md.
 */
@Mixin(ClientboundMoveEntityPacket.class)
public interface MoveEntityPacketAccessor {
    @Accessor("entityId")
    int kinora$entityId();
}
