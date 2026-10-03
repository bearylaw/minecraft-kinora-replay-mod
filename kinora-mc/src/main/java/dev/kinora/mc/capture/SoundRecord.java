package dev.kinora.mc.capture;

import dev.kinora.core.io.ByteSink;
import dev.kinora.core.io.ByteSource;

/**
 * A SOUND record: a sound the client started, resolved to the exact file that played. Layout in
 * {@code docs/format.md}.
 *
 * @param event               sound event id, e.g. {@code minecraft:block.stone.break}
 * @param file                resolved sound location (without {@code sounds/} and {@code .ogg}), e.g. {@code minecraft:dig/stone1}
 * @param category            sound source name, e.g. {@code blocks}
 * @param volume              instance volume (before category volume)
 * @param pitch               instance pitch (playback rate)
 * @param attenuationDistance distance at which linear attenuation reaches zero
 * @param flags               {@link #RELATIVE}, {@link #LOOPING}, {@link #LOCAL}, {@link #LINEAR}, {@link #STREAMED}
 * @param seed                seed of the instance's random source, or 0 if unknown
 */
public record SoundRecord(String event, String file, String category, double x, double y, double z, float volume, float pitch,
                          int attenuationDistance, int flags, long seed) {
    public static final int RELATIVE = 1;
    public static final int LOOPING = 1 << 1;
    /** Not caused by a sound packet: playback must play it from this record. */
    public static final int LOCAL = 1 << 2;
    /** Linear distance attenuation (otherwise none). */
    public static final int LINEAR = 1 << 3;
    public static final int STREAMED = 1 << 4;

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public byte[] encode() {
        ByteSink out = new ByteSink(96);
        out.writeString(event).writeString(file).writeString(category);
        out.writeDouble(x).writeDouble(y).writeDouble(z);
        out.writeFloat(volume).writeFloat(pitch);
        out.writeVarInt(attenuationDistance);
        out.writeByte(flags);
        out.writeVarLong(seed);
        return out.toByteArray();
    }

    public static SoundRecord decode(byte[] payload) {
        ByteSource in = new ByteSource(payload);
        return new SoundRecord(in.readString(), in.readString(), in.readString(), in.readDouble(), in.readDouble(), in.readDouble(),
                in.readFloat(), in.readFloat(), in.readVarInt(), in.readUnsignedByte(), in.readVarLong());
    }
}
