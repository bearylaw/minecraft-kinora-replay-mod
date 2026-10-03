/*
 * Kinora API - LGPL-3.0-only. See LICENSE-API.
 */
package dev.kinora.api.track;

/**
 * A registered mod data track: lets a mod store its own client-side state in recordings and get it
 * back, correctly timed, during playback.
 *
 * <p>Obtain one with {@link dev.kinora.api.Kinora#registerTrack}.
 */
public interface DataTrack {
    /** Namespaced id, e.g. {@code "examplemod:sparkles"}. */
    String id();

    /** The schema version passed at registration. Replays remember it so old data can be read. */
    int version();

    /**
     * Writes an event at the current recording time. Call on the game thread.
     *
     * @return false if nothing is being recorded (the call is then a cheap no-op)
     */
    boolean write(byte[] data);

    /** True while a recording is running and {@link #write} would store data. */
    boolean isRecording();
}
