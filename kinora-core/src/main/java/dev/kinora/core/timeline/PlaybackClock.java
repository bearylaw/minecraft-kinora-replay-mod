package dev.kinora.core.timeline;

/**
 * Replay time during interactive playback: where the viewer is, how fast time moves, and how many
 * whole ticks the game must run before the next frame.
 *
 * <p>Time is kept in ticks as a double. Each frame the owner calls {@link #advance(long)} with the
 * wall clock, then asks {@link #ticksToRun(long)} how many ticks bring the world (which stands at
 * a whole number of completed ticks) up to the clock, and renders with {@link #partialTick()}.
 *
 * <p>Offline rendering does not use the wall clock at all: it calls {@link #setTime(double)} for
 * every frame.
 */
public final class PlaybackClock {
    /** Fewest and most ticks a single frame may run while playing normally. */
    public static final int MAX_TICKS_PER_FRAME = 40;
    public static final double MIN_SPEED = 0.01;
    public static final double MAX_SPEED = 10.0;

    private double time;
    private double speed = 1.0;
    private boolean paused;
    private long lastWallNanos = Long.MIN_VALUE;
    private double end = Double.MAX_VALUE;
    private double loopStart = -1;
    private double loopEnd = -1;

    /** Replay time, in ticks. */
    public double time() {
        return time;
    }

    /** Sets replay time directly (seeking, offline rendering). */
    public void setTime(double ticks) {
        time = clamp(ticks);
    }

    public double speed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        lastWallNanos = Long.MIN_VALUE;
    }

    public void setEnd(double endTicks) {
        this.end = endTicks;
        time = clamp(time);
    }

    public double end() {
        return end;
    }

    /** Loops playback between two times; pass a negative start to stop looping. */
    public void setLoop(double start, double stopAt) {
        if (start < 0 || stopAt <= start) {
            loopStart = -1;
            loopEnd = -1;
        } else {
            loopStart = start;
            loopEnd = stopAt;
        }
    }

    public boolean looping() {
        return loopStart >= 0;
    }

    /**
     * Moves time forward by the wall-clock time since the last call, scaled by the speed. Stops at
     * the end; wraps when looping.
     *
     * @return true if time jumped backwards (a loop wrapped), which the caller must treat as a seek
     */
    public boolean advance(long wallNanos) {
        if (lastWallNanos == Long.MIN_VALUE || paused) {
            lastWallNanos = wallNanos;
            return false;
        }
        long elapsed = Math.max(0, wallNanos - lastWallNanos);
        lastWallNanos = wallNanos;
        // A frame that took longer than a second (a hitch, a breakpoint) does not fast-forward.
        elapsed = Math.min(elapsed, 1_000_000_000L);
        time = clamp(time + elapsed / 50_000_000.0 * speed);
        if (looping() && time >= loopEnd) {
            time = loopStart;
            return true;
        }
        if (time >= end) {
            paused = true;
        }
        return false;
    }

    /** Whole ticks to run so that {@code completedTicks} reaches the clock, capped per frame. */
    public int ticksToRun(long completedTicks) {
        long target = (long) Math.floor(time);
        long behind = target - completedTicks;
        if (behind <= 0) {
            return 0;
        }
        return (int) Math.min(behind, MAX_TICKS_PER_FRAME);
    }

    /** Fraction of the next tick to render, in [0, 1). */
    public float partialTick() {
        double fraction = time - Math.floor(time);
        float f = (float) fraction;
        return f >= 1.0f ? Math.nextDown(1.0f) : f;
    }

    /** Steps by whole or fractional ticks while paused (frame step). */
    public void step(double ticks) {
        time = clamp(time + ticks);
    }

    private double clamp(double t) {
        return Math.max(0, Math.min(end, t));
    }
}
