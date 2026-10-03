package dev.kinora.core.audio;

/**
 * One sound the replay played while rendering, as the game's sound engine would have played it.
 *
 * @param replayTicks replay time the sound started at
 * @param sound       sound file id, e.g. {@code minecraft:sounds/mob/zombie/say1.ogg}
 * @param relative    position is relative to the listener (e.g. ambience), not a world position
 * @param attenuate   volume falls off linearly with distance, reaching 0 at {@code range} blocks
 * @param volume      0..1 and up; already includes the sound definition's own volume
 * @param pitch       playback rate, 0.5..2
 * @param category    the game's sound category (e.g. hostile, blocks, weather)
 */
public record SoundCue(double replayTicks, String sound, double x, double y, double z, boolean relative, boolean attenuate,
                       double range, double volume, double pitch, String category) {
}
