package dev.kinora.mc.mixin;

import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the entity id of a head rotation packet, for the same reason as {@link MoveEntityPacketAccessor}. */
@Mixin(ClientboundRotateHeadPacket.class)
public interface RotateHeadPacketAccessor {
    @Accessor("entityId")
    int kinora$entityId();
}
