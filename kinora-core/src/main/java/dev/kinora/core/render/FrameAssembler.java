package dev.kinora.core.render;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns rendered units, which arrive in replay-time order, into output frames in output order.
 *
 * <p>A frame of one unit with no fade passes straight through. Frames of several units (motion
 * blur, dissolves) are mixed by weight once their last unit arrives. A finished frame that is not
 * next in line waits in the {@link FrameStore}.
 *
 * <p>Units arrive as RGBA, 8 bits per channel, top row first. Frames leave the same way, or for deep
 * outputs as 16-bit big-endian RGBA, so mixing keeps its extra precision. Not thread-safe; the
 * encoder thread owns it.
 */
public final class FrameAssembler {
    /** Where finished frames go, in order. */
    public interface Output {
        void frame(int index, byte[] rgba) throws IOException;
    }

    private final RenderPlan plan;
    private final FrameStore store;
    private final Output output;
    private final int[] arrived;
    private final List<List<RenderPlan.Unit>> parts;
    private final boolean deep;
    private int next;

    /**
     * @param firstFrame the first frame to output (non-zero when a render resumes)
     */
    public FrameAssembler(RenderPlan plan, int firstFrame, FrameStore store, Output output) {
        this(plan, firstFrame, store, output, false);
    }

    /** @param deep output 16-bit frames */
    public FrameAssembler(RenderPlan plan, int firstFrame, FrameStore store, Output output, boolean deep) {
        this.deep = deep;
        this.plan = plan;
        this.store = store;
        this.output = output;
        this.arrived = new int[plan.frameCount()];
        this.parts = new ArrayList<>(java.util.Collections.nCopies(plan.frameCount(), (List<RenderPlan.Unit>) null));
        this.next = firstFrame;
    }

    /** Index of the next frame to be written. */
    public int nextFrame() {
        return next;
    }

    public boolean done() {
        return next >= plan.frameCount();
    }

    public void accept(RenderPlan.Unit unit, byte[] rgba) throws IOException {
        int frame = unit.frame();
        if (frame < next) {
            return;
        }
        RenderPlan.Recipe recipe = plan.recipe(frame);
        arrived[frame]++;
        byte[] finished;
        if (recipe.units() <= 1) {
            finished = recipe.fade() > 0 || deep ? mix(List.of(unit), List.of(rgba), recipe.fade(), deep) : rgba;
        } else {
            if (parts.get(frame) == null) {
                parts.set(frame, new ArrayList<>(recipe.units()));
            }
            parts.get(frame).add(unit);
            if (arrived[frame] < recipe.units()) {
                store.put(key(unit), rgba);
                return;
            }
            List<RenderPlan.Unit> units = parts.get(frame);
            List<byte[]> images = new ArrayList<>(units.size());
            for (RenderPlan.Unit u : units) {
                images.add(u == unit ? rgba : store.take(key(u)));
            }
            parts.set(frame, null);
            finished = mix(units, images, recipe.fade(), deep);
        }
        if (frame == next) {
            output.frame(frame, finished);
            next++;
            drain();
        } else {
            store.put(FrameStore.Key.finished(frame), finished);
        }
    }

    private void drain() throws IOException {
        while (next < plan.frameCount()) {
            FrameStore.Key key = FrameStore.Key.finished(next);
            if (!store.contains(key)) {
                return;
            }
            output.frame(next, store.take(key));
            next++;
        }
    }

    private static FrameStore.Key key(RenderPlan.Unit u) {
        return new FrameStore.Key(u.frame(), u.layer(), u.subFrame(), false);
    }

    static byte[] mix(List<RenderPlan.Unit> units, List<byte[]> images, double fade) {
        return mix(units, images, fade, false);
    }

    /** Weighted average of the images, then faded towards black; 16-bit output when {@code deep}. */
    static byte[] mix(List<RenderPlan.Unit> units, List<byte[]> images, double fade, boolean deep) {
        int length = images.getFirst().length;
        double total = 0;
        for (RenderPlan.Unit u : units) {
            total += u.weight();
        }
        if (total <= 0) {
            total = 1;
        }
        double keep = 1 - Math.max(0, Math.min(1, fade));
        float[] weights = new float[units.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = (float) (units.get(i).weight() / total);
        }
        byte[][] src = images.toArray(byte[][]::new);
        float k = (float) keep;
        byte[] out = new byte[deep ? length * 2 : length];
        // In blocks of whole pixels, in parallel: a 1080p dissolve is 8 million values.
        int block = 1 << 16;
        java.util.stream.IntStream.range(0, (length + block - 1) / block).parallel().forEach(b -> {
            int end = Math.min(length, (b + 1) * block);
            for (int p = b * block; p < end; p++) {
                float v = 0;
                for (int i = 0; i < src.length; i++) {
                    v += (src[i][p] & 0xFF) * weights[i];
                }
                // Alpha is mixed but not faded: a fade darkens the picture, it does not make it transparent.
                if ((p & 3) != 3) {
                    v *= k;
                }
                if (deep) {
                    int w = Math.min(65535, Math.round(v * 257));
                    out[2 * p] = (byte) (w >>> 8);
                    out[2 * p + 1] = (byte) w;
                } else {
                    out[p] = (byte) Math.min(255, Math.round(v));
                }
            }
        });
        return out;
    }
}
