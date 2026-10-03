package dev.kinora.mc.render;

import dev.kinora.core.audio.AudioClip;
import dev.kinora.core.audio.AudioLog;
import dev.kinora.core.audio.AudioMixer;
import dev.kinora.core.audio.AudioTimeline;
import dev.kinora.core.audio.SoundCue;
import dev.kinora.core.audio.WavWriter;
import dev.kinora.core.project.Project;
import dev.kinora.core.project.Shot;
import dev.kinora.core.render.Ffmpeg;
import dev.kinora.core.render.RenderJob;
import dev.kinora.core.render.RenderPlan;
import dev.kinora.core.render.RenderSettings;
import dev.kinora.mc.KinoraConfig;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.util.KinoraPaths;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * A render's sound: what the replay played while it rendered ({@link #heard}), and, once the frames
 * are done, the mix placed on the video's timeline and added to the file ({@link #finish}).
 */
final class RenderAudio {
    private final RenderJob job;
    private final AudioLog log;
    /** Sounds that play on and change (minecarts, bees, elytra wind), with their keys; ticked by {@link #tick}. */
    private final java.util.Map<TickableSoundInstance, String> loops = new java.util.IdentityHashMap<>();
    private int loopCount;

    RenderAudio(RenderJob job) throws IOException {
        this.job = job;
        this.log = AudioLog.open(KinoraPaths.renderQueue().resolve(job.id + ".audio.jsonl"));
    }

    /** A sound the game is about to play (and Kinora silences), at the given replay tick. */
    void heard(SoundInstance instance, double replayTicks) {
        if (instance.getSource() == SoundSource.MUSIC) {
            // The game picks music at random; music is the user's to add.
            return;
        }
        if (instance instanceof TickableSoundInstance tickable) {
            // Plays on and changes: followed tick by tick (see tick).
            if (!loops.containsKey(tickable) && instance.resolve(Minecraft.getInstance().getSoundManager()) != null && audible(instance)) {
                loops.put(tickable, instance.getSound().getPath() + "#" + job.id.substring(0, Math.min(8, job.id.length())) + "-" + loopCount++);
                sample(tickable, loops.get(tickable), replayTicks);
            }
            return;
        }
        if (instance.isLooping()) {
            // A plain loop has no known end.
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        SoundManager sounds = mc.getSoundManager();
        if (instance.resolve(sounds) == null) {
            return;
        }
        var sound = instance.getSound();
        if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
            return;
        }
        float volume = instance.getVolume();
        float range = Math.max(volume, 1.0f) * sound.getAttenuationDistance();
        // The player's own category levels apply, as they would in the game; the master level does not.
        float category = instance.getSource() == SoundSource.MASTER ? 1f : mc.options.getSoundSourceVolume(instance.getSource());
        log.cue(new SoundCue(replayTicks, sound.getPath().toString(), instance.getX(), instance.getY(), instance.getZ(), instance.isRelative(),
                instance.getAttenuation() == SoundInstance.Attenuation.LINEAR, range, volume * category,
                Math.max(0.5f, Math.min(2.0f, instance.getPitch())), instance.getSource().getName()));
    }

    private static boolean audible(SoundInstance instance) {
        var sound = instance.getSound();
        return sound != null && sound != SoundManager.EMPTY_SOUND && sound != SoundManager.INTENTIONALLY_EMPTY_SOUND;
    }

    /**
     * One replay tick passed: the followed sounds update themselves (the sound engine would, but
     * Kinora keeps them from it) and their state is logged. Stopped ones are dropped.
     */
    void tick(double replayTicks) {
        var it = loops.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            TickableSoundInstance t = e.getKey();
            try {
                t.tick();
            } catch (RuntimeException ex) {
                // A sound whose entity is gone can fail to tick: it has stopped.
                it.remove();
                continue;
            }
            if (t.isStopped()) {
                it.remove();
            } else {
                sample(t, e.getValue(), replayTicks);
            }
        }
    }

    /** The world was rebuilt: the followed sounds belonged to the old one. Their new copies are heard again. */
    void endLoops() {
        loops.clear();
    }

    private void sample(TickableSoundInstance t, String key, double replayTicks) {
        Minecraft mc = Minecraft.getInstance();
        var sound = t.getSound();
        float volume = t.getVolume();
        float range = Math.max(volume, 1.0f) * sound.getAttenuationDistance();
        float category = t.getSource() == SoundSource.MASTER ? 1f : mc.options.getSoundSourceVolume(t.getSource());
        log.loop(new dev.kinora.core.audio.LoopSample(key, replayTicks, sound.getPath().toString(), t.getX(), t.getY(), t.getZ(), t.isRelative(),
                t.getAttenuation() == SoundInstance.Attenuation.LINEAR, range, volume * category, Math.max(0.5f, Math.min(2.0f, t.getPitch())),
                t.getSource().getName(), t.isLooping()));
    }

    /** Where the camera is for an output frame. */
    void listener(int frame, double x, double y, double z, double yaw) {
        log.frame(frame, x, y, z, yaw);
    }

    void flush() {
        log.flush();
    }

    void close() {
        log.close();
    }

    /**
     * Mixes and adds the sound to the finished output: muxed into a video, or written as
     * {@code audio.wav} beside an image sequence. Runs off the game thread.
     */
    void finish() throws IOException {
        log.flush();
        RenderSettings s = job.settings;
        Project project = job.project();
        Shot shot = job.shotId.isEmpty() ? null : project.shot(job.shotId);
        double frameSeconds = (double) s.fpsDenominator / s.fpsNumerator;
        double duration = RenderPlan.frameCount(shot != null ? shot.duration : project.sequenceDuration(), s) * frameSeconds;
        var lanes = AudioTimeline.sample(project, shot, s);
        List<AudioTimeline.Placement> placements = AudioTimeline.place(lanes, frameSeconds, log.cues(), s.pitchMode);
        var loopSamples = log.loops();
        float[] mix = AudioMixer.mix(placements, lanes, loopSamples, frameSeconds, RenderAudio::decode, log.listener(frameSeconds), duration,
                s.pitchMode, s.gameVolume);
        Path output = Path.of(job.output);
        if (!job.video()) {
            WavWriter.writeFloat(output.resolve("audio.wav"), mix, 2, AudioMixer.SAMPLE_RATE);
            KinoraMod.LOG.info("Kinora wrote {} sounds to {}", placements.size(), output.resolve("audio.wav"));
            return;
        }
        Path ffmpeg = Ffmpeg.locate(KinoraConfig.ffmpegPath(), KinoraPaths.tools());
        if (ffmpeg == null) {
            throw new IOException("FFmpeg disappeared before the audio was added");
        }
        String name = output.getFileName().toString();
        Path wav = output.resolveSibling(name + ".audio.wav");
        Path muxed = output.resolveSibling(name + ".muxing." + s.container);
        WavWriter.writeFloat(wav, mix, 2, AudioMixer.SAMPLE_RATE);
        Path music = s.musicPath.isBlank() ? null : Path.of(s.musicPath);
        if (music != null && !Files.isRegularFile(music)) {
            KinoraMod.LOG.warn("Kinora could not find the music file {}; rendering without it", music);
            music = null;
        }
        Process p = new ProcessBuilder(Ffmpeg.muxAudioCommand(ffmpeg, output, wav, music, s, muxed)).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (p.waitFor() != 0) {
                throw new IOException("FFmpeg could not add the audio: " + out.strip());
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        Files.move(muxed, output, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(wav);
        KinoraMod.LOG.info("Kinora added {} sounds and {} continuous sounds to {}", placements.size(),
                loopSamples.stream().map(dev.kinora.core.audio.LoopSample::key).distinct().count(), name);
    }

    /** Decodes a sound file from the game's resources (resource packs included). */
    private static AudioClip decode(String sound) {
        Identifier id = Identifier.tryParse(sound);
        if (id == null) {
            return null;
        }
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(id);
             JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            FloatArrayList samples = new FloatArrayList();
            while (ogg.readChunk(samples::add)) {
                // Decodes the whole file.
            }
            var format = ogg.getFormat();
            return new AudioClip(samples.toFloatArray(), format.getChannels(), (int) format.getSampleRate());
        } catch (IOException | RuntimeException e) {
            KinoraMod.LOG.warn("Kinora could not decode sound {}", sound, e);
            return null;
        }
    }
}
