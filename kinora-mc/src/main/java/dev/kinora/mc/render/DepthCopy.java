package dev.kinora.mc.render;

import dev.kinora.api.KinoraConstants;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Copies the world's depth (after the level is drawn, before the hand pass clears it) into a float
 * texture and reads it back, for depth of field. GL cannot read a depth texture back directly, so a
 * full-screen pass writes it into an {@code R32_FLOAT} colour target first.
 */
public final class DepthCopy implements AutoCloseable {
    static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(KinoraConstants.MOD_ID, "pipeline/depth_copy"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath(KinoraConstants.MOD_ID, "core/depth_copy"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    /** Texture usage: COPY_SRC | TEXTURE_BINDING | RENDER_ATTACHMENT. */
    private static final int TARGET_USAGE = 2 | 4 | 8;
    /** Buffer usage: MAP_READ | COPY_DST. */
    private static final int READBACK_USAGE = 1 | 8;

    private final int width;
    private final int height;
    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;

    DepthCopy(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    /** Copies the target's depth now (on the GPU timeline); {@link #read} reads this copy. */
    void copy(RenderTarget target) {
        GpuTextureView depth = target.getDepthTextureView();
        if (depth == null || target.width != width || target.height != height) {
            return;
        }
        if (texture == null) {
            texture = RenderSystem.getDevice().createTexture(() -> "Kinora depth copy", TARGET_USAGE, GpuFormat.R32_FLOAT, width, height, 1, 1);
            view = RenderSystem.getDevice().createTextureView(texture);
        }
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Kinora depth copy", view, Optional.empty())) {
            pass.setPipeline(PIPELINE);
            pass.bindTexture("InSampler", depth, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * Reads the last copy back; {@code done} gets depth values (0..1, reversed Z: 1 near, 0 far),
     * top row first, a frame or two later.
     */
    void read(Consumer<float[]> done) {
        if (texture == null) {
            done.accept(null);
            return;
        }
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Kinora depth readback", READBACK_USAGE, (long) width * height * 4);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
            float[] depth = new float[width * height];
            try (GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
                ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
                for (int y = 0; y < height; y++) {
                    // Bottom row first in the texture.
                    int src = (height - 1 - y) * width * 4;
                    for (int x = 0; x < width; x++) {
                        depth[y * width + x] = data.getFloat(src + x * 4);
                    }
                }
            }
            buffer.close();
            done.accept(depth);
        }, 0);
    }

    @Override
    public void close() {
        if (view != null) {
            view.close();
        }
        if (texture != null) {
            texture.close();
        }
    }
}
