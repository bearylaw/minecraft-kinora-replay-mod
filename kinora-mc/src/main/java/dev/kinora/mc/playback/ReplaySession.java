package dev.kinora.mc.playback;

import com.mojang.authlib.GameProfile;

import dev.kinora.api.Kinora;
import dev.kinora.api.ReplayTime;
import dev.kinora.api.event.KinoraListener;
import dev.kinora.core.format.IndexEntry;
import dev.kinora.core.format.KinoraFile;
import dev.kinora.core.format.Marker;
import dev.kinora.core.format.ModTrack;
import dev.kinora.core.format.RecordKind;
import dev.kinora.core.format.ReplayMetadata;
import dev.kinora.core.format.Snapshot;
import dev.kinora.core.format.StreamRecord;
import dev.kinora.core.timeline.PlaybackClock;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.adapter.SyntheticPackets;
import dev.kinora.mc.capture.ClientState;
import dev.kinora.mc.capture.SoundRecord;
import dev.kinora.mc.hooks.TimeHooks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.world.level.GameType;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One open replay: the file, the fake connection feeding it into the client, the clock, and the
 * puppet. Owns replay time; the camera is the {@link ReplayManager}'s, so it survives seeks.
 *
 * <p>Time model: {@link #completedTicks()} replay ticks have run. Records with tick {@code t} are
 * applied right after replay tick {@code t} completes, which is when the live client processed
 * them. Rendering between ticks uses the clock's fraction as the partial tick.
 *
 * <p>Game thread only.
 */
public final class ReplaySession implements PlaybackFilter.Observer, TimeHooks.Source {
    /** Seeks closer than this run forward instead of restarting from a snapshot. */
    private static final long FORWARD_SEEK_LIMIT = 30 * 20;
    /** Ticks per frame while fast-forwarding to a seek target. */
    private static final int FAST_FORWARD_TICKS_PER_FRAME = 200;

    public enum Phase { LOADING, PLAYING, CLOSED }

    /** Something that decides replay time frame by frame (the editor previewing a shot). */
    public interface TimeDriver {
        /** Replay time in ticks to show this frame, or NaN to leave time to the clock. */
        double frame();

        /** Called after every replay tick. */
        default void afterTick(long completedTicks) {}
    }

    private final KinoraFile file;
    private final ReplayMetadata metadata;
    private final Map<Integer, ModTrack> modTracks = new HashMap<>();
    private final PlaybackClock clock = new PlaybackClock();
    private final Puppet puppet = new Puppet();
    private final GameProfile cameraProfile = new GameProfile(UUID.randomUUID(), "KinoraCamera");
    private final ServerData serverData;
    private @Nullable PlaybackConnection connection;
    private @Nullable PlaybackFilter filter;
    private KinoraFile.@Nullable RecordCursor cursor;
    private long completedTicks;
    private int grantedTicks;
    private int ticksThisFrame;
    private long fastForwardTarget = -1;
    private Phase phase = Phase.LOADING;
    private boolean resumeAfterLoad;
    /** Replay tick at which the client last received a time packet. */
    private long lastTimeTick;
    private int applyErrors;
    private @Nullable TimeDriver driver;

    ReplaySession(KinoraFile file) throws IOException {
        this.file = file;
        this.metadata = file.metadata();
        for (ModTrack t : file.modTracks()) {
            modTracks.put(t.index(), t);
        }
        clock.setEnd(Math.max(metadata.endTick, file.lastTick()));
        serverData = new ServerData(serverName(metadata), "kinora.replay", ServerData.Type.OTHER);
    }

    /**
     * The name the replay's connection goes by. Mods keep per-server data under the server's name
     * (Distant Horizons its far terrain, by default), so a multiplayer replay takes the recorded
     * server's name and finds that data. The address stays Kinora's own: the game saves a server's
     * details over the server-list entry with the same name and address, and a replay must not.
     */
    static String serverName(ReplayMetadata metadata) {
        return metadata.singleplayer || metadata.serverName == null || metadata.serverName.isBlank()
                ? "Kinora Replay" : metadata.serverName;
    }

    public KinoraFile file() {
        return file;
    }

    public ReplayMetadata metadata() {
        return metadata;
    }

    public PlaybackClock clock() {
        return clock;
    }

    public Phase phase() {
        return phase;
    }

    public long completedTicks() {
        return completedTicks;
    }

    public long startTick() {
        return metadata.startTick;
    }

    public long endTick() {
        return (long) clock.end();
    }

    public void setDriver(@Nullable TimeDriver driver) {
        this.driver = driver;
    }

    public boolean fastForwarding() {
        return fastForwardTarget >= 0;
    }

    public int errors() {
        return applyErrors + (filter == null ? 0 : filter.errors());
    }

    /** The recording player's entity in playback, if spawned. */
    public net.minecraft.world.entity.player.@Nullable Player puppet() {
        return puppet.entity();
    }

    public int recordedPlayerId() {
        return puppet.entityId();
    }

    public ReplayTime replayTime() {
        return new ReplayTime(completedTicks, partialTick());
    }

    public List<Marker> markers() {
        try {
            return file.markers();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ starting

    /**
     * Builds the world at {@code targetTick}: a fresh connection, the nearest snapshot at or before
     * it (or the stream start), then a silent fast-forward to the target.
     */
    void startAt(long targetTick) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        long target = Math.max(metadata.startTick, Math.min(targetTick, endTick()));
        filter = new PlaybackFilter(this);
        connection = PlaybackConnection.open(mc, cameraProfile, serverData, filter);
        puppet.reset();
        IndexEntry snapshotEntry = target > metadata.startTick ? file.snapshotAtOrBefore(target) : null;
        lastTimeTick = 0;
        if (snapshotEntry != null) {
            Snapshot snapshot = file.readSnapshot(snapshotEntry);
            // Everything in a snapshot takes effect at its tick.
            completedTicks = snapshot.tick();
            for (Snapshot.Entry entry : snapshot.entries()) {
                StreamRecord record = switch (entry) {
                    case Snapshot.Inline inline -> new StreamRecord(inline.kind(), snapshot.tick(), snapshot.nanos(), inline.payload());
                    case Snapshot.Reference ref -> file.record(ref.ordinal());
                };
                filter.setTimeAdvance(snapshot.tick() - record.tick());
                apply(record, true);
                filter.setTimeAdvance(0);
            }
            cursor = file.cursor(snapshot.resumeOrdinal());
            completedTicks = snapshot.tick();
        } else {
            cursor = file.cursor(0);
            completedTicks = 0;
        }
        applyUpTo(completedTicks, true);
        clock.setTime(target);
        fastForwardTarget = target > completedTicks ? target : -1;
        phase = Phase.LOADING;
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public int ticksForFrame(int vanillaTicks) {
        ticksThisFrame = 0;
        if (phase == Phase.LOADING) {
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener != null && listener.hasClientLoaded() && Minecraft.getInstance().level != null) {
                phase = Phase.PLAYING;
                clock.setPaused(!resumeAfterLoad);
                resyncTime();
            } else {
                // The loading screen and level-load tracker tick on vanilla time; replay time waits.
                return vanillaTicks;
            }
        }
        if (phase != Phase.PLAYING) {
            return 0;
        }
        int ticks;
        if (fastForwardTarget >= 0) {
            ticks = (int) Math.min(fastForwardTarget - completedTicks, FAST_FORWARD_TICKS_PER_FRAME);
            if (ticks <= 0) {
                fastForwardTarget = -1;
                ticks = 0;
            }
        } else {
            double driven = driver == null ? Double.NaN : driver.frame();
            if (!Double.isNaN(driven)) {
                if (!follow(driven)) {
                    return 0;
                }
            } else if (clock.advance(System.nanoTime())) {
                // A loop wrapped around: seek back.
                seek(clock.time());
                return 0;
            }
            ticks = clock.ticksToRun(completedTicks);
        }
        grantedTicks = ticks;
        ticksThisFrame = ticks;
        return ticks;
    }

    /**
     * The client's level clock keeps ticking while the loading screen is up, for as many ticks as
     * loading happens to take. Once loaded, set it back to exactly what the recording says for this
     * tick: the last time packet, moved on by the replay ticks since it arrived.
     */
    private void resyncTime() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (filter == null || listener == null) {
            return;
        }
        var packet = filter.lastTimeAdvancedBy(completedTicks - lastTimeTick);
        if (packet != null) {
            listener.handleSetTime(packet);
        }
        // Entities restored during loading ticked on vanilla time, a varying number of times; their
        // tick count drives idle animations (arm sway, bobbing). Start every one from the same count.
        if (mc.level != null) {
            for (var entity : mc.level.entitiesForRendering()) {
                entity.tickCount = 0;
            }
        }
    }

    @Override
    public boolean entitiesFrozen() {
        return phase == Phase.LOADING;
    }

    @Override
    public float partialTick() {
        if (phase != Phase.PLAYING || fastForwardTarget >= 0) {
            return 0.0f;
        }
        double fraction = clock.time() - completedTicks;
        if (fraction <= 0) {
            return 0.0f;
        }
        return fraction >= 1.0 ? Math.nextDown(1.0f) : (float) fraction;
    }

    @Override
    public long textureTicks() {
        return completedTicks;
    }

    @Override
    public float deltaTicks() {
        return ticksThisFrame;
    }

    /** End of every client tick. */
    void onClientTickPost() {
        if (phase != Phase.PLAYING || grantedTicks <= 0) {
            return;
        }
        grantedTicks--;
        completedTicks++;
        applyUpTo(completedTicks, fastForwardTarget >= 0);
        if (connection != null) {
            connection.tick();
        }
        if (driver != null) {
            driver.afterTick(completedTicks);
        }
        if (fastForwardTarget >= 0 && completedTicks >= fastForwardTarget) {
            fastForwardTarget = -1;
        }
    }

    private void applyUpTo(long tick, boolean silent) {
        KinoraFile.RecordCursor c = cursor;
        if (c == null) {
            return;
        }
        StreamRecord next;
        while ((next = c.peek()) != null && next.tick() <= tick) {
            c.next();
            apply(next, silent);
        }
    }

    private void apply(StreamRecord record, boolean silent) {
        try {
            switch (record.kind()) {
                case RecordKind.PACKET -> {
                    if (connection != null) {
                        connection.feed(record.payload());
                        if (filter != null && filter.takeTimeApplied()) {
                            lastTimeTick = completedTicks;
                        }
                    }
                }
                case RecordKind.CLIENT_STATE -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level != null) {
                        ClientState state = ClientState.decode(record.payload(), mc.level.registryAccess());
                        if (state != null) {
                            puppet.apply(state);
                        }
                    }
                }
                case RecordKind.SOUND -> {
                    if (!silent) {
                        SoundRecord sound = SoundRecord.decode(record.payload());
                        if (sound.has(SoundRecord.LOCAL)) {
                            Minecraft.getInstance().getSoundManager().play(new RecordedSoundInstance(sound));
                        }
                    }
                }
                case RecordKind.MOD_DATA -> dev.kinora.mc.api.ModTracks.deliver(record, modTracks, new ReplayTime(completedTicks, 0f));
                default -> {
                    // Markers live in the marker table; unknown kinds are skipped by design.
                }
            }
        } catch (RuntimeException e) {
            applyErrors++;
            if (applyErrors <= 20) {
                KinoraMod.LOG.warn("Kinora skipped a {} record at tick {}", RecordKind.name(record.kind()), record.tick(), e);
            }
        }
    }

    @Override
    public void afterHandled(Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
            puppet.levelChanged();
            setUpCamera(mc);
        } else if (packet instanceof ClientboundStartConfigurationPacket) {
            puppet.levelChanged();
        }
    }

    /** The playback camera: listed as a spectator, flying, untouchable. */
    private void setUpCamera(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientPacketListener listener = mc.getConnection();
        if (player == null || listener == null || mc.level == null) {
            return;
        }
        if (listener.getPlayerInfo(cameraProfile.id()) == null) {
            listener.handlePlayerInfoUpdate(SyntheticPackets.addPlayerInfo(cameraProfile, GameType.SPECTATOR, false, mc.level.registryAccess()));
        }
        player.getAbilities().flying = true;
        player.getAbilities().invulnerable = true;
        player.noPhysics = true;
    }

    // ------------------------------------------------------------------ control

    public void setPaused(boolean paused) {
        if (phase == Phase.LOADING) {
            resumeAfterLoad = !paused;
            return;
        }
        if (!paused && clock.time() >= clock.end()) {
            seek(metadata.startTick);
        }
        clock.setPaused(paused);
    }

    public boolean paused() {
        return phase == Phase.LOADING ? !resumeAfterLoad : clock.paused();
    }

    /**
     * Moves replay time to {@code target} for continuous motion (a shot previewing): forward moves
     * run ticks, backward moves rebuild the world. Returns false if a rebuild was started.
     */
    private boolean follow(double target) {
        double t = Math.max(metadata.startTick, Math.min(target, clock.end()));
        long whole = (long) Math.floor(t);
        if (whole < completedTicks || whole - completedTicks > FORWARD_SEEK_LIMIT) {
            seek(t);
            return false;
        }
        clock.setPaused(true);
        clock.setTime(t);
        return true;
    }

    /**
     * Moves replay time. Short forward jumps run ticks quickly; anything else rebuilds the world
     * from the nearest snapshot.
     */
    public void seek(double targetTicks) {
        seek(targetTicks, false);
    }

    /**
     * @param rebuild always rebuild from the snapshot, even for a short forward jump. Renders start
     *                this way: entities count their own ticks from when they were created (idle
     *                animations follow that count), so the world must be built the same way each time.
     */
    public void seek(double targetTicks, boolean rebuild) {
        double target = Math.max(metadata.startTick, Math.min(targetTicks, clock.end()));
        ReplayTime from = replayTime();
        long whole = (long) Math.floor(target);
        if (!rebuild && whole >= completedTicks && whole - completedTicks <= FORWARD_SEEK_LIMIT && phase == Phase.PLAYING) {
            clock.setTime(target);
            fastForwardTarget = whole > completedTicks + 2 ? whole : -1;
        } else {
            ReplayManager.INSTANCE.restart(this, target);
        }
        // Clamped: a fraction just below 1 can round to 1.0f, which ReplayTime refuses.
        ReplayTime to = new ReplayTime(whole, Math.max(0f, Math.min(Math.nextDown(1f), (float) (target - whole))));
        for (KinoraListener l : Kinora.listeners()) {
            l.onSeek(from, to);
        }
        dev.kinora.mc.api.ModTracks.resetAll();
    }

    /** Rebuild after a restart: same file, new connection, fast-forward to the target time. */
    void restartAt(double target) throws IOException {
        boolean wasPaused = paused();
        if (connection != null) {
            connection.close();
            connection = null;
        }
        startAt((long) Math.floor(target));
        clock.setTime(target);
        resumeAfterLoad = !wasPaused;
    }

    void close() {
        phase = Phase.CLOSED;
        if (connection != null) {
            connection.close();
            connection = null;
        }
        try {
            file.close();
        } catch (IOException ignored) {
            // Nothing to do about a failed close of a read-only file.
        }
    }
}
