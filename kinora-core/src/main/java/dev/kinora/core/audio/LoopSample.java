package dev.kinora.core.audio;

/**
 * One replay tick of a sound that plays on and changes while it plays (a rolling minecart, a bee's
 * buzz, elytra wind): where it is and how loud and high it is at that tick. A sound is heard for as
 * long as it has a sample every tick.
 *
 * @param key     the same for every tick of one playing sound
 * @param looping the file repeats; otherwise it plays once
 * @see SoundCue for the other fields
 */
public record LoopSample(String key, double replayTicks, String sound, double x, double y, double z, boolean relative, boolean attenuate,
                         double range, double volume, double pitch, String category, boolean looping) {
    /** As a cue, for the gain calculation. */
    SoundCue asCue() {
        return new SoundCue(replayTicks, sound, x, y, z, relative, attenuate, range, volume, pitch, category);
    }
}
