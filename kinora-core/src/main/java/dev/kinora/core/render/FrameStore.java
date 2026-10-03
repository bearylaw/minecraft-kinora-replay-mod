package dev.kinora.core.render;

import dev.kinora.core.format.Zstd;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Holds images rendered ahead of their place in the output: frames rendered out of order, and the
 * halves of a dissolve waiting for each other. Keeps up to a memory budget in RAM and spills the
 * rest, zstd-compressed, to a scratch folder that {@link #close} deletes.
 *
 * <p>Not thread-safe; the encoder thread owns it.
 */
public final class FrameStore implements AutoCloseable {
    /** What a stored image is: a finished frame, or one unit of a frame still being assembled. */
    public record Key(int frame, int layer, int subFrame, boolean finished) {
        static Key finished(int frame) {
            return new Key(frame, 0, 0, true);
        }

        String fileName() {
            return (finished ? "f" : "u") + frame + "_" + layer + "_" + subFrame + ".zst";
        }
    }

    private final Path spillDir;
    private final long memoryBudget;
    private final Map<Key, byte[]> memory = new HashMap<>();
    private final Map<Key, Integer> spilled = new HashMap<>();
    private long memoryBytes;
    private long spilledBytes;

    /**
     * @param spillDir     scratch folder, created when first needed
     * @param memoryBudget bytes kept in RAM before images go to disk
     */
    public FrameStore(Path spillDir, long memoryBudget) {
        this.spillDir = spillDir;
        this.memoryBudget = memoryBudget;
    }

    public void put(Key key, byte[] image) {
        if (memoryBytes + image.length <= memoryBudget) {
            memory.put(key, image);
            memoryBytes += image.length;
            return;
        }
        try {
            Files.createDirectories(spillDir);
            byte[] packed = Zstd.compress(image, 0, image.length);
            Files.write(spillDir.resolve(key.fileName()), packed);
            spilled.put(key, image.length);
            spilledBytes += packed.length;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot spill a frame to " + spillDir, e);
        }
    }

    public boolean contains(Key key) {
        return memory.containsKey(key) || spilled.containsKey(key);
    }

    /** Removes and returns an image, or null if it is not stored. */
    public byte[] take(Key key) {
        byte[] image = memory.remove(key);
        if (image != null) {
            memoryBytes -= image.length;
            return image;
        }
        Integer size = spilled.remove(key);
        if (size == null) {
            return null;
        }
        Path file = spillDir.resolve(key.fileName());
        try {
            byte[] packed = Files.readAllBytes(file);
            Files.delete(file);
            spilledBytes -= packed.length;
            return Zstd.decompress(packed, 0, packed.length, size);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read a spilled frame from " + spillDir, e);
        }
    }

    public int size() {
        return memory.size() + spilled.size();
    }

    public long memoryBytes() {
        return memoryBytes;
    }

    /** Compressed bytes on disk. */
    public long spilledBytes() {
        return spilledBytes;
    }

    @Override
    public void close() {
        memory.clear();
        memoryBytes = 0;
        spilled.clear();
        if (Files.isDirectory(spillDir)) {
            try (Stream<Path> files = Files.walk(spillDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // Best effort: a scratch folder left behind is harmless.
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }
}
