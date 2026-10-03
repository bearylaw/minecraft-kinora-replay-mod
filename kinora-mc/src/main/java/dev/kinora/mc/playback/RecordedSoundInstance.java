package dev.kinora.mc.playback;

import dev.kinora.mc.capture.SoundRecord;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import org.jspecify.annotations.Nullable;

/**
 * Replays a recorded client-side sound exactly: the same file variant, volume, pitch, position and
 * attenuation, rather than letting the sound event pick a random variant again.
 */
public final class RecordedSoundInstance extends AbstractSoundInstance {
    private final Identifier file;
    private final boolean stream;
    private final int attenuationDistance;

    public RecordedSoundInstance(SoundRecord record) {
        super(Identifier.parse(record.event()), sourceByName(record.category()), RandomSource.create(record.seed()));
        this.file = Identifier.parse(record.file());
        this.stream = record.has(SoundRecord.STREAMED);
        this.attenuationDistance = Math.max(1, record.attenuationDistance());
        this.volume = record.volume();
        this.pitch = record.pitch();
        this.x = record.x();
        this.y = record.y();
        this.z = record.z();
        this.relative = record.has(SoundRecord.RELATIVE);
        this.looping = false;
        this.attenuation = record.has(SoundRecord.LINEAR) ? SoundInstance.Attenuation.LINEAR : SoundInstance.Attenuation.NONE;
    }

    @Override
    public @Nullable WeighedSoundEvents resolve(SoundManager soundManager) {
        WeighedSoundEvents events = soundManager.getSoundEvent(this.identifier);
        // The range passed to the engine already folded in volume; recover the base distance.
        int base = Math.max(1, Math.round(attenuationDistance / Math.max(1.0f, this.volume)));
        this.sound = new Sound(file, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1, Sound.Type.FILE, stream, false, base);
        return events != null ? events : new WeighedSoundEvents(this.identifier, null);
    }

    static SoundSource sourceByName(String name) {
        for (SoundSource source : SoundSource.values()) {
            if (source.getName().equals(name)) {
                return source;
            }
        }
        return SoundSource.MASTER;
    }
}
