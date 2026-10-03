package dev.kinora.inspector;

import dev.kinora.core.format.IndexEntry;
import dev.kinora.core.format.KinoraFile;
import dev.kinora.core.format.KinoraFormat;
import dev.kinora.core.format.KinoraRecovery;
import dev.kinora.core.format.Marker;
import dev.kinora.core.format.ModTrack;
import dev.kinora.core.format.RecordKind;
import dev.kinora.core.format.Snapshot;
import dev.kinora.core.format.StreamRecord;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code kinora-inspect}: look inside {@code .kinora} files without starting the game.
 *
 * <pre>
 * kinora-inspect info      FILE         header, metadata, chunk counts
 * kinora-inspect index     FILE         every chunk
 * kinora-inspect stats     FILE         records by kind and packet id, sizes
 * kinora-inspect records   FILE [N]     the first N records (default 50)
 * kinora-inspect markers   FILE         marker table
 * kinora-inspect snapshots FILE         snapshot list
 * kinora-inspect validate  FILE         read and verify every chunk; exit 1 on damage
 * kinora-inspect recover   FILE         finish a file a crash left open
 * kinora-inspect thumbnail FILE OUT.png extract the thumbnail
 * </pre>
 */
public final class KinoraInspect {
    private KinoraInspect() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            usage(err);
            return 2;
        }
        Path file = Path.of(args[1]);
        try {
            return switch (args[0]) {
                case "info" -> info(file, out);
                case "index" -> index(file, out);
                case "stats" -> stats(file, out);
                case "records" -> records(file, args.length > 2 ? Integer.parseInt(args[2]) : 50, out);
                case "markers" -> markers(file, out);
                case "snapshots" -> snapshots(file, out);
                case "validate" -> validate(file, out);
                case "recover" -> recover(file, out);
                case "clip" -> {
                    // clip FILE START_SECONDS END_SECONDS OUT
                    if (args.length < 5) {
                        usage(err);
                        yield 2;
                    }
                    try (dev.kinora.core.format.KinoraFile f = dev.kinora.core.format.KinoraFile.open(file)) {
                        long base = f.metadata().startTick;
                        var r = dev.kinora.core.format.ClipExporter.export(f, base + Math.round(Double.parseDouble(args[2]) * 20),
                                base + Math.round(Double.parseDouble(args[3]) * 20), Path.of(args[4]));
                        out.printf("clip %s: ticks %d..%d, %d records, %d bytes%n", r.file(), r.startTick(), r.endTick(), r.records(), r.bytes());
                    }
                    yield 0;
                }
                case "thumbnail" -> {
                    if (args.length < 3) {
                        usage(err);
                        yield 2;
                    }
                    yield thumbnail(file, Path.of(args[2]), out);
                }
                default -> {
                    usage(err);
                    yield 2;
                }
            };
        } catch (IOException | RuntimeException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static void usage(PrintStream err) {
        err.println("usage: kinora-inspect <info|index|stats|records|markers|snapshots|validate|recover|thumbnail> FILE [ARG]");
        err.println("       kinora-inspect clip FILE START_SECONDS END_SECONDS OUT.kinora");
    }

    private static int info(Path path, PrintStream out) throws IOException {
        try (KinoraFile file = KinoraFile.open(path)) {
            out.printf(Locale.ROOT, "file:       %s (%,d bytes)%n", path, file.size());
            out.printf(Locale.ROOT, "format:     %d.%d, id %s%n", file.header().major(), file.header().minor(), file.header().fileId());
            out.printf(Locale.ROOT, "finished:   %s%n", file.finished() ? "yes" : "no (scanned)");
            if (file.scanReport().scanned()) {
                printScan(file.scanReport(), out);
            }
            out.printf(Locale.ROOT, "records:    %,d in %,d batches, last tick %,d (%s)%n", file.endOrdinal(), file.batches().size(),
                    file.lastTick(), duration(file.lastTick()));
            out.printf(Locale.ROOT, "snapshots:  %d%n", file.snapshots().size());
            List<ModTrack> tracks = file.modTracks();
            if (!tracks.isEmpty()) {
                out.println("mod tracks: " + tracks);
            }
            out.println("metadata:");
            out.println(file.metadata().toJson().indent(2));
        }
        return 0;
    }

    private static void printScan(KinoraFile.ScanReport report, PrintStream out) {
        out.printf(Locale.ROOT, "  valid up to %,d of %,d bytes%n", report.validEnd(), report.fileSize());
        for (long[] r : report.damagedRanges()) {
            out.printf(Locale.ROOT, "  damaged: %,d..%,d%n", r[0], r[1]);
        }
        for (String p : report.problems()) {
            out.println("  problem: " + p);
        }
    }

    private static int index(Path path, PrintStream out) throws IOException {
        try (KinoraFile file = KinoraFile.open(path)) {
            out.println(" seq  type  offset        stored     ticks              ordinals");
            for (IndexEntry e : file.index()) {
                out.printf(Locale.ROOT, "%4d  %s  %,12d  %,9d  %,7d..%,-7d  %,d+%d%s%n", e.sequence(), e.typeName(), e.offset(), e.storedLength(),
                        e.firstTick(), e.lastTick(), e.firstOrdinal(), e.recordCount(),
                        (e.flags() & KinoraFormat.FLAG_ZSTD) != 0 ? "  zstd" : "");
            }
        }
        return 0;
    }

    private static int stats(Path path, PrintStream out) throws IOException {
        try (KinoraFile file = KinoraFile.open(path)) {
            Map<String, long[]> byKind = new TreeMap<>();
            Map<String, long[]> byPacket = new TreeMap<>();
            for (KinoraFile.RecordCursor c = file.cursor(0); c.hasNext(); ) {
                StreamRecord r = c.next();
                long[] k = byKind.computeIfAbsent(RecordKind.name(r.kind()), x -> new long[2]);
                k[0]++;
                k[1] += r.payload().length;
                if (r.kind() == RecordKind.PACKET && r.payload().length > 1) {
                    String phase = r.payload()[0] == RecordKind.PHASE_CONFIGURATION ? "config" : "play";
                    String key = String.format(Locale.ROOT, "%s 0x%02x", phase, packetId(r.payload()));
                    long[] p = byPacket.computeIfAbsent(key, x -> new long[2]);
                    p[0]++;
                    p[1] += r.payload().length;
                }
            }
            out.println("records by kind:");
            byKind.forEach((k, v) -> out.printf(Locale.ROOT, "  %-14s %,10d  %,14d bytes%n", k, v[0], v[1]));
            out.println("packets by phase and id:");
            byPacket.forEach((k, v) -> out.printf(Locale.ROOT, "  %-14s %,10d  %,14d bytes%n", k, v[0], v[1]));
            long raw = byKind.values().stream().mapToLong(v -> v[1]).sum();
            out.printf(Locale.ROOT, "raw payload %,d bytes, file %,d bytes (%.1fx)%n", raw, file.size(), raw / (double) Math.max(1, file.size()));
        }
        return 0;
    }

    static int packetId(byte[] payload) {
        int value = 0;
        for (int i = 1, shift = 0; i < payload.length && shift < 35; i++, shift += 7) {
            value |= (payload[i] & 0x7F) << shift;
            if ((payload[i] & 0x80) == 0) {
                break;
            }
        }
        return value;
    }

    private static int records(Path path, int limit, PrintStream out) throws IOException {
        try (KinoraFile file = KinoraFile.open(path)) {
            KinoraFile.RecordCursor c = file.cursor(0);
            for (int i = 0; i < limit && c.hasNext(); i++) {
                long ordinal = c.ordinal();
                StreamRecord r = c.next();
                String detail = r.kind() == RecordKind.PACKET && r.payload().length > 1
                        ? String.format(Locale.ROOT, "%s id 0x%02x", r.payload()[0] == 1 ? "config" : "play", packetId(r.payload()))
                        : r.kind() == RecordKind.MARKER ? Marker.decode(r.tick(), r.nanos(), r.payload()).name() : "";
                out.printf(Locale.ROOT, "%,8d  tick %,7d  %,14d ns  %-12s %,7d bytes  %s%n", ordinal, r.tick(), r.nanos(),
                        RecordKind.name(r.kind()), r.payload().length, detail);
            }
        }
        return 0;
    }

    private static int markers(Path path, PrintStream out) throws IOException {
        try (KinoraFile file = KinoraFile.open(path)) {
            for (Marker m : file.markers()) {
                out.printf(Locale.ROOT, "tick %,7d  %-10s #%08X  %s%n", m.tick(), m.category(), m.color(), m.name());
            }
        }
        return 0;
    }

    private static int snapshots(Path path, PrintStream out) throws IOException {
        try (KinoraFile file = KinoraFile.open(path)) {
            for (IndexEntry e : file.snapshots()) {
                Snapshot s = file.readSnapshot(e);
                long refs = s.entries().stream().filter(x -> x instanceof Snapshot.Reference).count();
                out.printf(Locale.ROOT, "tick %,7d  resume %,9d  %,6d entries (%,d references, %,d inline), %,d bytes stored%n",
                        s.tick(), s.resumeOrdinal(), s.entries().size(), refs, s.entries().size() - refs, e.storedLength());
            }
        }
        return 0;
    }

    private static int validate(Path path, PrintStream out) throws IOException {
        int problems = 0;
        try (KinoraFile file = KinoraFile.open(path)) {
            if (file.scanReport().scanned()) {
                printScan(file.scanReport(), out);
                problems += file.scanReport().damagedRanges().size() + file.scanReport().problems().size();
            }
            for (IndexEntry e : file.index()) {
                try {
                    byte[] payload = file.readPayload(e);
                    if (e.type() == KinoraFormat.TYPE_PKTS) {
                        file.readBatch(e);
                    } else if (e.type() == KinoraFormat.TYPE_SNAP) {
                        Snapshot s = Snapshot.decode(payload);
                        for (Snapshot.Entry entry : s.entries()) {
                            if (entry instanceof Snapshot.Reference ref && file.batchContaining(ref.ordinal()) == null) {
                                out.printf(Locale.ROOT, "snapshot at tick %d refers to missing record %d%n", s.tick(), ref.ordinal());
                                problems++;
                            }
                        }
                    }
                } catch (RuntimeException ex) {
                    out.printf(Locale.ROOT, "chunk %d (%s): %s%n", e.sequence(), e.typeName(), ex.getMessage());
                    problems++;
                }
            }
            long expected = 0;
            long previousTick = 0;
            for (IndexEntry b : file.batches()) {
                if (b.firstOrdinal() != expected) {
                    out.printf(Locale.ROOT, "ordinals jump from %d to %d%n", expected, b.firstOrdinal());
                    problems++;
                }
                if (b.firstTick() < previousTick) {
                    out.printf(Locale.ROOT, "ticks go backwards at batch %d%n", b.sequence());
                    problems++;
                }
                expected = b.endOrdinal();
                previousTick = b.lastTick();
            }
        }
        out.println(problems == 0 ? "OK" : problems + " problem(s)");
        return problems == 0 ? 0 : 1;
    }

    private static int recover(Path path, PrintStream out) throws IOException {
        KinoraRecovery.Result result = KinoraRecovery.recover(path);
        if (result.wasFinished()) {
            out.println("file was already finished; nothing to do");
        } else {
            out.printf(Locale.ROOT, "recovered: kept %,d bytes, discarded %,d, %s of recording%n", result.keptBytes(), result.discardedBytes(),
                    duration(result.ticks()));
            result.problems().forEach(p -> out.println("  " + p));
        }
        return 0;
    }

    private static int thumbnail(Path path, Path target, PrintStream out) throws IOException {
        try (KinoraFile file = KinoraFile.open(path)) {
            KinoraFile.Thumbnail thumbnail = file.thumbnail();
            if (thumbnail == null) {
                out.println("no thumbnail");
                return 1;
            }
            Files.write(target, thumbnail.png());
            out.printf(Locale.ROOT, "wrote %s (tick %d, %,d bytes)%n", target, thumbnail.tick(), thumbnail.png().length);
        }
        return 0;
    }

    static String duration(long ticks) {
        long seconds = ticks / 20;
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
}
