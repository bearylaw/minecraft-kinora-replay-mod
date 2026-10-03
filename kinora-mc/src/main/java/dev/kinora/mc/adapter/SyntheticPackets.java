package dev.kinora.mc.adapter;

import com.mojang.authlib.GameProfile;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Packets playback makes up itself. Where Minecraft offers no client-side constructor, the wire
 * format is written and decoded with the packet's own codec, so the result is exactly what a
 * server would have sent.
 */
public final class SyntheticPackets {
    private SyntheticPackets() {}

    /** Adds a player to the tab list with a game mode, as {@code ClientboundPlayerInfoUpdatePacket} ADD_PLAYER does. */
    public static ClientboundPlayerInfoUpdatePacket addPlayerInfo(GameProfile profile, GameType mode, boolean listed, RegistryAccess registries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(128), registries, ConnectionType.OTHER);
        try {
            // Action writers run in enum order: ADD_PLAYER, (INITIALIZE_CHAT), UPDATE_GAME_MODE, UPDATE_LISTED.
            buf.writeEnumSet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED), ClientboundPlayerInfoUpdatePacket.Action.class);
            buf.writeVarInt(1);
            buf.writeUUID(profile.id());
            ByteBufCodecs.PLAYER_NAME.encode(buf, profile.name());
            ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(buf, profile.properties());
            buf.writeVarInt(mode.getId());
            buf.writeBoolean(listed);
            return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    /** Spawns a player entity (the puppet), which the client builds as a {@code RemotePlayer}. */
    public static ClientboundAddEntityPacket addPlayerEntity(int id, UUID uuid, double x, double y, double z, float yRot, float xRot, float headYaw) {
        return new ClientboundAddEntityPacket(id, uuid, x, y, z, xRot, yRot, EntityTypes.PLAYER, 0, Vec3.ZERO, headYaw);
    }
}
