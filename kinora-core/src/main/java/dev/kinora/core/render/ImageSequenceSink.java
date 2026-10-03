package dev.kinora.core.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Writes each frame as a numbered image ({@code name_000000.png}, or {@code .exr}) in a folder.
 * 8-bit PNG takes 8-bit frames; 16-bit PNG and OpenEXR take 16-bit frames (see {@link FrameAssembler}).
 * With alpha, frames arrive premultiplied: PNGs are stored with straight alpha, EXRs premultiplied. Files are written to a
 * temporary name and moved into place, so a frame file that exists is complete: a resumed render
 * can trust every file it finds.
 */
public final class ImageSequenceSink implements FrameSink {
    public enum Format {
        PNG8("png"), PNG16("png"), EXR("exr");

        final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public static Format of(RenderSettings.Output output) {
            return switch (output) {
                case PNG16_SEQUENCE -> PNG16;
                case EXR_SEQUENCE -> EXR;
                default -> PNG8;
            };
        }
    }

    private final Format format;
    private final Path folder;
    private final String baseName;
    private final boolean alpha;
    private final int compression;
    private int width;
    private int height;
    /** PNG compression is the slow part of an image sequence: frames are encoded in parallel. */
    private java.util.concurrent.ExecutorService pool;
    private final java.util.ArrayDeque<java.util.concurrent.Future<?>> pending = new java.util.ArrayDeque<>();
    private int threads;

    /**
     * @param alpha       keep the alpha channel (RGBA PNG) rather than writing RGB
     * @param compression zlib level 0..9; 1 is fast and only slightly larger
     */
    public ImageSequenceSink(Path folder, String baseName, boolean alpha, int compression) {
        this(folder, baseName, Format.PNG8, alpha, compression);
    }

    public ImageSequenceSink(Path folder, String baseName, Format format, boolean alpha, int compression) {
        this.format = format;
        this.folder = folder;
        this.baseName = baseName;
        this.alpha = alpha;
        this.compression = compression;
    }

    public Path file(int index) {
        return folder.resolve(String.format(Locale.ROOT, "%s_%06d.%s", baseName, index, format.extension));
    }

    /** Frames already on disk from an earlier run, counted from frame 0 without gaps. */
    public int completeFrames(int frameCount) {
        int n = 0;
        while (n < frameCount && Files.isRegularFile(file(n))) {
            n++;
        }
        return n;
    }

    @Override
    public void begin(int width, int height, int firstFrame) throws IOException {
        this.width = width;
        this.height = height;
        Files.createDirectories(folder);
        threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2));
        pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> Thread.ofPlatform().daemon().name("Kinora PNG").unstarted(r));
    }

    @Override
    public void write(int index, byte[] rgba) throws IOException {
        // Bounded: at most two frames per thread wait in memory.
        while (pending.size() >= threads * 2) {
            await(pending.poll());
        }
        pending.add(pool.submit(() -> {
            encode(index, rgba);
            return null;
        }));
    }

    private static void await(java.util.concurrent.Future<?> f) throws IOException {
        try {
            f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private void encode(int index, byte[] rgba) throws IOException {
        byte[] png;
        if (format == Format.EXR) {
            png = ExrWriter.encodeColor(width, height, Pixels.toLinear(rgba, alpha), alpha);
        } else if (format == Format.PNG16) {
            if (alpha) {
                Pixels.unpremultiply16(rgba);
                png = PngWriter.encode16(width, height, 4, rgba, compression);
            } else {
                png = PngWriter.encode16(width, height, 3, Pixels.rgb16(rgba), compression);
            }
        } else if (alpha) {
            Pixels.unpremultiply8(rgba);
            png = PngWriter.encode8(width, height, 4, rgba, compression);
        } else {
            byte[] rgb = new byte[width * height * 3];
            for (int s = 0, d = 0; s < rgba.length; s += 4, d += 3) {
                rgb[d] = rgba[s];
                rgb[d + 1] = rgba[s + 1];
                rgb[d + 2] = rgba[s + 2];
            }
            png = PngWriter.encode8(width, height, 3, rgb, compression);
        }
        Path target = file(index);
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.write(temp, png);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void finish() throws IOException {
        try {
            while (!pending.isEmpty()) {
                await(pending.poll());
            }
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.shutdown();
            try {
                pool.awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
