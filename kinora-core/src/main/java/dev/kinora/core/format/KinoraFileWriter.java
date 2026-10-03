package dev.kinora.core.format;

import dev.kinora.core.io.ByteSink;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Appends chunks to a {@code .kinora} file. Append-only: nothing written is ever rewritten, so a
 * crash at any moment leaves every complete chunk intact and recoverable.
 *
 * <p>Not thread-safe: one writer thread owns it (see
 * {@link dev.kinora.core.recording.RecordingWriter}).
 */
public final class KinoraFileWriter implements Closeable {
    /** Payloads smaller than this are stored uncompressed: zstd's frame overhead would outweigh the gain. */
    private static final int COMPRESS_THRESHOLD = 64;

    private final Path path;
    private final FileChannel channel;
    private final FileHeader header;
    private final List<IndexEntry> index = new ArrayList<>();
    private long position;
    private long nextSequence;
    private boolean finished;

    private KinoraFileWriter(Path path, FileChannel channel, FileHeader header, long position, long nextSequence, List<IndexEntry> existing) {
        this.path = path;
        this.channel = channel;
        this.header = header;
        this.position = position;
        this.nextSequence = nextSequence;
        this.index.addAll(existing);
    }

    /** Creates a new file; fails if it already exists. */
    public static KinoraFileWriter create(Path path, UUID fileId, long createdMillis) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            FileHeader header = FileHeader.create(fileId, createdMillis);
            writeFully(channel, ByteBuffer.wrap(header.toBytes()), 0);
            return new KinoraFileWriter(path, channel, header, KinoraFormat.FILE_HEADER_SIZE, 0, List.of());
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /**
     * Reopens an unfinished file to append to it after its last valid chunk, discarding any
     * damaged tail. Used by crash recovery to write the closing index.
     */
    static KinoraFileWriter appendAfter(Path path, FileHeader header, List<IndexEntry> validChunks, long truncateAt) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
        try {
            channel.truncate(truncateAt);
            long nextSequence = validChunks.isEmpty() ? 0 : validChunks.getLast().sequence() + 1;
            return new KinoraFileWriter(path, channel, header, truncateAt, nextSequence, validChunks);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long at) throws IOException {
        long offset = at;
        while (buffer.hasRemaining()) {
            offset += channel.write(buffer, offset);
        }
    }

    public FileHeader header() {
        return header;
    }

    public Path path() {
        return path;
    }

    /** Bytes written so far. */
    public long size() {
        return position;
    }

    public List<IndexEntry> index() {
        return Collections.unmodifiableList(index);
    }

    /**
     * Appends one chunk.
     *
     * @param compress try zstd; stored raw anyway if that does not make it smaller
     * @return the index entry describing what was written
     */
    public IndexEntry writeChunk(int type, byte[] payload, boolean compress, boolean critical,
                                 long firstTick, long lastTick, long firstOrdinal, int recordCount) throws IOException {
        if (finished) {
            throw new IllegalStateException("file already finished");
        }
        if (payload.length > KinoraFormat.MAX_CHUNK_PAYLOAD) {
            throw new IllegalArgumentException("chunk payload of " + payload.length + " bytes exceeds the format limit");
        }
        byte[] stored = payload;
        int flags = critical ? KinoraFormat.FLAG_CRITICAL : 0;
        if (compress && payload.length >= COMPRESS_THRESHOLD) {
            byte[] packed = Zstd.compress(payload, 0, payload.length);
            if (packed.length < payload.length) {
                stored = packed;
                flags |= KinoraFormat.FLAG_ZSTD;
            }
        }
        ChunkHeader chunkHeader = new ChunkHeader(type, flags, nextSequence, stored.length, payload.length,
                ChunkHeader.payloadCrc(stored, 0, stored.length));
        ByteBuffer out = ByteBuffer.allocate(KinoraFormat.CHUNK_HEADER_SIZE + stored.length);
        chunkHeader.write(out);
        out.put(stored);
        out.flip();
        long offset = position;
        writeFully(channel, out, offset);
        position += out.capacity();
        IndexEntry entry = new IndexEntry(type, nextSequence, offset, flags, stored.length, firstTick, lastTick, firstOrdinal, recordCount);
        nextSequence++;
        // INDX entries describe other chunks; the index does not list itself.
        if (type != KinoraFormat.TYPE_INDX) {
            index.add(entry);
        }
        return entry;
    }

    public IndexEntry writeMetadata(ReplayMetadata metadata) throws IOException {
        return writeChunk(KinoraFormat.TYPE_META, metadata.toBytes(), true, false, 0, 0, 0, 0);
    }

    public IndexEntry writeBatch(RecordBatch batch) throws IOException {
        return writeChunk(KinoraFormat.TYPE_PKTS, batch.encode(), true, false,
                batch.firstTick(), batch.lastTick(), batch.firstOrdinal(), batch.records().size());
    }

    public IndexEntry writeSnapshot(Snapshot snapshot) throws IOException {
        return writeChunk(KinoraFormat.TYPE_SNAP, snapshot.encode(), true, false,
                snapshot.tick(), snapshot.tick(), snapshot.resumeOrdinal(), 0);
    }

    public IndexEntry writeModTracks(List<ModTrack> tracks) throws IOException {
        return writeChunk(KinoraFormat.TYPE_MODT, ModTrack.encodeTable(tracks), false, false, 0, 0, 0, 0);
    }

    /** THMB payload: varlong tick, then the PNG bytes. */
    public IndexEntry writeThumbnail(long tick, byte[] png) throws IOException {
        ByteSink out = new ByteSink(png.length + 10);
        out.writeVarLong(tick);
        out.writeBytes(png);
        return writeChunk(KinoraFormat.TYPE_THMB, out.toByteArray(), false, false, tick, tick, 0, 0);
    }

    /** MARK payload: varint count, then {@link Marker#writeTableEntry} each. */
    public IndexEntry writeMarkerTable(List<Marker> markers) throws IOException {
        ByteSink out = new ByteSink();
        out.writeVarInt(markers.size());
        for (Marker marker : markers) {
            marker.writeTableEntry(out);
        }
        return writeChunk(KinoraFormat.TYPE_MARK, out.toByteArray(), true, false, 0, 0, 0, 0);
    }

    /** Forces written bytes to the storage device; called periodically for crash safety. */
    public void sync() throws IOException {
        channel.force(false);
    }

    /**
     * Writes the closing metadata, marker table, index and trailer, then syncs. After this the file
     * opens without a scan.
     */
    public void finish(ReplayMetadata metadata, List<Marker> markers) throws IOException {
        if (finished) {
            return;
        }
        metadata.complete = true;
        writeMetadata(metadata);
        writeMarkerTable(markers);
        IndexEntry indexChunk = writeChunk(KinoraFormat.TYPE_INDX, IndexEntry.encodeIndex(index), true, false, 0, 0, 0, 0);
        ByteBuffer trailer = ByteBuffer.allocate(KinoraFormat.TRAILER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        trailer.put(KinoraFormat.TRAILER_MAGIC);
        trailer.putLong(indexChunk.offset());
        trailer.putInt(0);
        trailer.putInt(ChunkHeader.crc(trailer, 0, 20));
        trailer.flip();
        writeFully(channel, trailer, position);
        position += KinoraFormat.TRAILER_SIZE;
        finished = true;
        sync();
    }

    public boolean finished() {
        return finished;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
