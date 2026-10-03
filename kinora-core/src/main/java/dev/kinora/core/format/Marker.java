package dev.kinora.core.format;

import dev.kinora.core.io.ByteSink;
import dev.kinora.core.io.ByteSource;

import java.util.Objects;

/**
 * A named point on the replay timeline, dropped by hand while playing or detected automatically.
 *
 * @param tick     replay tick
 * @param nanos    replay nanoseconds, for sub-tick placement
 * @param name     what the user sees; may be empty
 * @param color    0xAARRGGBB
 * @param category free-form grouping key, e.g. {@code "manual"}, {@code "death"}, {@code "chat"}
 * @param source   {@link #SOURCE_MANUAL}, {@link #SOURCE_DETECTED} or {@link #SOURCE_EDITOR}
 */
public record Marker(long tick, long nanos, String name, int color, String category, int source) {
    public static final int SOURCE_MANUAL = 0;
    public static final int SOURCE_DETECTED = 1;
    public static final int SOURCE_EDITOR = 2;

    public Marker {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(category, "category");
    }

    /** Payload of a {@link RecordKind#MARKER} record; the timestamp lives on the record. */
    public byte[] encode() {
        ByteSink out = new ByteSink(32 + name.length() + category.length());
        writeBody(out);
        return out.toByteArray();
    }

    private void writeBody(ByteSink out) {
        out.writeString(name);
        out.writeInt(color);
        out.writeString(category);
        out.writeByte(source);
    }

    public static Marker decode(long tick, long nanos, byte[] payload) {
        ByteSource in = new ByteSource(payload);
        String name = in.readString();
        int color = in.readInt();
        String category = in.readString();
        int source = in.readUnsignedByte();
        return new Marker(tick, nanos, name, color, category, source);
    }

    /** Entry in a MARK table chunk: timestamp, then the record payload. */
    public void writeTableEntry(ByteSink out) {
        out.writeVarLong(tick);
        out.writeVarLong(nanos);
        writeBody(out);
    }

    public static Marker readTableEntry(ByteSource in) {
        long tick = in.readVarLong();
        long nanos = in.readVarLong();
        String name = in.readString();
        int color = in.readInt();
        String category = in.readString();
        int source = in.readUnsignedByte();
        return new Marker(tick, nanos, name, color, category, source);
    }
}
