package dev.kinora.mc.capture;

import dev.kinora.core.io.ByteSink;
import dev.kinora.core.io.ByteSource;
import dev.kinora.core.io.MalformedDataException;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CLIENT_STATE records: what the server never tells the recording player about itself. Layouts are
 * listed in {@code docs/format.md}; type numbers are stored in files and must never change.
 */
public sealed interface ClientState {
    int PLAYER = 1;
    int EQUIPMENT = 2;
    int SWING = 3;
    int VEHICLE = 4;
    int IDENTITY = 5;
    int ENTITY_DATA = 6;

    int type();

    /** Encodes this state; items need the registries to encode. */
    byte[] encode(RegistryAccess registries);

    /** Where the recording player was and looked at the end of a tick. */
    record Player(int entityId, double x, double y, double z, float yRot, float xRot, float yHeadRot, float yBodyRot,
                  boolean onGround, float fov, int cameraType) implements ClientState {
        @Override
        public int type() {
            return PLAYER;
        }

        @Override
        public byte[] encode(RegistryAccess registries) {
            ByteSink out = new ByteSink(64);
            out.writeByte(PLAYER);
            out.writeVarInt(entityId);
            out.writeDouble(x).writeDouble(y).writeDouble(z);
            out.writeFloat(yRot).writeFloat(xRot).writeFloat(yHeadRot).writeFloat(yBodyRot);
            out.writeBoolean(onGround);
            out.writeFloat(fov);
            out.writeByte(cameraType);
            return out.toByteArray();
        }
    }

    /** The recording player's equipment after it changed. */
    record Equipment(int entityId, List<Slot> slots) implements ClientState {
        public record Slot(EquipmentSlot slot, ItemStack stack) {}

        @Override
        public int type() {
            return EQUIPMENT;
        }

        @Override
        public byte[] encode(RegistryAccess registries) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(256), registries, ConnectionType.OTHER);
            try {
                buf.writeByte(EQUIPMENT);
                buf.writeVarInt(entityId);
                buf.writeVarInt(slots.size());
                for (Slot slot : slots) {
                    buf.writeByte(slot.slot().ordinal());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, slot.stack());
                }
                return ByteBufUtil.getBytes(buf);
            } finally {
                buf.release();
            }
        }
    }

    /** The recording player started a swing. */
    record Swing(int entityId, boolean offHand) implements ClientState {
        @Override
        public int type() {
            return SWING;
        }

        @Override
        public byte[] encode(RegistryAccess registries) {
            return new ByteSink(8).writeByte(SWING).writeVarInt(entityId).writeBoolean(offHand).toByteArray();
        }
    }

    /** The vehicle the recording player steers, which the server does not echo back to it. */
    record Vehicle(int entityId, double x, double y, double z, float yRot, float xRot) implements ClientState {
        @Override
        public int type() {
            return VEHICLE;
        }

        @Override
        public byte[] encode(RegistryAccess registries) {
            ByteSink out = new ByteSink(48);
            out.writeByte(VEHICLE);
            out.writeVarInt(entityId);
            out.writeDouble(x).writeDouble(y).writeDouble(z);
            out.writeFloat(yRot).writeFloat(xRot);
            return out.toByteArray();
        }
    }

    /** Who the recording player is; the first CLIENT_STATE of every recording. */
    record Identity(int entityId, UUID uuid, String name) implements ClientState {
        @Override
        public int type() {
            return IDENTITY;
        }

        @Override
        public byte[] encode(RegistryAccess registries) {
            ByteSink out = new ByteSink(48);
            out.writeByte(IDENTITY);
            out.writeVarInt(entityId);
            out.writeLong(uuid.getMostSignificantBits()).writeLong(uuid.getLeastSignificantBits());
            out.writeString(name);
            return out.toByteArray();
        }
    }

    /**
     * All of the recording player's synced values (skin layers, main hand, pose, crouching, flying...),
     * whenever one changes. The server only ever sends the player changes to its own values, so
     * without this the replay would start from defaults (no outer skin layer).
     */
    record EntityData(int entityId, List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> values) implements ClientState {
        @Override
        public int type() {
            return ENTITY_DATA;
        }

        @Override
        public byte[] encode(RegistryAccess registries) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(256), registries, ConnectionType.OTHER);
            try {
                buf.writeByte(ENTITY_DATA);
                net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buf,
                        new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(entityId, values));
                return ByteBufUtil.getBytes(buf);
            } finally {
                buf.release();
            }
        }
    }

    /** Decodes a CLIENT_STATE payload; unknown types return null so newer files still play. */
    static ClientState decode(byte[] payload, RegistryAccess registries) {
        if (payload.length == 0) {
            throw new MalformedDataException("empty client state");
        }
        int type = payload[0] & 0xFF;
        if (type == ENTITY_DATA) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), registries, ConnectionType.OTHER);
            try {
                buf.readByte();
                var packet = net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buf);
                return new EntityData(packet.id(), packet.packedItems());
            } finally {
                buf.release();
            }
        }
        if (type == EQUIPMENT) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), registries, ConnectionType.OTHER);
            try {
                buf.readByte();
                int entityId = buf.readVarInt();
                int count = buf.readVarInt();
                List<Equipment.Slot> slots = new ArrayList<>();
                EquipmentSlot[] values = EquipmentSlot.values();
                for (int i = 0; i < count; i++) {
                    int ordinal = buf.readUnsignedByte();
                    ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                    if (ordinal < values.length) {
                        slots.add(new Equipment.Slot(values[ordinal], stack));
                    }
                }
                return new Equipment(entityId, slots);
            } finally {
                buf.release();
            }
        }
        ByteSource in = new ByteSource(payload);
        in.readUnsignedByte();
        return switch (type) {
            case PLAYER -> new Player(in.readVarInt(), in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readBoolean(), in.readFloat(), in.readUnsignedByte());
            case SWING -> new Swing(in.readVarInt(), in.readBoolean());
            case VEHICLE -> new Vehicle(in.readVarInt(), in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat());
            case IDENTITY -> new Identity(in.readVarInt(), new UUID(in.readLong(), in.readLong()), in.readString());
            default -> null;
        };
    }
}
