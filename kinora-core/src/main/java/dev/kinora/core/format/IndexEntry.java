package dev.kinora.core.format;

import dev.kinora.core.io.ByteSink;
import dev.kinora.core.io.ByteSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Where one chunk lives and what it covers.
 *
 * @param type          chunk FourCC
 * @param sequence      chunk sequence number
 * @param offset        file offset of the chunk header
 * @param flags         chunk flags
 * @param storedLength  stored payload length
 * @param firstTick     PKTS: tick of the first record; SNAP and THMB: their tick; otherwise 0
 * @param lastTick      PKTS: tick of the last record; SNAP and THMB: their tick; otherwise 0
 * @param firstOrdinal  PKTS: ordinal of the first record; SNAP: resume ordinal; otherwise 0
 * @param recordCount   PKTS: number of records; otherwise 0
 */
public record IndexEntry(int type, long sequence, long offset, int flags, int storedLength,
                         long firstTick, long lastTick, long firstOrdinal, int recordCount) {

    public String typeName() {
        return KinoraFormat.fourCCName(type);
    }

    public long endOrdinal() {
        return firstOrdinal + recordCount;
    }

    public long endOffset() {
        return offset + KinoraFormat.CHUNK_HEADER_SIZE + storedLength;
    }

    /** INDX payload: varint count, then per entry u32 type, varlongs, varint, varlongs. */
    public static byte[] encodeIndex(List<IndexEntry> entries) {
        ByteSink out = new ByteSink(16 + entries.size() * 24);
        out.writeVarInt(entries.size());
        for (IndexEntry e : entries) {
            out.writeInt(e.type);
            out.writeVarLong(e.sequence);
            out.writeVarLong(e.offset);
            out.writeVarInt(e.flags);
            out.writeVarInt(e.storedLength);
            out.writeVarLong(e.firstTick);
            out.writeVarLong(e.lastTick);
            out.writeVarLong(e.firstOrdinal);
            out.writeVarInt(e.recordCount);
        }
        return out.toByteArray();
    }

    public static List<IndexEntry> decodeIndex(byte[] payload) {
        ByteSource in = new ByteSource(payload);
        int count = in.readVarIntBounded(in.remaining() / 8);
        List<IndexEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new IndexEntry(in.readInt(), in.readVarLong(), in.readVarLong(), in.readVarInt(),
                    in.readVarInt(), in.readVarLong(), in.readVarLong(), in.readVarLong(), in.readVarInt()));
        }
        return entries;
    }
}
