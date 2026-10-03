package dev.kinora.mc.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Reads rendered frames back from the GPU without stalling: each capture copies the colour texture
 * into one of a few reusable buffers, and the bytes arrive a frame or two later in the callback
 * (on the game thread, from {@code RenderSystem.executePendingTasks}). Works on both backends, as
 * vanilla's screenshot does.
 */
final class FrameCapture implements AutoCloseable {
    /** GpuBuffer usage: MAP_READ | COPY_DST. */
    private static final int READBACK_USAGE = 1 | 8;

    private final int width;
    private final int height;
    private final int maxBuffers;
    private final ArrayDeque<GpuBuffer> free = new ArrayDeque<>();
    private int created;
    private int inFlight;
    private boolean closed;

    FrameCapture(int width, int height, int maxBuffers) {
        this.width = width;
        this.height = height;
        this.maxBuffers = maxBuffers;
    }

    boolean canCapture() {
        return !closed && (!free.isEmpty() || created < maxBuffers);
    }

    int inFlight() {
        return inFlight;
    }

    /**
     * Copies {@code texture} (which must be {@code width x height}, RGBA8) and calls {@code done}
     * with the pixels: RGBA, top row first.
     *
     * @param keepAlpha false forces alpha to 255, as the world's alpha is not meaningful
     */
    void capture(GpuTexture texture, boolean keepAlpha, Consumer<byte[]> done) {
        GpuBuffer buffer = free.poll();
        if (buffer == null) {
            buffer = RenderSystem.getDevice().createBuffer(() -> "Kinora frame readback", READBACK_USAGE, (long) width * height * 4);
            created++;
        }
        GpuBuffer target = buffer;
        inFlight++;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, target, 0L, () -> {
            byte[] pixels = new byte[width * height * 4];
            try (GpuBufferSlice.MappedView view = target.map(true, false)) {
                ByteBuffer data = view.data();
                int row = width * 4;
                // Textures are stored bottom row first on both backends.
                for (int y = 0; y < height; y++) {
                    data.get((height - 1 - y) * row, pixels, y * row, row);
                }
            }
            if (!keepAlpha) {
                for (int i = 3; i < pixels.length; i += 4) {
                    pixels[i] = (byte) 255;
                }
            }
            inFlight--;
            if (closed) {
                target.close();
            } else {
                free.add(target);
            }
            done.accept(pixels);
        }, 0);
    }

    @Override
    public void close() {
        closed = true;
        GpuBuffer b;
        while ((b = free.poll()) != null) {
            b.close();
        }
    }
}
