package dev.kinora.core.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The depth pass: one OpenEXR per frame ({@code name_depth_000000.exr}) holding the distance of
 * each pixel from the camera, in blocks along the view axis. The sky is {@link #SKY}.
 */
public final class DepthPassWriter implements AutoCloseable {
    /** The distance written where nothing was drawn (as Blender does for the background). */
    public static final float SKY = 1e10f;

    private final Path folder;
    private final String baseName;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> Thread.ofPlatform().daemon().name("Kinora depth pass").unstarted(r));
    private final ArrayDeque<Future<?>> pending = new ArrayDeque<>();

    public DepthPassWriter(Path folder, String baseName) {
        this.folder = folder;
        this.baseName = baseName;
    }

    /** The depth folder for a render's output: next to it, named after it. */
    public static Path folderFor(Path output) {
        String name = output.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && !Files.isDirectory(output)) {
            name = name.substring(0, dot);
        }
        return output.resolveSibling(name + "_depth");
    }

    public Path file(int frame) {
        return folder.resolve(String.format(Locale.ROOT, "%s_depth_%06d.exr", baseName, frame));
    }

    /**
     * Writes one frame in the background. Blocks while a few frames are already waiting.
     *
     * @param depth  stored depth (reversed Z: 1 near, 0 far), {@code width x height}, top row first
     * @param factor supersampling factor: each {@code factor x factor} block becomes one pixel (its nearest point)
     */
    public void write(int frame, float[] depth, int width, int height, double m22, double m32, int factor) throws IOException {
        while (pending.size() >= 4) {
            await(pending.poll());
        }
        pending.add(pool.submit(() -> {
            int ow = width / factor;
            int oh = height / factor;
            float[] out = distances(depth, width, ow, oh, factor, m22, m32);
            Files.createDirectories(folder);
            Path target = file(frame);
            Path temp = target.resolveSibling(target.getFileName() + ".part");
            Files.write(temp, ExrWriter.encodeDepth(ow, oh, out));
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return null;
        }));
    }

    static float[] distances(float[] depth, int width, int ow, int oh, int factor, double m22, double m32) {
        float[] out = new float[ow * oh];
        java.util.stream.IntStream.range(0, oh).parallel().forEach(y -> {
            for (int x = 0; x < ow; x++) {
                // The nearest point in the block: depth edges stay hard, as compositing expects.
                float nearest = 0f;
                for (int dy = 0; dy < factor; dy++) {
                    int row = (y * factor + dy) * width + x * factor;
                    for (int dx = 0; dx < factor; dx++) {
                        nearest = Math.max(nearest, depth[row + dx]);
                    }
                }
                out[y * ow + x] = distance(nearest, m22, m32);
            }
        });
        return out;
    }

    /** Distance in blocks along the view axis for a stored (reversed-Z) depth; {@link #SKY} for the far end. */
    public static float distance(float depth, double m22, double m32) {
        if (depth <= 0f) {
            return SKY;
        }
        double d = depth + m22;
        return Math.abs(d) < 1e-12 ? SKY : (float) Math.max(0.0, m32 / d);
    }

    private static void await(Future<?> f) throws IOException {
        try {
            f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** Waits for every frame to be written. */
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
        pool.shutdown();
        try {
            pool.awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
