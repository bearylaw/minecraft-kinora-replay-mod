package dev.kinora.core.render;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The encoder thread: takes rendered units from the game thread, assembles them into frames in
 * output order and hands them to a {@link FrameSink}.
 *
 * <p>The queue is bounded. The game thread checks {@link #hasRoom} before rendering the next unit,
 * so a slow encoder slows the render down instead of filling memory.
 */
public final class RenderEncoder implements AutoCloseable {
    public enum State { RUNNING, FINISHED, FAILED, CANCELLED }

    private record Item(RenderPlan.Unit unit, byte[] rgba, Grade grade, java.util.function.UnaryOperator<byte[]> prepare,
                        java.util.function.Consumer<byte[]> after, boolean end) {}

    private final RenderPlan plan;
    private final int width;
    private final int height;
    private final int firstFrame;
    private final FrameSink sink;
    private final FrameStore store;
    private final BlockingQueue<Item> queue;
    private final AtomicInteger written = new AtomicInteger();
    /** Views of multi-view images (cube faces, eyes) waiting for the rest of their image. */
    private final java.util.Map<String, byte[][]> views = new java.util.HashMap<>();
    private final Thread thread;
    private volatile State state = State.RUNNING;
    private volatile Throwable error;
    private volatile boolean cancelled;
    /** Encoder-thread time per stage, in nanoseconds: effects before grading, grading, titles, mixing, the sink. */
    private final java.util.concurrent.atomic.AtomicLongArray stageNanos = new java.util.concurrent.atomic.AtomicLongArray(6);
    /** Index in {@link #stageNanos}: time the encoder thread waited for the writer to take a frame. */
    private static final int WAITING = 5;

    public RenderEncoder(RenderPlan plan, int width, int height, int firstFrame, FrameSink sink, FrameStore store, int queueFrames) {
        this.plan = plan;
        this.width = width;
        this.height = height;
        this.firstFrame = firstFrame;
        this.sink = sink;
        this.store = store;
        this.queue = new ArrayBlockingQueue<>(Math.max(2, queueFrames));
        this.written.set(firstFrame);
        this.thread = Thread.ofPlatform().daemon().name("Kinora encoder").unstarted(this::run);
    }

    public void start() {
        thread.start();
    }

    public boolean hasRoom() {
        return queue.remainingCapacity() > 1;
    }

    /** Hands over one rendered unit. Blocks only if the caller ignored {@link #hasRoom}. */
    public void submit(RenderPlan.Unit unit, byte[] rgba) throws InterruptedException {
        submit(unit, rgba, Grade.NONE);
    }

    /** Hands over one rendered unit and the look to give it. */
    public void submit(RenderPlan.Unit unit, byte[] rgba, Grade grade) throws InterruptedException {
        submit(unit, rgba, grade, null);
    }

    /**
     * Hands over one rendered unit, the look to give it, and work to do on it first on the encoder
     * thread (depth of field, scaling down a supersampled image); {@code prepare} may be null.
     */
    public void submit(RenderPlan.Unit unit, byte[] rgba, Grade grade, java.util.function.UnaryOperator<byte[]> prepare)
            throws InterruptedException {
        submit(unit, rgba, grade, prepare, null);
    }

    /** As above, plus work after grading, such as drawing titles (which the grade must not touch); may be null. */
    public void submit(RenderPlan.Unit unit, byte[] rgba, Grade grade, java.util.function.UnaryOperator<byte[]> prepare,
                       java.util.function.Consumer<byte[]> after) throws InterruptedException {
        if (state != State.RUNNING) {
            return;
        }
        queue.put(new Item(unit, rgba, grade, prepare, after, false));
    }

    /** No more units: finalise once everything queued is written. */
    public void endOfInput() throws InterruptedException {
        queue.put(new Item(null, null, null, null, null, true));
    }

    /** Frames written to the sink so far, counting frames from earlier runs. */
    public int framesWritten() {
        return written.get();
    }

    public State state() {
        return state;
    }

    public Throwable error() {
        return error;
    }

    /**
     * Seconds the encoder thread spent per stage: {@code [effects, grade, titles, mix, output]}, where
     * effects are depth of field, scaling down and joining views, and output is writing (PNG or FFmpeg).
     */
    public double[] stageSeconds() {
        double[] s = new double[WAITING];
        for (int i = 0; i < s.length; i++) {
            s[i] = stageNanos.get(i) / 1e9;
        }
        return s;
    }

    public long storedBytes() {
        return store.memoryBytes() + store.spilledBytes();
    }

    private void run() {
        Writer writer = null;
        try {
            sink.begin(width, height, firstFrame);
            Writer w = writer = new Writer();
            FrameAssembler assembler = new FrameAssembler(plan, firstFrame, store, w::put, plan.settings().deep());
            while (!cancelled) {
                Item item = queue.poll(100, TimeUnit.MILLISECONDS);
                if (item == null) {
                    continue;
                }
                if (item.end) {
                    if (!assembler.done()) {
                        throw new IOException("render ended at frame " + assembler.nextFrame() + " of " + plan.frameCount());
                    }
                    writer.drain();
                    sink.finish();
                    state = State.FINISHED;
                    return;
                }
                long t0 = System.nanoTime();
                if (item.prepare != null) {
                    item = new Item(item.unit, item.prepare.apply(item.rgba), item.grade, null, item.after, false);
                }
                byte[] image = joinViews(item);
                long t1 = System.nanoTime();
                stageNanos.addAndGet(0, t1 - t0);
                if (image == null) {
                    continue;
                }
                if (item.grade != null && !item.grade.isNone()) {
                    item.grade.apply(image, width, height, item.unit.frame() * 64L + item.unit.subFrame());
                }
                long t2 = System.nanoTime();
                stageNanos.addAndGet(1, t2 - t1);
                if (item.after != null) {
                    item.after.accept(image);
                }
                long t3 = System.nanoTime();
                stageNanos.addAndGet(2, t3 - t2);
                long waitedBefore = stageNanos.get(WAITING);
                assembler.accept(item.unit, image);
                // Mixing without the time spent waiting for the writer.
                stageNanos.addAndGet(3, System.nanoTime() - t3 - (stageNanos.get(WAITING) - waitedBefore));
            }
            // Frames already handed to the writer are kept: a resumed render continues after them.
            writer.drain();
            sink.close();
            state = State.CANCELLED;
        } catch (Throwable t) {
            error = t;
            if (writer != null) {
                writer.abort();
            }
            try {
                sink.close();
            } catch (RuntimeException ignored) {
            }
            state = cancelled ? State.CANCELLED : State.FAILED;
        } finally {
            queue.clear();
            store.close();
        }
    }

    /**
     * Writes finished frames to the sink on its own thread, so FFmpeg (or PNG encoding) runs while
     * the encoder thread grades and mixes the next frames. Holds at most a few frames.
     */
    private final class Writer {
        private record Frame(int index, byte[] rgba) {}

        private static final Frame END = new Frame(-1, null);
        private final BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(3);
        private final Thread thread;
        private volatile Throwable failure;

        Writer() {
            thread = Thread.ofPlatform().daemon().name("Kinora frame writer").start(this::loop);
        }

        private void loop() {
            try {
                while (true) {
                    Frame f = frames.take();
                    if (f == END) {
                        return;
                    }
                    long t = System.nanoTime();
                    sink.write(f.index, f.rgba);
                    stageNanos.addAndGet(4, System.nanoTime() - t);
                    written.set(f.index + 1);
                }
            } catch (InterruptedException e) {
                // Aborted.
            } catch (Throwable t) {
                failure = t;
                frames.clear();
            }
        }

        void put(int index, byte[] rgba) throws IOException {
            long t = System.nanoTime();
            try {
                while (!frames.offer(new Frame(index, rgba), 100, TimeUnit.MILLISECONDS)) {
                    check();
                    if (!thread.isAlive()) {
                        throw new IOException("the frame writer stopped");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } finally {
                stageNanos.addAndGet(WAITING, System.nanoTime() - t);
            }
            check();
        }

        /** Waits until every frame handed over is written. */
        void drain() throws IOException {
            try {
                while (thread.isAlive() && !frames.offer(END, 100, TimeUnit.MILLISECONDS)) {
                    check();
                }
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            check();
        }

        void abort() {
            thread.interrupt();
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void check() throws IOException {
            Throwable t = failure;
            if (t instanceof IOException io) {
                throw io;
            } else if (t != null) {
                throw new IOException(t);
            }
        }
    }

    /** The output picture once all views of an image are in, or null while some are missing. */
    private byte[] joinViews(Item item) {
        int count = Views.count(plan.settings());
        if (count == 1) {
            return item.rgba;
        }
        RenderPlan.Unit u = item.unit;
        String key = u.frame() + ":" + u.layer() + ":" + u.subFrame();
        byte[][] parts = views.computeIfAbsent(key, k -> new byte[count][]);
        parts[u.view()] = item.rgba;
        for (byte[] p : parts) {
            if (p == null) {
                return null;
            }
        }
        views.remove(key);
        return Views.combine(parts, plan.settings());
    }

    /** Stops without finishing; what the sink has written stays resumable. Waits for the thread. */
    public void cancel() {
        cancelled = true;
        try {
            thread.join(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for the encoder to finish after {@link #endOfInput}. */
    public boolean await(long millis) throws InterruptedException {
        thread.join(millis);
        return !thread.isAlive();
    }

    @Override
    public void close() {
        if (thread.isAlive()) {
            cancel();
        }
    }
}
