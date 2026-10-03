package dev.kinora.core.recording;

import dev.kinora.core.format.KinoraFileWriter;
import dev.kinora.core.format.Marker;
import dev.kinora.core.format.ModTrack;
import dev.kinora.core.format.RecordBatch;
import dev.kinora.core.format.RecordKind;
import dev.kinora.core.format.ReplayMetadata;
import dev.kinora.core.format.Snapshot;
import dev.kinora.core.format.StreamRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Streams a recording to disk on a background thread.
 *
 * <p>Any thread may {@link #append} records; each call returns the record's ordinal, and ordinals
 * are assigned in call order, so the stream is totally ordered across threads. Appending never
 * blocks on IO: records go into a queue bounded by bytes. If the disk falls so far behind that the
 * queue exceeds its budget, the recording is stopped cleanly and the failure reported, rather than
 * stalling the caller (often the network thread) or dropping data silently.
 *
 * <p>Records are grouped into PKTS chunks of about {@link Options#batchBytes} raw bytes or
 * {@link Options#batchTicks} ticks, whichever comes first. The file is synced to the device every
 * {@link Options#syncIntervalMillis}, so a crash loses at most that much.
 */
public final class RecordingWriter implements AutoCloseable {

    /** Tuning. The defaults suit a client recording. */
    public record Options(int batchBytes, int batchTicks, long syncIntervalMillis, long queueBudgetBytes) {
        public static Options defaults() {
            return new Options(256 * 1024, 20, 5_000, 256L * 1024 * 1024);
        }
    }

    /** Why a recording ended other than by {@link #finish}. */
    public enum FailureReason { QUEUE_OVERFLOW, IO_ERROR }

    private sealed interface Task permits RecordTask, SnapshotTask, TracksTask, ThumbnailTask, FlushTask, FinishTask {}

    private record RecordTask(StreamRecord record, long ordinal) implements Task {}

    private record SnapshotTask(Snapshot snapshot) implements Task {}

    private record TracksTask(List<ModTrack> tracks) implements Task {}

    private record ThumbnailTask(long tick, byte[] png) implements Task {}

    private record FlushTask() implements Task {}

    private record FinishTask(ReplayMetadata metadata) implements Task {}

    private final Options options;
    private final KinoraFileWriter file;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private record Queued(Task task, long bytes) {}

    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    private final List<Marker> markers = new ArrayList<>();
    private final Thread thread;
    private final Consumer<FailureReason> onFailure;
    private long queuedBytes;
    private long nextOrdinal;
    private long lastTick;
    private volatile boolean closed;
    private volatile FailureReason failure;
    private volatile IOException ioFailure;
    private volatile long writtenRecords;
    private volatile long peakQueuedBytes;

    // Writer-thread state.
    private final List<StreamRecord> pending = new ArrayList<>();
    private long pendingFirstOrdinal = -1;
    private int pendingBytes;
    private long lastSync = System.currentTimeMillis();

    private RecordingWriter(KinoraFileWriter file, Options options, Consumer<FailureReason> onFailure) {
        this.file = file;
        this.options = options;
        this.onFailure = onFailure;
        this.thread = Thread.ofPlatform().daemon().name("Kinora writer " + file.path().getFileName()).unstarted(this::run);
    }

    /**
     * Creates the file and writes its opening metadata.
     *
     * @param onFailure called once, on the writer thread, if the recording has to stop by itself
     */
    public static RecordingWriter start(Path path, ReplayMetadata metadata, Options options, Consumer<FailureReason> onFailure) throws IOException {
        KinoraFileWriter file = KinoraFileWriter.create(path, UUID.randomUUID(), metadata.startedAtMillis);
        try {
            metadata.complete = false;
            file.writeMetadata(metadata);
            file.sync();
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
        RecordingWriter writer = new RecordingWriter(file, options, onFailure);
        writer.thread.start();
        return writer;
    }

    public UUID fileId() {
        return file.header().fileId();
    }

    public Path path() {
        return file.path();
    }

    /**
     * Queues a record and returns its ordinal, or -1 if the recording has stopped. Ticks must not
     * decrease; a smaller tick than the previous record's is raised to it.
     */
    public long append(int kind, long tick, long nanos, byte[] payload) {
        lock.lock();
        try {
            if (closed) {
                return -1;
            }
            long t = Math.max(tick, lastTick);
            lastTick = t;
            long ordinal = nextOrdinal++;
            enqueue(new RecordTask(new StreamRecord(kind, t, nanos, payload), ordinal), payload.length + 24L);
            if (kind == RecordKind.MARKER) {
                markers.add(Marker.decode(t, nanos, payload));
            }
            return ordinal;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code action} while holding the append lock, so nothing else can append in between.
     * Snapshot builders use this to read state and the next ordinal consistently.
     */
    public <T> T locked(java.util.function.Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** The ordinal the next appended record will get. */
    public long nextOrdinal() {
        lock.lock();
        try {
            return nextOrdinal;
        } finally {
            lock.unlock();
        }
    }

    public long lastTick() {
        lock.lock();
        try {
            return lastTick;
        } finally {
            lock.unlock();
        }
    }

    /** Queues a snapshot. Its resume ordinal must not be beyond {@link #nextOrdinal()}. */
    public void snapshot(Snapshot snapshot) {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            if (snapshot.resumeOrdinal() > nextOrdinal) {
                throw new IllegalArgumentException("snapshot resumes at " + snapshot.resumeOrdinal() + " but only " + nextOrdinal + " records exist");
            }
            long size = 64;
            for (Snapshot.Entry e : snapshot.entries()) {
                size += e instanceof Snapshot.Inline i ? i.payload().length + 8 : 10;
            }
            enqueue(new SnapshotTask(snapshot), size);
        } finally {
            lock.unlock();
        }
    }

    public void modTracks(List<ModTrack> tracks) {
        lock.lock();
        try {
            if (!closed) {
                enqueue(new TracksTask(List.copyOf(tracks)), 64);
            }
        } finally {
            lock.unlock();
        }
    }

    public void thumbnail(long tick, byte[] png) {
        lock.lock();
        try {
            if (!closed) {
                enqueue(new ThumbnailTask(tick, png), png.length);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Asks the writer to write out the current batch now (e.g. before a snapshot is taken from disk). */
    public void flush() {
        lock.lock();
        try {
            if (!closed) {
                enqueue(new FlushTask(), 0);
            }
        } finally {
            lock.unlock();
        }
    }

    private void enqueue(Task task, long bytes) {
        queue.add(new Queued(task, bytes));
        queuedBytes += bytes;
        if (queuedBytes > peakQueuedBytes) {
            peakQueuedBytes = queuedBytes;
        }
        notEmpty.signal();
        if (queuedBytes > options.queueBudgetBytes && !(task instanceof FinishTask)) {
            fail(FailureReason.QUEUE_OVERFLOW);
        }
    }

    /** Must hold the lock. Stops accepting records and lets the writer finish what is queued. */
    private void fail(FailureReason reason) {
        if (failure == null) {
            failure = reason;
            closed = true;
            queue.add(new Queued(new FinishTask(null), 0));
            notEmpty.signal();
        }
    }

    /**
     * Stops accepting records, writes everything queued, then the closing metadata, marker table
     * and index. Blocks until done.
     */
    public void finish(ReplayMetadata metadata) throws IOException {
        lock.lock();
        try {
            if (!closed) {
                closed = true;
                queue.add(new Queued(new FinishTask(metadata), 0));
                notEmpty.signal();
            }
        } finally {
            lock.unlock();
        }
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while finishing the recording", e);
        }
        if (ioFailure != null) {
            throw ioFailure;
        }
    }

    /** Markers appended so far. */
    public List<Marker> markers() {
        lock.lock();
        try {
            return List.copyOf(markers);
        } finally {
            lock.unlock();
        }
    }

    public FailureReason failure() {
        return failure;
    }

    public boolean isOpen() {
        return !closed;
    }

    /** Bytes waiting to be written: a measure of how far the disk is behind. */
    public long queuedBytes() {
        lock.lock();
        try {
            return queuedBytes;
        } finally {
            lock.unlock();
        }
    }

    public long peakQueuedBytes() {
        return peakQueuedBytes;
    }

    public long writtenRecords() {
        return writtenRecords;
    }

    public long fileSize() {
        return file.size();
    }

    private void run() {
        ReplayMetadata finishMetadata = null;
        boolean finishing = false;
        try {
            while (!finishing) {
                Task task;
                lock.lock();
                try {
                    while (queue.isEmpty()) {
                        long wait = Math.max(1, options.syncIntervalMillis - (System.currentTimeMillis() - lastSync));
                        notEmpty.await(wait, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (queue.isEmpty()) {
                            break;
                        }
                    }
                    Queued next = queue.poll();
                    task = next == null ? null : next.task;
                    if (next != null) {
                        queuedBytes -= next.bytes;
                    }
                } finally {
                    lock.unlock();
                }
                if (task != null) {
                    switch (task) {
                        case RecordTask r -> add(r.record, r.ordinal);
                        case SnapshotTask s -> {
                            writePending();
                            file.writeSnapshot(s.snapshot);
                        }
                        case TracksTask t -> file.writeModTracks(t.tracks);
                        case ThumbnailTask t -> file.writeThumbnail(t.tick, t.png);
                        case FlushTask f -> writePending();
                        case FinishTask f -> {
                            finishing = true;
                            finishMetadata = f.metadata;
                        }
                    }
                }
                if (System.currentTimeMillis() - lastSync >= options.syncIntervalMillis) {
                    writePending();
                    file.sync();
                    lastSync = System.currentTimeMillis();
                }
            }
            // Drain anything queued after the finish marker raced in (cannot happen once closed, but cheap).
            writePending();
            if (finishMetadata == null) {
                // Stopped by a failure: the caller never supplied final metadata. Leave the file
                // unfinished but synced; recovery will close it.
                file.sync();
            } else {
                finishMetadata.endTick = Math.max(finishMetadata.endTick, lastTick());
                file.finish(finishMetadata, markers());
            }
        } catch (IOException e) {
            ioFailure = e;
            lock.lock();
            try {
                closed = true;
                if (failure == null) {
                    failure = FailureReason.IO_ERROR;
                }
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                file.close();
            } catch (IOException ignored) {
                // Nothing more to do; the data that reached the disk is what we have.
            }
            if (failure != null && onFailure != null) {
                onFailure.accept(failure);
            }
        }
    }

    private void add(StreamRecord record, long ordinal) throws IOException {
        if (pending.isEmpty()) {
            pendingFirstOrdinal = ordinal;
        } else if (record.tick() - pending.getFirst().tick() >= options.batchTicks) {
            writePending();
            pendingFirstOrdinal = ordinal;
        }
        pending.add(record);
        pendingBytes += record.payload().length + 8;
        writtenRecords++;
        if (pendingBytes >= options.batchBytes) {
            writePending();
        }
    }

    private void writePending() throws IOException {
        if (pending.isEmpty()) {
            return;
        }
        file.writeBatch(new RecordBatch(pendingFirstOrdinal, new ArrayList<>(pending)));
        pending.clear();
        pendingBytes = 0;
        pendingFirstOrdinal = -1;
    }

    /** Abandons the recording without finishing it (the file stays recoverable). */
    @Override
    public void close() {
        lock.lock();
        try {
            if (!closed) {
                closed = true;
                queue.add(new Queued(new FinishTask(null), 0));
                notEmpty.signal();
            }
        } finally {
            lock.unlock();
        }
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
