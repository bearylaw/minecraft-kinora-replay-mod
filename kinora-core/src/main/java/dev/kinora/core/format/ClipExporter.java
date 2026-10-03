package dev.kinora.core.format;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cuts a stretch of a replay into its own, smaller {@code .kinora} file, without re-recording.
 *
 * <p>The clip starts at the last snapshot at or before the start tick, with every entry of that
 * snapshot written inline (so it needs nothing from the original), followed by the original records
 * up to the end tick. Later snapshots inside the clip are kept, renumbered, so seeking in the clip is
 * as fast as in the original. Playback of the clip starts at the start tick; the records between the
 * snapshot and the start only rebuild the world up to that moment.
 */
public final class ClipExporter {
    private static final int BATCH_BYTES = 256 * 1024;

    private ClipExporter() {}

    /** What was written. */
    public record Result(Path file, long records, long startTick, long endTick, long bytes) {}

    public static Result export(KinoraFile source, long startTick, long endTick, Path output) throws IOException {
        if (endTick <= startTick) {
            throw new IllegalArgumentException("the clip must end after it starts");
        }
        ReplayMetadata meta = source.metadata().copy();
        long start = Math.max(meta.startTick, startTick);
        long end = Math.min(source.lastTick(), endTick);
        IndexEntry base = source.snapshotAtOrBefore(start);
        Snapshot first = base == null ? null : source.readSnapshot(base);
        // The original's ordinal that becomes ordinal 0 of the clip.
        long offset = first == null ? 0 : first.resumeOrdinal();

        meta.kind = ReplayMetadata.Kind.CLIP;
        meta.sourceFileId = source.header().fileId().toString();
        meta.startTick = start;
        meta.endTick = end;
        meta.durationNanos = (long) ((end - start) / meta.ticksPerSecond * 1e9);

        try (KinoraFileWriter out = KinoraFileWriter.create(output, UUID.randomUUID(), System.currentTimeMillis())) {
            out.writeMetadata(meta);
            if (first != null) {
                out.writeSnapshot(remap(source, first, offset));
            }
            // Snapshots after the first, in tick order, each written once the records it resumes after exist.
            List<Snapshot> later = new ArrayList<>();
            for (IndexEntry e : source.snapshots()) {
                Snapshot s = source.readSnapshot(e);
                if ((first == null || s.tick() > first.tick()) && s.tick() <= end) {
                    later.add(s);
                }
            }
            int nextSnapshot = 0;
            long written = 0;
            List<StreamRecord> batch = new ArrayList<>();
            long batchFirst = 0;
            int batchBytes = 0;
            KinoraFile.RecordCursor cursor = source.cursor(offset);
            while (cursor.hasNext()) {
                StreamRecord r = cursor.peek();
                if (r.tick() > end) {
                    break;
                }
                cursor.next();
                if (batch.isEmpty()) {
                    batchFirst = written;
                }
                batch.add(r);
                batchBytes += r.payload().length + 16;
                written++;
                if (batchBytes >= BATCH_BYTES) {
                    out.writeBatch(new RecordBatch(batchFirst, batch));
                    batch = new ArrayList<>();
                    batchBytes = 0;
                    nextSnapshot = writeDueSnapshots(source, out, later, nextSnapshot, offset, written);
                }
            }
            if (!batch.isEmpty()) {
                out.writeBatch(new RecordBatch(batchFirst, batch));
            }
            writeDueSnapshots(source, out, later, nextSnapshot, offset, written);
            if (!source.modTracks().isEmpty()) {
                out.writeModTracks(source.modTracks());
            }
            KinoraFile.Thumbnail thumbnail = source.thumbnail();
            if (thumbnail != null) {
                out.writeThumbnail(thumbnail.tick(), thumbnail.png());
            }
            List<Marker> markers = new ArrayList<>();
            for (Marker m : source.markers()) {
                if (m.tick() >= start && m.tick() <= end) {
                    markers.add(m);
                }
            }
            out.finish(meta, markers);
            return new Result(output, written, start, end, out.size());
        }
    }

    /** Writes the snapshots whose resume point is now within the records written. */
    private static int writeDueSnapshots(KinoraFile source, KinoraFileWriter out, List<Snapshot> later, int next, long offset, long written)
            throws IOException {
        while (next < later.size() && later.get(next).resumeOrdinal() - offset <= written) {
            out.writeSnapshot(remap(source, later.get(next), offset));
            next++;
        }
        return next;
    }

    /** A snapshot with ordinals moved by {@code offset}; references to records before the clip become inline. */
    private static Snapshot remap(KinoraFile source, Snapshot s, long offset) throws IOException {
        List<Snapshot.Entry> entries = new ArrayList<>(s.entries().size());
        for (Snapshot.Entry e : s.entries()) {
            switch (e) {
                case Snapshot.Inline inline -> entries.add(inline);
                case Snapshot.Reference ref -> {
                    if (ref.ordinal() >= offset) {
                        entries.add(new Snapshot.Reference(ref.ordinal() - offset));
                    } else {
                        StreamRecord r = source.record(ref.ordinal());
                        entries.add(new Snapshot.Inline(r.kind(), r.payload()));
                    }
                }
            }
        }
        return new Snapshot(s.tick(), s.nanos(), Math.max(0, s.resumeOrdinal() - offset), entries);
    }
}
