/*
 * Kinora API - LGPL-3.0-only. See LICENSE-API.
 */
package dev.kinora.api;

import dev.kinora.api.track.DataTrack;
import dev.kinora.api.track.TrackHandler;

import java.util.random.RandomGenerator;

/**
 * What Kinora implements behind {@link Kinora}. Not for other mods to implement or call directly.
 */
public interface KinoraRuntime {
    boolean isRecording();

    boolean isReplaying();

    boolean isRendering();

    ReplayTime replayTime();

    RenderClock renderClock();

    DataTrack registerTrack(String id, int version, TrackHandler handler);

    RandomGenerator random(long salt);

    void registerHideable(String id, String translationKey);

    boolean isHidden(String id);
}
