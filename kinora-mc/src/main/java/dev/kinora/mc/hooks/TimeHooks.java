package dev.kinora.mc.hooks;

import org.jspecify.annotations.Nullable;

/**
 * What Kinora's timing mixins ask. While a replay (or a render) is in control, ticks per frame and
 * the partial tick come from Kinora's clock instead of the wall clock. Game thread only.
 */
public final class TimeHooks {
    /** The active time source; when none is installed, timing is vanilla. */
    public interface Source {
        /** Whole ticks to run this frame; called once per frame. */
        int ticksForFrame(int vanillaTicks);

        /** Partial tick to render with, in [0, 1). */
        float partialTick();

        /** Ticks advanced this frame, for frame-rate dependent smoothing (fog, spyglass). */
        float deltaTicks();

        /** The replay tick animated textures show. */
        long textureTicks();

        /** True while entities must not tick (a replay restoring its world: they would tick a varying number of times). */
        default boolean entitiesFrozen() {
            return false;
        }
    }

    private static @Nullable Source source;

    private TimeHooks() {}

    public static void install(@Nullable Source s) {
        source = s;
    }

    public static boolean active() {
        return source != null;
    }

    public static boolean entitiesFrozen() {
        Source s = source;
        return s != null && s.entitiesFrozen();
    }

    public static int ticksForFrame(int vanilla) {
        Source s = source;
        return s == null ? vanilla : s.ticksForFrame(vanilla);
    }

    /** Vanilla runs at most 10 ticks a frame; a replay fast-forwarding needs more. */
    public static int tickCap(int vanilla) {
        return source == null ? vanilla : Integer.MAX_VALUE;
    }

    /** Texture animation steps: vanilla steps once per frame that has ticks, replays once per tick. */
    public static int textureSteps(int ticksToDo) {
        return source == null ? 1 : ticksToDo;
    }

    /** The tick animated textures should show, or -1 to animate as vanilla does. */
    public static long textureTicks() {
        Source s = source;
        return s == null ? -1 : s.textureTicks();
    }

    public static float partialTick(float vanilla) {
        Source s = source;
        return s == null ? vanilla : s.partialTick();
    }

    public static float deltaTicks(float vanilla) {
        Source s = source;
        return s == null ? vanilla : s.deltaTicks();
    }
}
