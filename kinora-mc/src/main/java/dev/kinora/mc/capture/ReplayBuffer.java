package dev.kinora.mc.capture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The instant replay buffer: the last N seconds of a session kept in memory, plus periodic
 * in-memory snapshots, so "save the last two minutes" can produce a complete replay after the fact.
 *
 * <p>Records are held by reference. A snapshot's references keep the records they need alive (the
 * chunk data of every loaded chunk, for instance) even after they have left the window, and the
 * garbage collector frees them once no retained snapshot refers to them.
 *
 * <p>Not thread-safe; the session lock guards it.
 */
public final class ReplayBuffer {
    private record Point(long sessionTick, long nanoTime, long position, List<StateModel.Item> items) {}

    private final ArrayDeque<Captured> records = new ArrayDeque<>();
    private final ArrayDeque<Point> points = new ArrayDeque<>();
    /** Absolute position (count of records ever added) of the first record in {@link #records}. */
    private long firstPosition;
    private long bytes;
    private volatile int windowTicks;

    public ReplayBuffer(int windowTicks) {
        this.windowTicks = windowTicks;
    }

    public void setWindowTicks(int ticks) {
        this.windowTicks = ticks;
    }

    public void add(Captured captured) {
        records.add(captured);
        bytes += captured.footprint();
    }

    /** Remembers the model's state at this point of the stream. */
    public void mark(long sessionTick, long nanoTime, List<StateModel.Item> items) {
        points.add(new Point(sessionTick, nanoTime, firstPosition + records.size(), items));
    }

    /** Drops what is older than the window, keeping one snapshot at or before the window start. */
    public void trim(long nowSessionTick) {
        long windowStart = nowSessionTick - windowTicks;
        while (points.size() > 1) {
            Point second = points.stream().skip(1).findFirst().orElseThrow();
            if (second.sessionTick() <= windowStart) {
                points.removeFirst();
            } else {
                break;
            }
        }
        long keepFrom = points.isEmpty() ? firstPosition + records.size() : points.getFirst().position();
        while (firstPosition < keepFrom && !records.isEmpty()) {
            Captured dropped = records.removeFirst();
            bytes -= dropped.footprint();
            firstPosition++;
        }
    }

    /** Approximate memory held by records in the window (not counting snapshot-only references). */
    public long bytes() {
        return bytes;
    }

    /** What a commit would write: the starting state and everything after it. */
    public record Contents(long startSessionTick, long startNanoTime, List<StateModel.Item> state, List<Captured> records) {}

    /** The earliest state inside the window (or the oldest available) and every record since; null if empty. */
    public Contents contents(long nowSessionTick) {
        if (points.isEmpty()) {
            return null;
        }
        long windowStart = nowSessionTick - windowTicks;
        Point start = points.getFirst();
        for (Point p : points) {
            if (p.sessionTick() >= windowStart) {
                start = p;
                break;
            }
        }
        List<Captured> after = new ArrayList<>();
        long position = firstPosition;
        for (Captured c : records) {
            if (position >= start.position()) {
                after.add(c);
            }
            position++;
        }
        return new Contents(start.sessionTick(), start.nanoTime(), start.items(), after);
    }

    public long oldestSessionTick() {
        return points.isEmpty() ? -1 : points.getFirst().sessionTick();
    }
}
