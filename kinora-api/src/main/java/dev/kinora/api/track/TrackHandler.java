/*
 * Kinora API - LGPL-3.0-only. See LICENSE-API.
 */
package dev.kinora.api.track;

import dev.kinora.api.ReplayTime;

/**
 * Receives a data track's contents during playback, and provides state for snapshots during
 * recording. All methods run on the game thread.
 */
public interface TrackHandler {
    /**
     * An event written with {@link DataTrack#write}, delivered at the replay tick it was written at.
     *
     * @param version the track version the data was written with
     */
    void onData(byte[] data, int version, ReplayTime time);

    /**
     * During recording, Kinora periodically asks for the track's complete current state so a replay
     * can be seeked without replaying everything before. Return null if the track keeps no state.
     */
    default byte[] captureState() {
        return null;
    }

    /**
     * During playback after a seek: forget all state ({@link #reset()} has been called) and adopt
     * this state, captured earlier by {@link #captureState()}.
     */
    default void restoreState(byte[] state, int version) {}

    /** Forget all state. Called when a replay starts, before seeking, and when it ends. */
    default void reset() {}
}
