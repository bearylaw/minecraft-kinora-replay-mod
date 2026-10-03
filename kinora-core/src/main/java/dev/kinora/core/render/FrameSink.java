package dev.kinora.core.render;

import java.io.IOException;

/**
 * Where finished frames go: a video encoder or an image sequence. Called from the encoder thread
 * only, frames in order.
 */
public interface FrameSink extends AutoCloseable {
    /**
     * Prepares the output.
     *
     * @param firstFrame the first frame that will be written; non-zero when a render resumes
     */
    void begin(int width, int height, int firstFrame) throws IOException;

    /** One frame: RGBA, 8 bits per channel, top row first. */
    void write(int index, byte[] rgba) throws IOException;

    /** All frames were written: finalise the output. */
    void finish() throws IOException;

    /** The render stopped early: release resources, keep what can be resumed. */
    @Override
    void close();
}
