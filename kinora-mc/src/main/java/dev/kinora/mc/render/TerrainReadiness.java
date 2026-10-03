package dev.kinora.mc.render;

import dev.kinora.mc.KinoraMod;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Whether the terrain around the camera has finished building. Vanilla builds sections with its
 * own dispatcher; Sodium replaces it and says so through
 * {@code SodiumWorldRenderer.isTerrainRenderComplete()}, called reflectively so Kinora does not
 * depend on Sodium.
 */
final class TerrainReadiness {
    private static final MethodHandle SODIUM_INSTANCE;
    private static final MethodHandle SODIUM_COMPLETE;

    static {
        MethodHandle instance = null;
        MethodHandle complete = null;
        if (ModList.get().isLoaded("sodium")) {
            try {
                Class<?> renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                instance = lookup.findStatic(renderer, "instanceNullable", MethodType.methodType(renderer));
                complete = lookup.findVirtual(renderer, "isTerrainRenderComplete", MethodType.methodType(boolean.class));
                KinoraMod.LOG.info("Kinora waits for Sodium's chunk builder when rendering");
            } catch (ReflectiveOperationException | LinkageError e) {
                KinoraMod.LOG.warn("Kinora cannot ask Sodium whether chunks are built; renders may show unbuilt chunks", e);
            }
        }
        SODIUM_INSTANCE = instance;
        SODIUM_COMPLETE = complete;
    }

    private int maxFreeBuffers;
    private long lastResorts;

    /** True when nothing is waiting to be built or being built. */
    boolean idle() {
        if (SODIUM_COMPLETE != null) {
            try {
                Object renderer = SODIUM_INSTANCE.invoke();
                return renderer == null || (boolean) SODIUM_COMPLETE.invoke(renderer);
            } catch (Throwable t) {
                return true;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        int free = mc.gameRenderer.renderBuffers().sectionBufferPool().getFreeBufferCount();
        maxFreeBuffers = Math.max(maxFreeBuffers, free);
        // Translucent geometry re-sorted for the new camera: queued this frame, or still running.
        long resorts = dev.kinora.mc.hooks.RenderHooks.resortsScheduled();
        boolean resorting = resorts != lastResorts;
        lastResorts = resorts;
        if (!resorting) {
            for (var section : mc.levelRenderer.visibleSections()) {
                if (section.transparencyResortingScheduled()) {
                    resorting = true;
                    break;
                }
            }
        }
        return !resorting && mc.levelRenderer.hasRenderedAllSections() && free >= maxFreeBuffers;
    }

    /** A number that changes while more terrain becomes visible (for "held still" checks). */
    int visibleSections() {
        return Minecraft.getInstance().levelExtractor.countRenderedSections();
    }
}
