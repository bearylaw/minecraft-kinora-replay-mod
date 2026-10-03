package dev.kinora.mc.hooks;

import com.mojang.blaze3d.pipeline.RenderTarget;

import org.jspecify.annotations.Nullable;

/**
 * What Kinora's render-capture mixin calls: once per frame, after the world (with outlines and post
 * effects) is drawn into the main target and before the GUI is drawn over it. Game thread only.
 */
public final class RenderHooks {
    public interface Capture {
        void worldRendered(RenderTarget mainTarget);
    }

    private static @Nullable Capture capture;
    private static double shaderSeconds = Double.NaN;
    private static double shaderFrameSeconds;
    private static long shaderFrameIndex = -1;

    private RenderHooks() {}

    public static void install(@Nullable Capture c) {
        capture = c;
        if (c == null) {
            shaderSeconds = Double.NaN;
            shaderFrameIndex = -1;
        }
    }

    /**
     * The clock shader mods should use this frame: seconds of output video and the image index.
     * NaN / -1 when not rendering.
     */
    public static void setShaderClock(double seconds, double frameSeconds, long frameIndex) {
        shaderSeconds = seconds;
        shaderFrameSeconds = frameSeconds;
        shaderFrameIndex = frameIndex;
    }

    public static double shaderSeconds() {
        return shaderSeconds;
    }

    public static double shaderFrameSeconds() {
        return shaderFrameSeconds;
    }

    public static long shaderFrameIndex() {
        return shaderFrameIndex;
    }

    private static long resortsScheduled;
    private static double @Nullable [] tile;

    /** The tile being rendered ({@code {scale, offsetX, offsetY}}, see Views.tile), or null. */
    public static void setTile(double @Nullable [] t) {
        tile = t;
    }

    public static double @Nullable [] tile() {
        return tile;
    }

    /** A section's translucent geometry was queued for re-sorting (see SectionResortMixin). */
    public static void resortScheduled() {
        resortsScheduled++;
    }

    /** How many re-sorts have been queued so far; the settle check waits while this moves. */
    public static long resortsScheduled() {
        return resortsScheduled;
    }

    /** True while an offline render runs. */
    public static boolean rendering() {
        return capture != null;
    }

    /** No frame cap while rendering: vanilla caps menus, unfocused and minimised windows. */
    public static int framerateLimit(int vanilla) {
        return capture != null ? 260 : vanilla;
    }

    public static void beforeGui(RenderTarget mainTarget) {
        Capture c = capture;
        if (c != null) {
            c.worldRendered(mainTarget);
        }
    }
}
