package dev.kinora.core.format;

import dev.kinora.core.io.ByteSource;
import dev.kinora.core.io.MalformedDataException;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Read access to a {@code .kinora} file.
 *
 * <p>A finished file is opened through its trailer and index without reading anything else. An
 * unfinished one (the game crashed or was killed while recording) is scanned chunk by chunk;
 * everything up to the last intact chunk is usable, damaged stretches are skipped by
 * resynchronising on the next valid chunk header, and {@link #scanReport()} says what was lost.
 * {@link KinoraRecovery} can then write the missing index so the next open is fast.
 *
 * <p>Thread-safe: reads are positional, and the batch cache is synchronised.
 */
public final class KinoraFile implements Closeable {
    private static final int BATCH_CACHE_SIZE = 48;

    private final Path path;
    private final FileChannel channel;
    private final FileHeader header;
    private final List<IndexEntry> index;
    private final List<IndexEntry> batches;
    private final List<IndexEntry> snapshots;
    private final boolean finished;
    private final ScanReport scanReport;
    private final Map<Long, RecordBatch> batchCache = new LinkedHashMap<>(BATCH_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, RecordBatch> eldest) {
            return size() > BATCH_CACHE_SIZE;
        }
    };
    private ReplayMetadata metadata;

    /** What a scan of an unfinished file found. Empty for finished files. */
    public record ScanReport(boolean scanned, long fileSize, long validEnd, List<long[]> damagedRanges, List<String> problems) {
        public boolean clean() {
            return damagedRanges.isEmpty() && problems.isEmpty() && validEnd == fileSize;
        }

        static ScanReport none(long size) {
            return new ScanReport(false, size, size, List.of(), List.of());
        }
    }

    private KinoraFile(Path path, FileChannel channel, FileHeader header, List<IndexEntry> index, boolean finished, ScanReport report) {
        this.path = path;
        this.channel = channel;
        this.header = header;
        this.index = Collections.unmodifiableList(index);
        this.finished = finished;
        this.scanReport = report;
        List<IndexEntry> b = new ArrayList<>();
        List<IndexEntry> s = new ArrayList<>();
        for (IndexEntry e : index) {
            if (e.type() == KinoraFormat.TYPE_PKTS) {
                b.add(e);
            } else if (e.type() == KinoraFormat.TYPE_SNAP) {
                s.add(e);
            }
        }
        b.sort((x, y) -> Long.compare(x.firstOrdinal(), y.firstOrdinal()));
        s.sort((x, y) -> Long.compare(x.firstTick(), y.firstTick()));
        this.batches = Collections.unmodifiableList(b);
        this.snapshots = Collections.unmodifiableList(s);
    }

    public static KinoraFile open(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long size = channel.size();
            byte[] headerBytes = new byte[(int) Math.min(size, KinoraFormat.FILE_HEADER_SIZE)];
            readFully(channel, ByteBuffer.wrap(headerBytes), 0);
            FileHeader header = FileHeader.read(headerBytes);
            List<IndexEntry> index = readIndexViaTrailer(channel, size);
            if (index != null) {
                checkCriticalChunks(index);
                return new KinoraFile(path, channel, header, index, true, ScanReport.none(size));
            }
            Scan scan = scan(channel, size);
            checkCriticalChunks(scan.entries);
            return new KinoraFile(path, channel, header, scan.entries, false,
                    new ScanReport(true, size, scan.validEnd, scan.damaged, scan.problems));
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static void checkCriticalChunks(List<IndexEntry> entries) throws IOException {
        for (IndexEntry e : entries) {
            if ((e.flags() & KinoraFormat.FLAG_CRITICAL) != 0 && !KinoraFormat.isKnownType(e.type())) {
                throw new FileHeader.InvalidHeaderException("file needs a newer Kinora: it contains a required "
                        + KinoraFormat.fourCCName(e.type()) + " chunk");
            }
        }
    }

    static void readFully(FileChannel channel, ByteBuffer buffer, long at) throws IOException {
        long offset = at;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read < 0) {
                throw new MalformedDataException("unexpected end of file at " + offset);
            }
            offset += read;
        }
    }

    private static List<IndexEntry> readIndexViaTrailer(FileChannel channel, long size) throws IOException {
        if (size < KinoraFormat.FILE_HEADER_SIZE + KinoraFormat.CHUNK_HEADER_SIZE + KinoraFormat.TRAILER_SIZE) {
            return null;
        }
        ByteBuffer trailer = ByteBuffer.allocate(KinoraFormat.TRAILER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, trailer, size - KinoraFormat.TRAILER_SIZE);
        byte[] magic = Arrays.copyOf(trailer.array(), 8);
        if (!Arrays.equals(magic, KinoraFormat.TRAILER_MAGIC) || trailer.getInt(20) != ChunkHeader.crc(trailer, 0, 20)) {
            return null;
        }
        long indexOffset = trailer.getLong(8);
        if (indexOffset < KinoraFormat.FILE_HEADER_SIZE || indexOffset > size - KinoraFormat.TRAILER_SIZE - KinoraFormat.CHUNK_HEADER_SIZE) {
            return null;
        }
        try {
            ChunkRead chunk = readChunkAt(channel, indexOffset, size);
            if (chunk == null || chunk.header.type() != KinoraFormat.TYPE_INDX) {
                return null;
            }
            return new ArrayList<>(IndexEntry.decodeIndex(chunk.payload));
        } catch (MalformedDataException e) {
            // A damaged index is not fatal: fall back to scanning.
            return null;
        }
    }

    private record ChunkRead(ChunkHeader header, byte[] payload) {}

    /** Reads, verifies and decompresses the chunk at {@code offset}; null if its header is invalid. */
    private static ChunkRead readChunkAt(FileChannel channel, long offset, long size) throws IOException {
        if (offset + KinoraFormat.CHUNK_HEADER_SIZE > size) {
            return null;
        }
        ByteBuffer headerBuffer = ByteBuffer.allocate(KinoraFormat.CHUNK_HEADER_SIZE);
        readFully(channel, headerBuffer, offset);
        headerBuffer.flip();
        ChunkHeader header = ChunkHeader.read(headerBuffer);
        if (header == null) {
            return null;
        }
        long payloadEnd = offset + KinoraFormat.CHUNK_HEADER_SIZE + header.storedLength();
        if (payloadEnd > size) {
            return null;
        }
        byte[] stored = new byte[header.storedLength()];
        readFully(channel, ByteBuffer.wrap(stored), offset + KinoraFormat.CHUNK_HEADER_SIZE);
        if (ChunkHeader.payloadCrc(stored, 0, stored.length) != header.payloadCrc()) {
            throw new MalformedDataException("chunk " + header.sequence() + " (" + header.typeName() + ") at offset "
                    + offset + " fails its checksum");
        }
        byte[] raw = header.compressed() ? Zstd.decompress(stored, 0, stored.length, header.rawLength()) : stored;
        return new ChunkRead(header, raw);
    }

    private record Scan(List<IndexEntry> entries, long validEnd, List<long[]> damaged, List<String> problems) {}

    private static Scan scan(FileChannel channel, long size) throws IOException {
        List<IndexEntry> entries = new ArrayList<>();
        List<long[]> damaged = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        long position = KinoraFormat.FILE_HEADER_SIZE;
        long validEnd = position;
        long expectedSequence = 0;
        while (position < size) {
            IndexEntry entry = tryReadEntry(channel, position, size, problems);
            if (entry != null) {
                if (entry.sequence() != expectedSequence) {
                    problems.add("chunk sequence jumps from " + expectedSequence + " to " + entry.sequence() + " at offset " + position);
                }
                expectedSequence = entry.sequence() + 1;
                if (entry.type() != KinoraFormat.TYPE_INDX) {
                    entries.add(entry);
                }
                position = entry.endOffset();
                validEnd = position;
                continue;
            }
            long next = resync(channel, position + 1, size, problems);
            if (next < 0) {
                if (position < size) {
                    damaged.add(new long[] {position, size});
                }
                break;
            }
            damaged.add(new long[] {position, next});
            position = next;
        }
        return new Scan(entries, validEnd, damaged, problems);
    }

    /** Index entry for a valid chunk at {@code position}, or null if there is none there. */
    private static IndexEntry tryReadEntry(FileChannel channel, long position, long size, List<String> problems) throws IOException {
        ChunkRead chunk;
        try {
            chunk = readChunkAt(channel, position, size);
        } catch (MalformedDataException e) {
            problems.add(e.getMessage());
            return null;
        }
        if (chunk == null) {
            return null;
        }
        ChunkHeader h = chunk.header;
        long firstTick = 0;
        long lastTick = 0;
        long firstOrdinal = 0;
        int count = 0;
        try {
            if (h.type() == KinoraFormat.TYPE_PKTS) {
                RecordBatch batch = RecordBatch.decode(chunk.payload);
                firstTick = batch.firstTick();
                lastTick = batch.lastTick();
                firstOrdinal = batch.firstOrdinal();
                count = batch.records().size();
            } else if (h.type() == KinoraFormat.TYPE_SNAP) {
                Snapshot snapshot = Snapshot.decode(chunk.payload);
                firstTick = snapshot.tick();
                lastTick = snapshot.tick();
                firstOrdinal = snapshot.resumeOrdinal();
            } else if (h.type() == KinoraFormat.TYPE_THMB) {
                firstTick = lastTick = new ByteSource(chunk.payload).readVarLong();
            }
        } catch (MalformedDataException e) {
            problems.add("chunk " + h.sequence() + " (" + h.typeName() + ") has a valid checksum but does not decode: " + e.getMessage());
            return null;
        }
        return new IndexEntry(h.type(), h.sequence(), position, h.flags(), h.storedLength(), firstTick, lastTick, firstOrdinal, count);
    }

    /** Offset of the next valid chunk at or after {@code from}, or -1. */
    private static long resync(FileChannel channel, long from, long size, List<String> problems) throws IOException {
        final int window = 1 << 20;
        ByteBuffer buffer = ByteBuffer.allocate(window + 3);
        long base = from;
        while (base < size) {
            buffer.clear();
            int length = (int) Math.min(buffer.capacity(), size - base);
            buffer.limit(length);
            readFully(channel, buffer, base);
            byte[] data = buffer.array();
            for (int i = 0; i + 4 <= length; i++) {
                if (data[i] == 'K' && data[i + 1] == 'C' && data[i + 2] == 'H' && data[i + 3] == 'K') {
                    long candidate = base + i;
                    if (tryReadEntry(channel, candidate, size, new ArrayList<>()) != null) {
                        problems.add("resynchronised at offset " + candidate);
                        return candidate;
                    }
                }
            }
            if (length < buffer.capacity()) {
                break;
            }
            base += window;
        }
        return -1;
    }

    // ------------------------------------------------------------------ accessors

    public Path path() {
        return path;
    }

    public FileHeader header() {
        return header;
    }

    /** All chunks except the index itself, in file order. */
    public List<IndexEntry> index() {
        return index;
    }

    /** PKTS chunks ordered by first ordinal. */
    public List<IndexEntry> batches() {
        return batches;
    }

    /** SNAP chunks ordered by tick. */
    public List<IndexEntry> snapshots() {
        return snapshots;
    }

    /** True if the file has a valid trailer and index (it was closed properly or recovered). */
    public boolean finished() {
        return finished;
    }

    public ScanReport scanReport() {
        return scanReport;
    }

    public long size() throws IOException {
        return channel.size();
    }

    /** One past the last ordinal present in the file. */
    public long endOrdinal() {
        return batches.isEmpty() ? 0 : batches.getLast().endOrdinal();
    }

    public long lastTick() {
        long last = 0;
        for (IndexEntry b : batches) {
            last = Math.max(last, b.lastTick());
        }
        return last;
    }

    // ------------------------------------------------------------------ reading

    /** Verified, decompressed payload of a chunk. */
    public byte[] readPayload(IndexEntry entry) throws IOException {
        ChunkRead chunk = readChunkAt(channel, entry.offset(), channel.size());
        if (chunk == null) {
            throw new MalformedDataException("no valid chunk at offset " + entry.offset());
        }
        if (chunk.header.sequence() != entry.sequence() || chunk.header.type() != entry.type()) {
            throw new MalformedDataException("index does not match chunk at offset " + entry.offset());
        }
        return chunk.payload;
    }

    /** The last META chunk, or a blank metadata object if the file has none. */
    public synchronized ReplayMetadata metadata() throws IOException {
        if (metadata == null) {
            ReplayMetadata found = null;
            for (int i = index.size() - 1; i >= 0 && found == null; i--) {
                IndexEntry e = index.get(i);
                if (e.type() == KinoraFormat.TYPE_META) {
                    try {
                        found = ReplayMetadata.fromBytes(readPayload(e));
                    } catch (RuntimeException ignored) {
                        // Try the previous one.
                    }
                }
            }
            metadata = found != null ? found : new ReplayMetadata();
            if (!finished) {
                metadata.complete = false;
                metadata.endTick = Math.max(metadata.endTick, lastTick());
            }
        }
        return metadata.copy();
    }

    /** All MODT entries merged in file order. */
    public List<ModTrack> modTracks() throws IOException {
        List<ModTrack> tracks = new ArrayList<>();
        for (IndexEntry e : index) {
            if (e.type() == KinoraFormat.TYPE_MODT) {
                tracks.addAll(ModTrack.decodeTable(readPayload(e)));
            }
        }
        return tracks;
    }

    /** The marker table if the file has one, otherwise the MARKER records found in the stream. */
    public List<Marker> markers() throws IOException {
        for (int i = index.size() - 1; i >= 0; i--) {
            IndexEntry e = index.get(i);
            if (e.type() == KinoraFormat.TYPE_MARK) {
                ByteSource in = new ByteSource(readPayload(e));
                int count = in.readVarIntBounded(in.remaining());
                List<Marker> markers = new ArrayList<>(count);
                for (int m = 0; m < count; m++) {
                    markers.add(Marker.readTableEntry(in));
                }
                return markers;
            }
        }
        List<Marker> markers = new ArrayList<>();
        for (IndexEntry b : batches) {
            for (StreamRecord r : readBatch(b).records()) {
                if (r.kind() == RecordKind.MARKER) {
                    markers.add(Marker.decode(r.tick(), r.nanos(), r.payload()));
                }
            }
        }
        return markers;
    }

    public record Thumbnail(long tick, byte[] png) {}

    /** The last thumbnail in the file, or null. */
    public Thumbnail thumbnail() throws IOException {
        for (int i = index.size() - 1; i >= 0; i--) {
            IndexEntry e = index.get(i);
            if (e.type() == KinoraFormat.TYPE_THMB) {
                ByteSource in = new ByteSource(readPayload(e));
                long tick = in.readVarLong();
                return new Thumbnail(tick, in.readBytes(in.remaining()));
            }
        }
        return null;
    }

    public RecordBatch readBatch(IndexEntry entry) throws IOException {
        synchronized (batchCache) {
            RecordBatch cached = batchCache.get(entry.sequence());
            if (cached != null) {
                return cached;
            }
        }
        RecordBatch batch = RecordBatch.decode(readPayload(entry));
        synchronized (batchCache) {
            batchCache.put(entry.sequence(), batch);
        }
        return batch;
    }

    public Snapshot readSnapshot(IndexEntry entry) throws IOException {
        return Snapshot.decode(readPayload(entry));
    }

    /** The PKTS chunk holding {@code ordinal}, or null if it lies in a lost stretch. */
    public IndexEntry batchContaining(long ordinal) {
        int lo = 0;
        int hi = batches.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            IndexEntry b = batches.get(mid);
            if (ordinal < b.firstOrdinal()) {
                hi = mid - 1;
            } else if (ordinal >= b.endOrdinal()) {
                lo = mid + 1;
            } else {
                return b;
            }
        }
        return null;
    }

    public StreamRecord record(long ordinal) throws IOException {
        IndexEntry batch = batchContaining(ordinal);
        if (batch == null) {
            throw new MalformedDataException("record " + ordinal + " is not in the file (lost to damage?)");
        }
        return readBatch(batch).byOrdinal(ordinal);
    }

    /** The latest snapshot at or before {@code tick}, or null if there is none. */
    public IndexEntry snapshotAtOrBefore(long tick) {
        IndexEntry best = null;
        for (IndexEntry s : snapshots) {
            if (s.firstTick() <= tick) {
                best = s;
            } else {
                break;
            }
        }
        return best;
    }

    /** Ordinal of the first record whose tick is at least {@code tick}; {@link #endOrdinal()} if none. */
    public long firstOrdinalAtOrAfterTick(long tick) throws IOException {
        for (IndexEntry b : batches) {
            if (b.lastTick() >= tick) {
                for (StreamRecord r : readBatch(b).records()) {
                    if (r.tick() >= tick) {
                        return b.firstOrdinal() + readBatch(b).records().indexOf(r);
                    }
                }
            }
        }
        return endOrdinal();
    }

    /**
     * Iterates records from {@code fromOrdinal} to the end, crossing chunk boundaries. Ordinals
     * missing because of damage are skipped; {@link RecordCursor#gapsSkipped()} counts them.
     */
    public RecordCursor cursor(long fromOrdinal) {
        return new RecordCursor(fromOrdinal);
    }

    /** Sequential reader over the stream. Not thread-safe. */
    public final class RecordCursor implements Iterator<StreamRecord> {
        private long ordinal;
        private RecordBatch current;
        private long gapsSkipped;

        private RecordCursor(long from) {
            this.ordinal = from;
        }

        /** Ordinal of the record {@link #next()} will return. */
        public long ordinal() {
            return ordinal;
        }

        public long gapsSkipped() {
            return gapsSkipped;
        }

        private boolean load() {
            if (current != null && ordinal >= current.firstOrdinal() && ordinal < current.endOrdinal()) {
                return true;
            }
            IndexEntry b = batchContaining(ordinal);
            if (b == null) {
                // Jump over a lost stretch to the next batch that exists.
                IndexEntry next = null;
                for (IndexEntry candidate : batches) {
                    if (candidate.firstOrdinal() > ordinal) {
                        next = candidate;
                        break;
                    }
                }
                if (next == null) {
                    return false;
                }
                gapsSkipped += next.firstOrdinal() - ordinal;
                ordinal = next.firstOrdinal();
                b = next;
            }
            try {
                current = readBatch(b);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return true;
        }

        @Override
        public boolean hasNext() {
            return load();
        }

        /** Next record without consuming it, or null at the end. */
        public StreamRecord peek() {
            return load() ? current.byOrdinal(ordinal) : null;
        }

        @Override
        public StreamRecord next() {
            if (!load()) {
                throw new NoSuchElementException();
            }
            return current.byOrdinal(ordinal++);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
