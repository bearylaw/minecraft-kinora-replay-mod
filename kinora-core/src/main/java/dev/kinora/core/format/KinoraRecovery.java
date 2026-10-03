package dev.kinora.core.format;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an unfinished {@code .kinora} file (the game crashed or was killed while recording) back into
 * a finished one, in place: the damaged tail is cut off and the closing metadata, marker table, index
 * and trailer are appended. Every complete chunk survives.
 */
public final class KinoraRecovery {
    private KinoraRecovery() {}

    /** What recovery did. */
    public record Result(boolean wasFinished, long keptBytes, long discardedBytes, long ticks, List<String> problems) {}

    /** True if the file exists, has a valid Kinora header and lacks a trailer. */
    public static boolean needsRecovery(Path path) {
        try (KinoraFile file = KinoraFile.open(path)) {
            return !file.finished();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static Result recover(Path path) throws IOException {
        List<IndexEntry> entries;
        FileHeader header;
        ReplayMetadata metadata;
        List<Marker> markers;
        KinoraFile.ScanReport report;
        long lastTick;
        try (KinoraFile file = KinoraFile.open(path)) {
            if (file.finished()) {
                return new Result(true, file.size(), 0, file.lastTick(), List.of());
            }
            header = file.header();
            entries = new ArrayList<>(file.index());
            metadata = file.metadata();
            markers = file.markers();
            report = file.scanReport();
            lastTick = file.lastTick();
        }
        // Chunks found after a damaged stretch keep their offsets: the index points at them and the
        // garbage in between is never read again. Only the damaged tail is cut.
        long truncateAt = report.validEnd();
        for (IndexEntry e : entries) {
            truncateAt = Math.max(truncateAt, e.endOffset());
        }
        long size = Files.size(path);
        metadata.recovered = true;
        metadata.endTick = Math.max(metadata.endTick, lastTick);
        try (KinoraFileWriter writer = KinoraFileWriter.appendAfter(path, header, entries, truncateAt)) {
            writer.finish(metadata, markers);
        }
        return new Result(false, truncateAt, size - truncateAt, lastTick, report.problems());
    }
}
