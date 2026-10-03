package dev.kinora.mc.adapter;

import dev.kinora.core.format.RecordKind;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;

/**
 * Turns packets into the bytes Kinora stores and back, through the protocol's own codec.
 *
 * <p>A PACKET record payload is {@code u8 phase} followed by one frame: VarInt packet id, then the
 * body, exactly what {@code PacketDecoder} consumes.
 */
public final class PacketIO {
    private PacketIO() {}

    /** Kinora phase byte for a protocol, or 0 if Kinora does not record that protocol. */
    public static int phaseOf(ConnectionProtocol protocol) {
        return switch (protocol) {
            case CONFIGURATION -> RecordKind.PHASE_CONFIGURATION;
            case PLAY -> RecordKind.PHASE_PLAY;
            default -> 0;
        };
    }

    /** Record payload for a frame: phase byte, then the frame bytes. */
    public static byte[] recordPayload(int phase, ByteBuf frame) {
        int length = frame.readableBytes();
        byte[] out = new byte[length + 1];
        out[0] = (byte) phase;
        frame.getBytes(frame.readerIndex(), out, 1, length);
        return out;
    }

    /** Encodes a packet with the given protocol and returns a record payload for it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static byte[] encodeRecord(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buf = Unpooled.buffer(256);
        try {
            buf.writeByte(phaseOf(protocol.id()));
            ((StreamCodec) protocol.codec()).encode(buf, packet);
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    /** Decodes the frame part of a record payload. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Packet<?> decode(ProtocolInfo<?> protocol, byte[] recordPayload) {
        ByteBuf buf = Unpooled.wrappedBuffer(recordPayload, 1, recordPayload.length - 1);
        try {
            return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buf);
        } finally {
            buf.release();
        }
    }

    /** The VarInt packet id at the start of a record payload's frame. */
    public static int packetId(byte[] recordPayload) {
        int value = 0;
        for (int i = 1, shift = 0; i < recordPayload.length && shift < 35; i++, shift += 7) {
            int b = recordPayload[i];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        return -1;
    }
}
