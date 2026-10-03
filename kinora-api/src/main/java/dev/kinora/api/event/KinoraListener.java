/*
 * Kinora API - LGPL-3.0-only. See LICENSE-API.
 */
package dev.kinora.api.event;

import dev.kinora.api.RenderClock;
import dev.kinora.api.ReplayTime;

/**
 * Callbacks from Kinora. Every method has an empty default; override what you need and register
 * with {@link dev.kinora.api.Kinora#addListener}. All callbacks run on the game (render) thread.
 */
public interface KinoraListener {
    /** A recording started. */
    default void onRecordingStart() {}

    /** A recording stopped (finished, failed or discarded). */
    default void onRecordingStop() {}

    /** A replay was opened and its world is about to be built. */
    default void onReplayStart() {}

    /** The replay was closed and the player's real state restored. */
    default void onReplayStop() {}

    /**
     * Replay time jumped. Client-side state that is not rebuilt from the replay stream should be
     * reset here (or restored from a snapshot through a data track).
     */
    default void onSeek(ReplayTime from, ReplayTime to) {}

    /** An offline render started. */
    default void onRenderStart() {}

    /** An offline render finished or was cancelled. */
    default void onRenderFinish(boolean completed) {}

    /** An output frame is about to be rendered (before its first sub-frame). */
    default void onFrameBegin(RenderClock clock) {}

    /** A sub-frame is about to be rendered; with motion blur there are several per output frame. */
    default void onSubFrame(RenderClock clock, ReplayTime time) {}

    /** An output frame has been rendered and captured. */
    default void onFrameEnd(RenderClock clock) {}
}
