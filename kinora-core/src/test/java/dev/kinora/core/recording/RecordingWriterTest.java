package dev.kinora.core.recording;

import dev.kinora.core.format.KinoraFile;
import dev.kinora.core.format.Marker;
import dev.kinora.core.format.RecordKind;
import dev.kinora.core.format.ReplayMetadata;
import dev.kinora.core.format.Snapshot;
import dev.kinora.core.format.StreamRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingWriterTest {
    @TempDir
    Path dir;

    @Test
    void recordsFromManyThreadsArriveInOrdinalOrder() throws Exception {
        Path path = dir.resolve("r.kinora");
        ReplayMetadata metadata = new ReplayMetadata();
        RecordingWriter writer = RecordingWriter.start(path, metadata, RecordingWriter.Options.defaults(), null);
        int threads = 4;
        int perThread = 5_000;
        List<long[]> appended = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread worker = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    byte[] payload = {(byte) id, (byte) i, (byte) (i >> 8)};
                    long ordinal = writer.append(RecordKind.PACKET, i / 50, i * 1000L, payload);
                    appended.add(new long[] {ordinal, id, i});
                }
            });
            workers.add(worker);
        }
        go.countDown();
        for (Thread w : workers) {
            w.join();
        }
        writer.append(RecordKind.MARKER, 1000, 0, new Marker(1000, 0, "end", 0xFF00FF00, "manual", 0).encode());
        writer.snapshot(new Snapshot(1000, 0, writer.nextOrdinal(), List.of(new Snapshot.Reference(0))));
        writer.finish(metadata);

        try (KinoraFile file = KinoraFile.open(path)) {
            assertTrue(file.finished());
            assertEquals(threads * perThread + 1L, file.endOrdinal());
            for (long[] a : appended) {
                StreamRecord r = file.record(a[0]);
                assertEquals((byte) a[1], r.payload()[0]);
                assertEquals((byte) a[2], r.payload()[1]);
            }
            long previousTick = -1;
            for (KinoraFile.RecordCursor c = file.cursor(0); c.hasNext(); ) {
                StreamRecord r = c.next();
                assertTrue(r.tick() >= previousTick, "ticks never decrease");
                previousTick = r.tick();
            }
            assertEquals(1, file.markers().size());
            assertEquals(1, file.snapshots().size());
        }
    }

    @Test
    void overflowStopsTheRecordingInsteadOfBlocking() throws Exception {
        Path path = dir.resolve("o.kinora");
        AtomicReference<RecordingWriter.FailureReason> failure = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);
        RecordingWriter writer = RecordingWriter.start(path, new ReplayMetadata(),
                new RecordingWriter.Options(256 * 1024, 20, 5000, 1024), reason -> {
                    failure.set(reason);
                    failed.countDown();
                });
        long rejected = 0;
        for (int i = 0; i < 10_000; i++) {
            if (writer.append(RecordKind.PACKET, 0, i, new byte[4096]) < 0) {
                rejected++;
            }
        }
        failed.await();
        assertEquals(RecordingWriter.FailureReason.QUEUE_OVERFLOW, failure.get());
        assertTrue(rejected > 0);
        assertFalse(writer.isOpen());
        // What was accepted before the overflow is on disk and recoverable.
        try (KinoraFile file = KinoraFile.open(path)) {
            assertFalse(file.finished());
            assertTrue(file.endOrdinal() > 0);
        }
    }
}
