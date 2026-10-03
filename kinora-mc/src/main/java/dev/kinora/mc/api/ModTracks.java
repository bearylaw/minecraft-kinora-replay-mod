package dev.kinora.mc.api;

import dev.kinora.api.ReplayTime;
import dev.kinora.api.track.DataTrack;
import dev.kinora.api.track.TrackHandler;
import dev.kinora.core.format.ModTrack;
import dev.kinora.core.format.RecordKind;
import dev.kinora.core.format.StreamRecord;
import dev.kinora.core.io.ByteSource;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.capture.RecordingManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mod data tracks registered through the API: writing them while recording, and delivering them
 * back, by id, while a replay plays. A replay stores its own index-to-id table (MODT), so a track
 * keeps working when mods register in a different order next time.
 */
public final class ModTracks {
    private static final List<Track> TRACKS = new CopyOnWriteArrayList<>();
    private static final Map<String, Track> BY_ID = new ConcurrentHashMap<>();

    private ModTracks() {}

    /** A registered track. Its index is this game session's number for it. */
    public record Track(int index, String id, int version, TrackHandler handler) implements DataTrack {
        @Override
        public boolean write(byte[] data) {
            return RecordingManager.INSTANCE.modData(index, 0, data);
        }

        @Override
        public boolean isRecording() {
            return RecordingManager.INSTANCE.isRecording();
        }
    }

    public static synchronized DataTrack register(String id, int version, TrackHandler handler) {
        if (BY_ID.containsKey(id)) {
            throw new IllegalStateException("data track " + id + " is already registered");
        }
        Track track = new Track(TRACKS.size(), id, version, handler);
        TRACKS.add(track);
        BY_ID.put(id, track);
        return track;
    }

    /** The table written into recordings. */
    public static List<ModTrack> table() {
        List<ModTrack> table = new ArrayList<>();
        for (Track t : TRACKS) {
            table.add(new ModTrack(t.index(), t.id(), t.version()));
        }
        return table;
    }

    /** Asks every track for its state and records it, so snapshots can restore it. Game thread. */
    public static void captureStates() {
        for (Track t : TRACKS) {
            try {
                byte[] state = t.handler().captureState();
                if (state != null) {
                    RecordingManager.INSTANCE.modData(t.index(), RecordKind.MOD_DATA_FLAG_STATE, state);
                }
            } catch (RuntimeException e) {
                KinoraMod.LOG.warn("Data track {} failed to capture its state", t.id(), e);
            }
        }
    }

    /** Delivers a MOD_DATA record to the handler registered under the id the file names. */
    public static void deliver(StreamRecord record, Map<Integer, ModTrack> fileTracks, ReplayTime time) {
        ByteSource in = new ByteSource(record.payload());
        int index = in.readVarInt();
        int flags = in.readUnsignedByte();
        byte[] data = in.readBytes(in.remaining());
        ModTrack declared = fileTracks.get(index);
        Track track = declared == null ? null : BY_ID.get(declared.id());
        if (track == null) {
            return;
        }
        try {
            if ((flags & RecordKind.MOD_DATA_FLAG_STATE) != 0) {
                track.handler().reset();
                track.handler().restoreState(data, declared.version());
            } else {
                track.handler().onData(data, declared.version(), time);
            }
        } catch (RuntimeException e) {
            KinoraMod.LOG.warn("Data track {} failed to handle replay data", track.id(), e);
        }
    }

    public static void resetAll() {
        for (Track t : TRACKS) {
            try {
                t.handler().reset();
            } catch (RuntimeException e) {
                KinoraMod.LOG.warn("Data track {} failed to reset", t.id(), e);
            }
        }
    }
}
