package dev.kinora.core.format;

import java.util.Objects;

/**
 * One timestamped entry of the replay stream.
 *
 * <p>The stream is a single totally ordered sequence; a record's position in it is its
 * <em>ordinal</em> (0-based, implicit, never stored per record). Ticks and nanoseconds are
 * non-decreasing along the stream. The payload layout depends on {@link #kind()} and, for the
 * Minecraft-specific kinds, is defined by kinora-mc rather than here.
 *
 * @param kind    one of {@link RecordKind}
 * @param tick    client tick counter since the recording started; the record takes effect before
 *                that tick runs during playback
 * @param nanos   monotonic nanoseconds since the recording started, for sub-tick timing
 * @param payload kind-specific bytes; not copied, so callers must not mutate it afterwards
 */
public record StreamRecord(int kind, long tick, long nanos, byte[] payload) {
    public StreamRecord {
        if (kind < 0 || kind > 255) {
            throw new IllegalArgumentException("record kind out of range: " + kind);
        }
        if (tick < 0 || nanos < 0) {
            throw new IllegalArgumentException("negative timestamp: tick " + tick + " nanos " + nanos);
        }
        Objects.requireNonNull(payload, "payload");
    }

    /** Same record, re-timed: used when a snapshot's state is laid down at one instant. */
    public StreamRecord at(long newTick, long newNanos) {
        return new StreamRecord(kind, newTick, newNanos, payload);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StreamRecord r && r.kind == kind && r.tick == tick && r.nanos == nanos
                && java.util.Arrays.equals(r.payload, payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, tick, nanos, java.util.Arrays.hashCode(payload));
    }

    @Override
    public String toString() {
        return "StreamRecord[" + RecordKind.name(kind) + " tick=" + tick + " nanos=" + nanos + " bytes=" + payload.length + "]";
    }
}
