package dev.kinora.mc.hooks;

import dev.kinora.core.camera.CameraState;

import org.jspecify.annotations.Nullable;

/**
 * The camera override consulted by {@code CameraMixin} every frame. While a replay controls the
 * view, the rendered camera is exactly this state, regardless of which entity the game thinks it
 * is looking from. Game thread only.
 */
public final class CameraHooks {
    /** Supplies the camera for a frame, given the frame's partial tick. */
    public interface Source {
        @Nullable CameraState camera(float partialTick);
    }

    private static @Nullable Source source;
    private static @Nullable CameraState frame;

    private CameraHooks() {}

    public static void install(@Nullable Source s) {
        source = s;
        frame = null;
    }

    public static boolean active() {
        return source != null;
    }

    /** Called once per frame by the mixin; the result also supplies that frame's FOV. */
    public static @Nullable CameraState beginFrame(float partialTick) {
        Source s = source;
        frame = s == null ? null : s.camera(partialTick);
        // This runs while the frame's gizmo collector is open: the editor draws its path here.
        try {
            dev.kinora.mc.ui.PathGizmos.emit();
        } catch (IllegalStateException ignored) {
            // No gizmo collector in this context.
        }
        return frame;
    }

    /** The camera applied to the current frame, or null. */
    public static @Nullable CameraState currentFrame() {
        return frame;
    }

    public static float fov(float vanilla) {
        CameraState f = frame;
        return f == null || source == null ? vanilla : (float) f.fov();
    }
}
