package dev.kinora.core.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipExporterTest {
    /** One record per tick, 0..199, payload = the tick; snapshots at ticks 50 and 120. */
    private static Path replay(Path dir) throws IOException {
        Path path = dir.resolve("full.kinora");
        ReplayMetadata meta = new ReplayMetadata();
        meta.startTick = 0;
        meta.endTick = 199;
        try (KinoraFileWriter w = KinoraFileWriter.create(path, UUID.randomUUID(), 0)) {
            w.writeMetadata(meta);
            List<StreamRecord> records = new ArrayList<>();
            for (int t = 0; t < 200; t++) {
                records.add(new StreamRecord(RecordKind.PACKET, t, t * 50_000_000L, new byte[] {(byte) t}));
            }
            w.writeBatch(new RecordBatch(0, records.subList(0, 100)));
            w.writeSnapshot(new Snapshot(50, 0, 51, List.of(new Snapshot.Reference(10), new Snapshot.Inline(RecordKind.PACKET, new byte[] {7}),
                    new Snapshot.Reference(40))));
            w.writeBatch(new RecordBatch(100, records.subList(100, 200)));
            w.writeSnapshot(new Snapshot(120, 0, 121, List.of(new Snapshot.Reference(30), new Snapshot.Reference(110))));
            w.finish(meta, List.of(new Marker(30, 0, "early", 0, "x", 0), new Marker(70, 0, "inside", 0, "x", 0),
                    new Marker(150, 0, "late", 0, "x", 0)));
        }
        return path;
    }

    @Test
    void clipKeepsWhatPlaybackNeeds(@TempDir Path dir) throws IOException {
        Path clip = dir.resolve("clip.kinora");
        try (KinoraFile full = KinoraFile.open(replay(dir))) {
            var result = ClipExporter.export(full, 60, 130, clip);
            // Records from the snapshot's resume point (tick 51) to the end tick.
            assertEquals(130 - 51 + 1, result.records());
            assertTrue(Files.size(clip) < Files.size(dir.resolve("full.kinora")));
        }
        try (KinoraFile c = KinoraFile.open(clip)) {
            ReplayMetadata meta = c.metadata();
            assertEquals(ReplayMetadata.Kind.CLIP, meta.kind);
            assertEquals(60, meta.startTick);
            assertEquals(130, meta.endTick);
            // Clip ordinal 0 is the original tick-51 record.
            assertEquals(51, c.record(0).tick());
            assertEquals((byte) 51, c.record(0).payload()[0]);
            assertEquals(2, c.snapshots().size());
            Snapshot first = c.readSnapshot(c.snapshotAtOrBefore(60));
            assertEquals(0, first.resumeOrdinal());
            first.entries().forEach(e -> assertInstanceOf(Snapshot.Inline.class, e));
            assertEquals((byte) 10, ((Snapshot.Inline) first.entries().getFirst()).payload()[0]);
            Snapshot second = c.readSnapshot(c.snapshotAtOrBefore(125));
            assertEquals(121 - 51, second.resumeOrdinal());
            // Reference 30 was before the clip: inline now. Reference 110 is clip ordinal 59.
            assertInstanceOf(Snapshot.Inline.class, second.entries().get(0));
            assertEquals(new Snapshot.Reference(110 - 51), second.entries().get(1));
            assertEquals(List.of("inside"), c.markers().stream().map(Marker::name).toList());
        }
    }
}
