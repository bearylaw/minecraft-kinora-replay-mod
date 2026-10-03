package dev.kinora.core.format;

import dev.kinora.core.io.ByteSink;
import dev.kinora.core.io.ByteSource;
import dev.kinora.core.io.MalformedDataException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The payload of a SNAP chunk: enough of the stream to rebuild the client's state at one tick
 * without replaying everything before it.
 *
 * <p>Applying a snapshot means: start a fresh playback session, apply {@link #entries()} in order
 * (inline records directly, references by reading the referenced record), then continue the
 * stream at {@link #resumeOrdinal()}. A snapshot is mostly references to earlier records (the
 * last chunk data packet of every loaded chunk, the spawn packet of every live entity, ...), so it
 * stays small on disk. Records that summarise state (an entity's current position) are inline.
 *
 * <pre>
 * varlong tick             the tick the state corresponds to
 * varlong nanos
 * varlong resumeOrdinal    first record to replay after the entries
 * varint  count
 * count x {
 *   u8 entryType           0 = inline, 1 = reference
 *   inline:    u8 kind, varint length, bytes payload
 *   reference: varlong ordinal
 * }
 * </pre>
 */
public record Snapshot(long tick, long nanos, long resumeOrdinal, List<Entry> entries) {
    public Snapshot {
        entries = Collections.unmodifiableList(entries);
    }

    /** One step of a snapshot. */
    public sealed interface Entry permits Inline, Reference {}

    /** A record whose bytes live in the snapshot itself. It takes effect at the snapshot's tick. */
    public record Inline(int kind, byte[] payload) implements Entry {}

    /** A record already in the stream, by ordinal. It must precede {@link #resumeOrdinal()}. */
    public record Reference(long ordinal) implements Entry {}

    public byte[] encode() {
        ByteSink out = new ByteSink(64 + entries.size() * 6);
        out.writeVarLong(tick);
        out.writeVarLong(nanos);
        out.writeVarLong(resumeOrdinal);
        out.writeVarInt(entries.size());
        for (Entry entry : entries) {
            switch (entry) {
                case Inline inline -> {
                    out.writeByte(0);
                    out.writeByte(inline.kind());
                    out.writeByteArray(inline.payload());
                }
                case Reference reference -> {
                    if (reference.ordinal() >= resumeOrdinal) {
                        throw new IllegalArgumentException("snapshot references ordinal " + reference.ordinal()
                                + " at or after its resume point " + resumeOrdinal);
                    }
                    out.writeByte(1);
                    out.writeVarLong(reference.ordinal());
                }
            }
        }
        return out.toByteArray();
    }

    public static Snapshot decode(byte[] payload) {
        ByteSource in = new ByteSource(payload);
        long tick = in.readVarLong();
        long nanos = in.readVarLong();
        long resume = in.readVarLong();
        int count = in.readVarIntBounded(in.remaining() / 2);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int type = in.readUnsignedByte();
            switch (type) {
                case 0 -> {
                    int kind = in.readUnsignedByte();
                    entries.add(new Inline(kind, in.readByteArray()));
                }
                case 1 -> {
                    long ordinal = in.readVarLong();
                    if (ordinal >= resume) {
                        throw new MalformedDataException("snapshot reference " + ordinal + " is not before resume point " + resume);
                    }
                    entries.add(new Reference(ordinal));
                }
                default -> throw new MalformedDataException("unknown snapshot entry type " + type);
            }
        }
        if (in.hasRemaining()) {
            throw new MalformedDataException("trailing bytes after snapshot entries");
        }
        return new Snapshot(tick, nanos, resume, entries);
    }
}
