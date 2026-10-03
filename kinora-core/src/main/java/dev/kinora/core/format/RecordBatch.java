package dev.kinora.core.format;

import dev.kinora.core.io.ByteSink;
import dev.kinora.core.io.ByteSource;
import dev.kinora.core.io.MalformedDataException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The payload of a PKTS chunk: a run of consecutive stream records.
 *
 * <pre>
 * varlong firstOrdinal     ordinal of the first record in the whole stream
 * varlong baseTick
 * varlong baseNanos
 * varint  count
 * count x {
 *   u8      kind
 *   varlong tick delta      to the previous record (or baseTick), never negative
 *   zigzag  nanos delta     to the previous record (or baseNanos)
 *   varint  payload length
 *   bytes   payload
 * }
 * </pre>
 */
public record RecordBatch(long firstOrdinal, List<StreamRecord> records) {
    /** No single record may exceed this; a larger one would mean a corrupt length. */
    public static final int MAX_RECORD_PAYLOAD = 64 * 1024 * 1024;

    public RecordBatch {
        records = Collections.unmodifiableList(records);
    }

    public long firstTick() {
        return records.isEmpty() ? 0 : records.getFirst().tick();
    }

    public long lastTick() {
        return records.isEmpty() ? 0 : records.getLast().tick();
    }

    /** One past the last ordinal in this batch. */
    public long endOrdinal() {
        return firstOrdinal + records.size();
    }

    public StreamRecord byOrdinal(long ordinal) {
        long index = ordinal - firstOrdinal;
        if (index < 0 || index >= records.size()) {
            throw new IndexOutOfBoundsException("ordinal " + ordinal + " not in [" + firstOrdinal + ", " + endOrdinal() + ")");
        }
        return records.get((int) index);
    }

    public byte[] encode() {
        int estimate = 32;
        for (StreamRecord record : records) {
            estimate += record.payload().length + 16;
        }
        ByteSink out = new ByteSink(estimate);
        encodeInto(out, firstOrdinal, records);
        return out.toByteArray();
    }

    public static void encodeInto(ByteSink out, long firstOrdinal, List<StreamRecord> records) {
        long baseTick = records.isEmpty() ? 0 : records.getFirst().tick();
        long baseNanos = records.isEmpty() ? 0 : records.getFirst().nanos();
        out.writeVarLong(firstOrdinal);
        out.writeVarLong(baseTick);
        out.writeVarLong(baseNanos);
        out.writeVarInt(records.size());
        long tick = baseTick;
        long nanos = baseNanos;
        for (StreamRecord record : records) {
            if (record.tick() < tick) {
                throw new IllegalArgumentException("ticks must not decrease within a stream: " + record.tick() + " after " + tick);
            }
            out.writeByte(record.kind());
            out.writeVarLong(record.tick() - tick);
            out.writeSignedVarLong(record.nanos() - nanos);
            out.writeByteArray(record.payload());
            tick = record.tick();
            nanos = record.nanos();
        }
    }

    public static RecordBatch decode(byte[] payload) {
        ByteSource in = new ByteSource(payload);
        long firstOrdinal = in.readVarLong();
        long tick = in.readVarLong();
        long nanos = in.readVarLong();
        // Each record takes at least four bytes, which bounds the count a corrupt header can claim.
        int count = in.readVarIntBounded(in.remaining() / 4);
        List<StreamRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int kind = in.readUnsignedByte();
            tick += in.readVarLong();
            nanos += in.readSignedVarLong();
            int length = in.readVarIntBounded(Math.min(in.remaining(), MAX_RECORD_PAYLOAD));
            byte[] data = in.readBytes(length);
            if (tick < 0 || nanos < 0) {
                throw new MalformedDataException("record " + i + " has a negative timestamp");
            }
            records.add(new StreamRecord(kind, tick, nanos, data));
        }
        if (in.hasRemaining()) {
            throw new MalformedDataException(in.remaining() + " trailing bytes after the last record");
        }
        if (firstOrdinal < 0) {
            throw new MalformedDataException("negative first ordinal");
        }
        return new RecordBatch(firstOrdinal, records);
    }
}
