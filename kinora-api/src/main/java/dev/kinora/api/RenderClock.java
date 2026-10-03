/*
 * Kinora API - LGPL-3.0-only. See LICENSE-API.
 */
package dev.kinora.api;

/**
 * Where an offline render is: which output frame, which motion-blur sub-frame, and the exact output
 * time. Drive animations from this (or from {@link ReplayTime}) during renders, never from wall-clock
 * time.
 *
 * @param frame          output frame index, from 0
 * @param subFrame       sub-frame index within the frame, from 0 ({@code subFrames} of them)
 * @param subFrames      sub-frames rendered per output frame (1 without motion blur)
 * @param outputSeconds  exact output-video time of this sub-frame, in seconds
 * @param framesPerSecond output frame rate
 */
public record RenderClock(long frame, int subFrame, int subFrames, double outputSeconds, double framesPerSecond) {
}
