package dev.kinora.core.format;

import dev.kinora.core.io.ByteSink;
import dev.kinora.core.io.ByteSource;
import dev.kinora.core.io.MalformedDataException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatRoundTripTest {
    @TempDir
    Path dir;

    @Test
    void varintsRoundTripAtTheEdges() {
        long[] values = {0, 1, 127, 128, 16383, 16384, Integer.MAX_VALUE, 0xFFFFFFFFL, Long.MAX_VALUE, -1, Long.MIN_VALUE};
        ByteSink out = new ByteSink();
        for (long v : values) {
            out.writeVarLong(v);
            out.writeSignedVarLong(v);
        }
        ByteSource in = new ByteSource(out.toByteArray());
        for (long v : values) {
            assertEquals(v, in.readVarLong());
            assertEquals(v, in.readSignedVarLong());
        }
        assertFalse(in.hasRemaining());
    }

    @Test
    void truncatedDataIsAFormatErrorNotAnIndexError() {
        ByteSink out = new ByteSink();
        out.writeString("hello world");
        byte[] bytes = out.toByteArray();
        ByteSource in = new ByteSource(bytes, 0, bytes.length - 3);
        assertThrows(MalformedDataException.class, in::readString);
    }

    @Test
    void fourCCRoundTrips() {
        assertEquals("PKTS", KinoraFormat.fourCCName(KinoraFormat.TYPE_PKTS));
        assertEquals(KinoraFormat.fourCC("KCHK"), KinoraFormat.CHUNK_SYNC);
    }

    @Test
    void recordBatchRoundTrips() {
        List<StreamRecord> records = randomRecords(new Random(1), 500, 0);
        RecordBatch batch = new RecordBatch(1234, records);
        RecordBatch decoded = RecordBatch.decode(batch.encode());
        assertEquals(1234, decoded.firstOrdinal());
        assertEquals(records, decoded.records());
        assertEquals(records.getFirst().tick(), decoded.firstTick());
        assertEquals(records.getLast().tick(), decoded.lastTick());
    }

    @Test
    void snapshotRoundTripsAndRejectsForwardReferences() {
        Snapshot snapshot = new Snapshot(100, 5_000_000_000L, 42, List.of(
                new Snapshot.Reference(0), new Snapshot.Inline(RecordKind.PACKET, new byte[] {2, 9, 9}), new Snapshot.Reference(41)));
        Snapshot decoded = Snapshot.decode(snapshot.encode());
        assertEquals(snapshot.tick(), decoded.tick());
        assertEquals(snapshot.resumeOrdinal(), decoded.resumeOrdinal());
        assertEquals(3, decoded.entries().size());
        assertEquals(41, ((Snapshot.Reference) decoded.entries().get(2)).ordinal());
        assertArrayEquals(new byte[] {2, 9, 9}, ((Snapshot.Inline) decoded.entries().get(1)).payload());

        Snapshot bad = new Snapshot(1, 1, 5, List.of(new Snapshot.Reference(5)));
        assertThrows(IllegalArgumentException.class, bad::encode);
    }

    @Test
    void metadataKeepsUnknownKeys() {
        ReplayMetadata metadata = new ReplayMetadata();
        metadata.minecraftVersion = "26.2";
        metadata.mods.add(new ReplayMetadata.ModInfo("kinora", "0.1.0"));
        String json = metadata.toJson().replace("{", "{\n  \"fromTheFuture\": {\"a\": 1},");
        ReplayMetadata read = ReplayMetadata.fromJson(json);
        assertEquals("26.2", read.minecraftVersion);
        assertEquals("kinora", read.mods.getFirst().id());
        assertTrue(read.toJson().contains("fromTheFuture"));
    }

    @Test
    void finishedFileOpensThroughItsIndex() throws IOException {
        Path path = dir.resolve("a.kinora");
        List<StreamRecord> all = writeFile(path, true, 4);
        try (KinoraFile file = KinoraFile.open(path)) {
            assertTrue(file.finished());
            assertFalse(file.scanReport().scanned());
            assertEquals(all.size(), file.endOrdinal());
            List<StreamRecord> read = new ArrayList<>();
            file.cursor(0).forEachRemaining(read::add);
            assertEquals(all, read);
            assertEquals("26.2", file.metadata().minecraftVersion);
            assertTrue(file.metadata().complete);
            assertEquals(2, file.markers().size());
            assertEquals(2, file.snapshots().size());
            assertNotNull(file.thumbnail());
            assertEquals(1, file.modTracks().size());
            // Random access agrees with sequential access.
            for (int i = 0; i < all.size(); i += 37) {
                assertEquals(all.get(i), file.record(i));
            }
        }
    }

    @Test
    void unfinishedFileIsScannedAndRecovered() throws IOException {
        Path path = dir.resolve("crash.kinora");
        List<StreamRecord> all = writeFile(path, false, 4);
        // Simulate a crash mid-write: half a chunk of garbage at the end.
        Files.write(path, new byte[] {'K', 'C', 'H', 'K', 1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        try (KinoraFile file = KinoraFile.open(path)) {
            assertFalse(file.finished());
            assertTrue(file.scanReport().scanned());
            assertEquals(1, file.scanReport().damagedRanges().size());
            assertEquals(all.size(), file.endOrdinal());
            assertFalse(file.metadata().complete);
        }
        assertTrue(KinoraRecovery.needsRecovery(path));
        KinoraRecovery.Result result = KinoraRecovery.recover(path);
        assertFalse(result.wasFinished());
        assertEquals(7, result.discardedBytes());
        try (KinoraFile file = KinoraFile.open(path)) {
            assertTrue(file.finished());
            assertTrue(file.metadata().recovered);
            List<StreamRecord> read = new ArrayList<>();
            file.cursor(0).forEachRemaining(read::add);
            assertEquals(all, read);
            assertEquals(2, file.markers().size());
        }
        assertFalse(KinoraRecovery.needsRecovery(path));
    }

    @Test
    void damageInTheMiddleIsSkippedByResynchronising() throws IOException {
        Path path = dir.resolve("mid.kinora");
        List<StreamRecord> all = writeFile(path, false, 5);
        byte[] bytes = Files.readAllBytes(path);
        // Corrupt a byte inside the payload of the third PKTS chunk.
        List<IndexEntry> batches;
        try (KinoraFile file = KinoraFile.open(path)) {
            batches = file.batches();
        }
        IndexEntry victim = batches.get(2);
        bytes[(int) victim.offset() + KinoraFormat.CHUNK_HEADER_SIZE + 3] ^= 0x55;
        Files.write(path, bytes);
        try (KinoraFile file = KinoraFile.open(path)) {
            assertEquals(batches.size() - 1, file.batches().size());
            assertEquals(1, file.scanReport().damagedRanges().size());
            KinoraFile.RecordCursor cursor = file.cursor(0);
            List<StreamRecord> read = new ArrayList<>();
            cursor.forEachRemaining(read::add);
            assertEquals(all.size() - victim.recordCount(), read.size());
            assertEquals(victim.recordCount(), cursor.gapsSkipped());
            assertNull(file.batchContaining(victim.firstOrdinal()));
        }
        KinoraRecovery.recover(path);
        try (KinoraFile file = KinoraFile.open(path)) {
            assertTrue(file.finished());
            assertEquals(batches.size() - 1, file.batches().size());
        }
    }

    @Test
    void notAKinoraFileIsRejectedClearly() throws IOException {
        Path path = dir.resolve("x.kinora");
        Files.write(path, "hello, this is not a replay at all, it is text".getBytes());
        IOException e = assertThrows(IOException.class, () -> KinoraFile.open(path));
        assertTrue(e.getMessage().contains("not a Kinora replay"), e.getMessage());
    }

    @Test
    void unknownCriticalChunkRefusesTheFile() throws IOException {
        Path path = dir.resolve("future.kinora");
        try (KinoraFileWriter writer = KinoraFileWriter.create(path, UUID.randomUUID(), 0)) {
            writer.writeChunk(KinoraFormat.fourCC("ZZZZ"), new byte[] {1}, false, true, 0, 0, 0, 0);
            writer.finish(new ReplayMetadata(), List.of());
        }
        IOException e = assertThrows(IOException.class, () -> KinoraFile.open(path));
        assertTrue(e.getMessage().contains("newer Kinora"), e.getMessage());
    }

    @Test
    void unknownOptionalChunkIsSkipped() throws IOException {
        Path path = dir.resolve("optional.kinora");
        try (KinoraFileWriter writer = KinoraFileWriter.create(path, UUID.randomUUID(), 0)) {
            writer.writeChunk(KinoraFormat.fourCC("ZZZZ"), new byte[] {1, 2, 3}, false, false, 0, 0, 0, 0);
            writer.writeBatch(new RecordBatch(0, randomRecords(new Random(3), 10, 0)));
            writer.finish(new ReplayMetadata(), List.of());
        }
        try (KinoraFile file = KinoraFile.open(path)) {
            assertEquals(10, file.endOrdinal());
        }
    }

    /** Writes {@code batches} batches with markers, snapshots, a thumbnail and a mod track. */
    private static List<StreamRecord> writeFile(Path path, boolean finish, int batches) throws IOException {
        Random random = new Random(7);
        List<StreamRecord> all = new ArrayList<>();
        ReplayMetadata metadata = new ReplayMetadata();
        metadata.minecraftVersion = "26.2";
        try (KinoraFileWriter writer = KinoraFileWriter.create(path, UUID.randomUUID(), 1_700_000_000_000L)) {
            writer.writeMetadata(metadata);
            writer.writeModTracks(List.of(new ModTrack(0, "sample:sparkles", 1)));
            long tick = 0;
            for (int b = 0; b < batches; b++) {
                List<StreamRecord> records = randomRecords(random, 100, tick);
                if (b < 2) {
                    StreamRecord first = records.getFirst();
                    records.set(0, new StreamRecord(RecordKind.MARKER, first.tick(), first.nanos(),
                            new Marker(first.tick(), first.nanos(), "m" + b, 0xFFFF0000, "manual", Marker.SOURCE_MANUAL).encode()));
                }
                writer.writeBatch(new RecordBatch(all.size(), records));
                all.addAll(records);
                tick = records.getLast().tick();
                if (b % 2 == 1) {
                    writer.writeSnapshot(new Snapshot(tick, 0, all.size(), List.of(new Snapshot.Reference(0))));
                }
            }
            writer.writeThumbnail(5, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
            if (finish) {
                writer.finish(metadata, List.of(
                        new Marker(0, 0, "m0", 0xFFFF0000, "manual", 0), new Marker(1, 0, "m1", 0xFFFF0000, "manual", 0)));
            }
        }
        return all;
    }

    private static List<StreamRecord> randomRecords(Random random, int count, long startTick) {
        List<StreamRecord> records = new ArrayList<>();
        long tick = startTick;
        long nanos = startTick * 50_000_000L;
        for (int i = 0; i < count; i++) {
            tick += random.nextInt(3) == 0 ? 1 : 0;
            nanos += random.nextInt(5_000_000);
            byte[] payload = new byte[random.nextInt(300)];
            // Compressible but not trivial.
            for (int j = 0; j < payload.length; j++) {
                payload[j] = (byte) (random.nextInt(8) * 17);
            }
            records.add(new StreamRecord(RecordKind.PACKET, tick, nanos, payload));
        }
        return records;
    }
}
