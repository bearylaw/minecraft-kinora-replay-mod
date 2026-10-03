package dev.kinora.mc.capture;

import dev.kinora.core.format.ReplayMetadata;
import dev.kinora.core.format.Snapshot;
import dev.kinora.core.recording.RecordingWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One recording file being written. Translates session time into the file's own timeline (tick 0
 * is the moment the recording started) and remembers which captured records went into the file,
 * so snapshots can refer to them.
 */
public final class ActiveRecording {
    private static final AtomicInteger EPOCHS = new AtomicInteger();

    private final RecordingWriter writer;
    private final int epoch = EPOCHS.incrementAndGet();
    private final long startSessionTick;
    private final long startNanoTime;
    private final ReplayMetadata metadata;
    private long lastTick;

    private ActiveRecording(RecordingWriter writer, long startSessionTick, long startNanoTime, ReplayMetadata metadata) {
        this.writer = writer;
        this.startSessionTick = startSessionTick;
        this.startNanoTime = startNanoTime;
        this.metadata = metadata;
    }

    static ActiveRecording start(Path path, ReplayMetadata metadata, long sessionTick, Consumer<RecordingWriter.FailureReason> onFailure) throws IOException {
        long now = System.nanoTime();
        RecordingWriter writer = RecordingWriter.start(path, metadata, RecordingWriter.Options.defaults(), onFailure);
        return new ActiveRecording(writer, sessionTick, now, metadata);
    }

    public int epoch() {
        return epoch;
    }

    public RecordingWriter writer() {
        return writer;
    }

    public ReplayMetadata metadata() {
        return metadata;
    }

    public long tickOf(long sessionTick) {
        return Math.max(0, sessionTick - startSessionTick);
    }

    public long nanosOf(long nanoTime) {
        return Math.max(0, nanoTime - startNanoTime);
    }

    /** Current position of the recording's timeline, in ticks. */
    public long currentTick(long sessionTick) {
        return tickOf(sessionTick);
    }

    /** Appends a captured record at its own capture time. Caller holds the session lock. */
    void append(Captured captured) {
        long tick = tickOf(captured.sessionTick);
        appendAt(captured, tick, nanosOf(captured.nanoTime));
    }

    /** Appends a captured record at an explicit time (state laid down at recording start). */
    void appendAt(Captured captured, long tick, long nanos) {
        long t = Math.max(tick, lastTick);
        long ordinal = writer.append(captured.kind, t, nanos, captured.payload);
        if (ordinal >= 0) {
            captured.written(epoch, ordinal);
            lastTick = t;
        }
    }

    /** Appends bytes that exist only in the file (a synthesized snapshot record). */
    void appendRaw(int kind, byte[] payload, long tick, long nanos) {
        long t = Math.max(tick, lastTick);
        if (writer.append(kind, t, nanos, payload) >= 0) {
            lastTick = t;
        }
    }

    /** Writes a SNAP chunk describing the model's state now. Caller holds the session lock. */
    void snapshot(List<StateModel.Item> items, long sessionTick, long nanoTime) {
        List<Snapshot.Entry> entries = new ArrayList<>(items.size());
        for (StateModel.Item item : items) {
            switch (item) {
                case StateModel.Ref ref -> {
                    long ordinal = ref.captured().ordinalIn(epoch);
                    entries.add(ordinal >= 0 ? new Snapshot.Reference(ordinal) : new Snapshot.Inline(ref.captured().kind, ref.captured().payload));
                }
                case StateModel.Synth synth -> entries.add(new Snapshot.Inline(synth.kind(), synth.payload()));
            }
        }
        writer.snapshot(new Snapshot(tickOf(sessionTick), nanosOf(nanoTime), writer.nextOrdinal(), entries));
    }

    public long lastTick() {
        return lastTick;
    }
}
