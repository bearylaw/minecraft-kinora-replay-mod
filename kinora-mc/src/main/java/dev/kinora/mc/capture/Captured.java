package dev.kinora.mc.capture;

import dev.kinora.core.format.StreamRecord;

/**
 * One captured record, as it sits in memory before (and after) being written.
 *
 * <p>The state model and the replay buffer hold these by reference. When the record is written to a
 * recording file it gets an ordinal in that file, remembered here together with the file's
 * {@code epoch}, so later snapshots of the same file can refer to it instead of copying it.
 *
 * @see StateModel
 */
public final class Captured {
    /** Record kind ({@link dev.kinora.core.format.RecordKind}). */
    public final int kind;
    /** Session tick when captured (completed client ticks since the connection opened). */
    public final long sessionTick;
    /** {@link System#nanoTime()} when captured. */
    public final long nanoTime;
    /** Record payload, exactly as it will be stored. */
    public final byte[] payload;

    private long ordinal = -1;
    private int epoch = -1;

    public Captured(int kind, long sessionTick, long nanoTime, byte[] payload) {
        this.kind = kind;
        this.sessionTick = sessionTick;
        this.nanoTime = nanoTime;
        this.payload = payload;
    }

    /** Ordinal in the recording with the given epoch, or -1 if it was not written there. */
    public long ordinalIn(int recordingEpoch) {
        return epoch == recordingEpoch ? ordinal : -1;
    }

    void written(int recordingEpoch, long ordinalInFile) {
        this.epoch = recordingEpoch;
        this.ordinal = ordinalInFile;
    }

    public StreamRecord toRecord(long tick, long nanos) {
        return new StreamRecord(kind, tick, nanos, payload);
    }

    /** Approximate heap cost, for memory accounting. */
    public int footprint() {
        return payload.length + 48;
    }
}
