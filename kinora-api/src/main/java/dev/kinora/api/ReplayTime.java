/*
 * Kinora API - LGPL-3.0-only. See LICENSE-API.
 */
package dev.kinora.api;

/**
 * A point in replay time: a whole tick plus the fraction of the next tick, the same split Minecraft
 * uses for rendering ({@code partialTick}).
 *
 * <p>During an offline render this is exact and does not depend on how fast the computer is: use it
 * instead of {@code System.nanoTime()} or {@code Util.getMillis()} for anything visual.
 *
 * @param tick        completed replay ticks
 * @param partialTick fraction of the next tick, in {@code [0, 1)}
 */
public record ReplayTime(long tick, float partialTick) {
    /** Ticks per second the replay runs at. */
    public static final double TICKS_PER_SECOND = 20.0;

    public ReplayTime {
        if (tick < 0) {
            throw new IllegalArgumentException("negative tick");
        }
        if (!(partialTick >= 0 && partialTick < 1)) {
            throw new IllegalArgumentException("partial tick out of [0, 1): " + partialTick);
        }
    }

    /** Replay time in ticks as one number. */
    public double ticks() {
        return tick + (double) partialTick;
    }

    /** Replay time in seconds. */
    public double seconds() {
        return ticks() / TICKS_PER_SECOND;
    }
}
