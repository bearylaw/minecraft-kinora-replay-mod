package dev.kinora.mc;

import dev.kinora.api.KinoraRuntime;
import dev.kinora.api.RenderClock;
import dev.kinora.api.ReplayTime;
import dev.kinora.api.track.DataTrack;
import dev.kinora.api.track.TrackHandler;
import dev.kinora.mc.api.ModTracks;
import dev.kinora.mc.capture.RecordingManager;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;
import dev.kinora.mc.render.RenderRunner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/** Kinora's implementation of the public API ({@link dev.kinora.api.Kinora}). */
public final class KinoraRuntimeImpl implements KinoraRuntime {
    public static final KinoraRuntimeImpl INSTANCE = new KinoraRuntimeImpl();

    /** Hideable elements other mods declared: id to translation key, in registration order. */
    private final Map<String, String> hideables = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Set<String> hidden = ConcurrentHashMap.newKeySet();

    private KinoraRuntimeImpl() {}

    @Override
    public boolean isRecording() {
        return RecordingManager.INSTANCE.isRecording();
    }

    @Override
    public boolean isReplaying() {
        return ReplayManager.INSTANCE.active();
    }

    @Override
    public boolean isRendering() {
        return RenderRunner.rendering();
    }

    @Override
    public ReplayTime replayTime() {
        ReplaySession s = ReplayManager.INSTANCE.session();
        return s == null ? null : s.replayTime();
    }

    @Override
    public RenderClock renderClock() {
        RenderRunner r = RenderRunner.active();
        return r == null ? null : r.clock();
    }

    @Override
    public DataTrack registerTrack(String id, int version, TrackHandler handler) {
        return ModTracks.register(id, version, handler);
    }

    /**
     * Seeded from the replay file, the current replay tick and the salt: the same replay moment gives
     * the same numbers in every render. Outside replays: an ordinary random source.
     */
    @Override
    public RandomGenerator random(long salt) {
        ReplaySession s = ReplayManager.INSTANCE.session();
        if (s == null) {
            return new SplittableRandom();
        }
        long seed = s.file().header().fileId().getMostSignificantBits() ^ s.file().header().fileId().getLeastSignificantBits();
        seed = seed * 0x9E3779B97F4A7C15L + s.completedTicks();
        seed = seed * 0xC2B2AE3D27D4EB4FL + salt;
        return new SplittableRandom(seed);
    }

    @Override
    public void registerHideable(String id, String translationKey) {
        hideables.put(id, translationKey);
    }

    @Override
    public boolean isHidden(String id) {
        return ReplayManager.INSTANCE.active() && hidden.contains(id);
    }

    /** For Kinora's own UI: what other mods declared, and the user's choices. */
    public Map<String, String> hideables() {
        synchronized (hideables) {
            return new LinkedHashMap<>(hideables);
        }
    }

    public void setHidden(String id, boolean hide) {
        if (hide) {
            hidden.add(id);
        } else {
            hidden.remove(id);
        }
    }

    public boolean hiddenByUser(String id) {
        return hidden.contains(id);
    }
}
