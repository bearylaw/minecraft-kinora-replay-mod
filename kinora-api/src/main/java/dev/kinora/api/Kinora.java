/*
 * Kinora API - LGPL-3.0-only. See LICENSE-API.
 */
package dev.kinora.api;

import dev.kinora.api.event.KinoraListener;
import dev.kinora.api.track.DataTrack;
import dev.kinora.api.track.TrackHandler;

import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.random.RandomGenerator;

/**
 * The entry point for mods that want to be replay-aware.
 *
 * <p>Compile against {@code kinora-api} and declare Kinora as an optional dependency. While Kinora is
 * installed every method is safe to call at any time: before Kinora starts (registrations are kept
 * and bound when it does), and when nothing is recording or replaying (queries say so, tracks ignore
 * data, the random source is an ordinary one).
 *
 * <p>When Kinora is not installed these classes are not there at all. Keep every call in one class
 * that is only loaded when {@code ModList.get().isLoaded("kinora")} is true, as the sample mod does.
 * (Shipping {@code kinora-api} inside your own jar would also work, but two copies of the API on the
 * class path is best avoided.)
 *
 * <p>See {@code docs/api.md} for the replay-aware mod checklist.
 */
public final class Kinora {
    private static volatile KinoraRuntime runtime;
    private static final List<KinoraListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<PendingTrack> PENDING_TRACKS = new java.util.ArrayList<>();
    private static final List<String[]> PENDING_HIDEABLES = new java.util.ArrayList<>();

    private Kinora() {}

    /** True if the Kinora mod is installed and has started. */
    public static boolean isLoaded() {
        return runtime != null;
    }

    /** True while the local client is recording. */
    public static boolean isRecording() {
        KinoraRuntime r = runtime;
        return r != null && r.isRecording();
    }

    /** True while a replay is open (including during offline renders of it). */
    public static boolean isReplaying() {
        KinoraRuntime r = runtime;
        return r != null && r.isReplaying();
    }

    /** True while an offline render is producing frames. */
    public static boolean isRendering() {
        KinoraRuntime r = runtime;
        return r != null && r.isRendering();
    }

    /** Current replay time, or null when no replay is open. */
    public static ReplayTime replayTime() {
        KinoraRuntime r = runtime;
        return r == null ? null : r.replayTime();
    }

    /** Current render clock, or null when no offline render is running. */
    public static RenderClock renderClock() {
        KinoraRuntime r = runtime;
        return r == null ? null : r.renderClock();
    }

    /**
     * Registers a data track. Call once, during client setup.
     *
     * @param id      namespaced id, e.g. {@code "examplemod:sparkles"}
     * @param version schema version of what you write; passed back on playback
     * @param handler receives the data during playback
     */
    public static DataTrack registerTrack(String id, int version, TrackHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("track id must be namespaced, lowercase: " + id);
        }
        synchronized (Kinora.class) {
            KinoraRuntime r = runtime;
            if (r != null) {
                return r.registerTrack(id, version, handler);
            }
            // Kinora has not started yet (mods load in any order): bind when it does.
            PendingTrack pending = new PendingTrack(id, version, handler);
            PENDING_TRACKS.add(pending);
            return pending;
        }
    }

    /**
     * A random source for visual randomness that repeats exactly when the same replay is rendered
     * again: seeded from the replay, the current replay tick and {@code salt}. Outside replays it is
     * an ordinary random source.
     *
     * @param salt distinguishes independent uses, e.g. an entity id or a hash of your mod id
     */
    public static RandomGenerator random(long salt) {
        KinoraRuntime r = runtime;
        return r != null ? r.random(salt) : new SplittableRandom();
    }

    /**
     * Declares a HUD element or effect of your mod that the user can hide in Kinora (per replay and per
     * render). Query with {@link #isHidden}.
     *
     * @param id             namespaced id
     * @param translationKey lang key of the name shown in Kinora's visibility list
     */
    public static void registerHideable(String id, String translationKey) {
        synchronized (Kinora.class) {
            KinoraRuntime r = runtime;
            if (r != null) {
                r.registerHideable(id, translationKey);
            } else {
                PENDING_HIDEABLES.add(new String[] {id, translationKey});
            }
        }
    }

    /** True if the user hid this element for the current replay or render. Always false outside replays. */
    public static boolean isHidden(String id) {
        KinoraRuntime r = runtime;
        return r != null && r.isHidden(id);
    }

    public static void addListener(KinoraListener listener) {
        LISTENERS.add(Objects.requireNonNull(listener));
    }

    public static void removeListener(KinoraListener listener) {
        LISTENERS.remove(listener);
    }

    // ----------------------------------------------------------------- for Kinora itself

    /** Called once by Kinora at startup. Not for other mods. */
    public static void installRuntime(KinoraRuntime implementation) {
        synchronized (Kinora.class) {
            if (runtime != null) {
                throw new IllegalStateException("Kinora runtime already installed");
            }
            runtime = Objects.requireNonNull(implementation);
            // Registrations from mods that loaded before Kinora.
            for (PendingTrack t : PENDING_TRACKS) {
                t.bind(implementation.registerTrack(t.id, t.version, t.handler));
            }
            PENDING_TRACKS.clear();
            for (String[] h : PENDING_HIDEABLES) {
                implementation.registerHideable(h[0], h[1]);
            }
            PENDING_HIDEABLES.clear();
        }
    }

    /** Listeners, for Kinora to notify. Not for other mods. */
    public static List<KinoraListener> listeners() {
        return LISTENERS;
    }

    /** A track registered before Kinora started; works like the real one once Kinora is running. */
    private static final class PendingTrack implements DataTrack {
        final String id;
        final int version;
        final TrackHandler handler;
        private volatile DataTrack bound;

        PendingTrack(String id, int version, TrackHandler handler) {
            this.id = id;
            this.version = version;
            this.handler = handler;
        }

        void bind(DataTrack real) {
            bound = real;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public boolean write(byte[] data) {
            DataTrack b = bound;
            return b != null && b.write(data);
        }

        @Override
        public boolean isRecording() {
            DataTrack b = bound;
            return b != null && b.isRecording();
        }
    }
}
