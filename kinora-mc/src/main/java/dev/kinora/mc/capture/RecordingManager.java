package dev.kinora.mc.capture;

import com.mojang.blaze3d.platform.NativeImage;

import dev.kinora.api.Kinora;
import dev.kinora.api.event.KinoraListener;
import dev.kinora.core.format.Marker;
import dev.kinora.core.format.RecordKind;
import dev.kinora.core.format.ReplayMetadata;
import dev.kinora.core.recording.RecordingWriter;
import dev.kinora.core.render.PngWriter;
import dev.kinora.mc.KinoraConfig;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.util.KinoraPaths;
import dev.kinora.mc.util.Notify;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides when to record and drives recordings on the game thread: start and stop (by hand or on
 * join), per-tick player state, sounds, markers, seek snapshots, thumbnails, the replay buffer, and
 * the free-space safety stop.
 */
public final class RecordingManager implements SoundEventListener {
    public static final RecordingManager INSTANCE = new RecordingManager();

    private static final int BUFFER_MARK_TICKS = 200;
    private static final int DISK_CHECK_TICKS = 100;
    private static final int THUMBNAIL_DELAY_TICKS = 60;

    private final PlayerStateRecorder playerState = new PlayerStateRecorder();
    private @Nullable CaptureSession session;
    private long ticksSinceSnapshot;
    private long ticksSinceBufferMark;
    private long ticksSinceDiskCheck;
    private long recordingTicks;
    private boolean thumbnailTaken;
    private boolean soundListenerInstalled;

    private RecordingManager() {}

    public boolean isRecording() {
        CaptureSession s = session;
        return s != null && s.isRecording();
    }

    /** Ticks recorded so far by the running recording, or 0. */
    public long recordingTicks() {
        return isRecording() ? recordingTicks : 0;
    }

    public @Nullable CaptureSession session() {
        return session;
    }

    // ------------------------------------------------------------------ lifecycle

    /** End of every client tick. */
    public void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (!soundListenerInstalled) {
            mc.getSoundManager().addListener(this);
            soundListenerInstalled = true;
        }
        ClientPacketListener listener = mc.getConnection();
        CaptureSession live = listener == null ? null : CaptureHooks.session(listener.getConnection());
        if (live != session) {
            if (session != null && session.isRecording()) {
                stopAsync("connection changed");
            }
            session = live;
        }
        if (live == null || live.isClosed()) {
            return;
        }
        live.tick();
        boolean recording = live.isRecording();
        if (recording || live.bufferEnabled()) {
            playerState.tick(live, mc);
        }
        if (recording) {
            recordingTicks++;
            if (++ticksSinceSnapshot >= KinoraConfig.snapshotIntervalSeconds() * 20L) {
                ticksSinceSnapshot = 0;
                dev.kinora.mc.api.ModTracks.captureStates();
                live.snapshot();
            }
            if (!thumbnailTaken && recordingTicks >= THUMBNAIL_DELAY_TICKS) {
                thumbnailTaken = true;
                takeThumbnail(live);
            }
            if (++ticksSinceDiskCheck >= DISK_CHECK_TICKS) {
                ticksSinceDiskCheck = 0;
                checkDiskSpace(live);
            }
        }
        if (live.bufferEnabled() && ++ticksSinceBufferMark >= BUFFER_MARK_TICKS) {
            ticksSinceBufferMark = 0;
            if (!recording) {
                dev.kinora.mc.api.ModTracks.captureStates();
            }
            live.bufferMark();
        }
    }

    /** The player joined a world: auto-record and the replay buffer start here. */
    public void onLoggedIn() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        CaptureSession live = listener == null ? null : CaptureHooks.session(listener.getConnection());
        if (live == null) {
            return;
        }
        session = live;
        if (KinoraConfig.replayBuffer()) {
            live.enableBuffer(KinoraConfig.replayBufferSeconds() * 20);
            // The first point lets a save made in the first seconds still start from a full state.
            mc.execute(live::bufferMark);
        }
        if (!live.isRecording() && shouldAutoRecord(mc)) {
            // Let the login packet's world finish loading for one tick before laying down state.
            mc.execute(() -> start(true));
        }
    }

    private static boolean shouldAutoRecord(Minecraft mc) {
        return switch (KinoraConfig.autoRecord()) {
            case OFF -> false;
            case ALWAYS -> notExcluded(mc);
            case SINGLEPLAYER -> mc.hasSingleplayerServer();
            case MULTIPLAYER -> !mc.hasSingleplayerServer() && notExcluded(mc);
        };
    }

    private static boolean notExcluded(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        return server == null || !KinoraConfig.autoRecordExcludedServers().contains(server.ip);
    }

    /** The player is leaving the world: finish the recording. */
    public void onLoggingOut() {
        if (isRecording()) {
            stopAsync("disconnect");
        }
    }

    // ------------------------------------------------------------------ control

    public void toggle() {
        if (isRecording()) {
            stop();
        } else {
            start(false);
        }
    }

    /** Starts recording the current session. */
    public void start(boolean automatic) {
        Minecraft mc = Minecraft.getInstance();
        CaptureSession live = session;
        if (live == null || mc.level == null) {
            Notify.warn(Component.translatable("kinora.recording.cannot_start"), Component.translatable("kinora.recording.not_in_world"));
            return;
        }
        if (live.isRecording()) {
            return;
        }
        Path path = KinoraPaths.newReplayFile(MetadataFactory.label(mc));
        if (!hasFreeSpace(path.getParent())) {
            Notify.warn(Component.translatable("kinora.recording.cannot_start"),
                    Component.translatable("kinora.recording.disk_low", KinoraConfig.minFreeDiskBytes() / (1024 * 1024)));
            return;
        }
        ReplayMetadata metadata = MetadataFactory.create(mc, ReplayMetadata.Kind.RECORDING);
        try {
            ActiveRecording active = live.startRecording(path, metadata, reason -> mc.execute(() -> onWriterFailure(live, reason)));
            // Other mods' data tracks: the id table, and their state now, so the replay starts from it.
            active.writer().modTracks(dev.kinora.mc.api.ModTracks.table());
            dev.kinora.mc.api.ModTracks.captureStates();
            playerState.reset();
            playerState.tick(live, mc);
            recordingTicks = 0;
            ticksSinceSnapshot = 0;
            thumbnailTaken = false;
            KinoraMod.LOG.info("Recording to {} ({})", active.writer().path(), live.describeModel());
            Notify.info(Component.translatable(automatic ? "kinora.recording.started_auto" : "kinora.recording.started"),
                    Component.literal(path.getFileName().toString()));
            for (KinoraListener l : Kinora.listeners()) {
                l.onRecordingStart();
            }
        } catch (IOException | RuntimeException e) {
            KinoraMod.LOG.error("Could not start recording", e);
            Notify.warn(Component.translatable("kinora.recording.cannot_start"), Component.literal(String.valueOf(e.getMessage())));
        }
    }

    /** Stops and finishes the recording, waiting for the file to be complete. */
    public void stop() {
        CaptureSession live = session;
        if (live == null || !live.isRecording()) {
            return;
        }
        ReplayMetadata finalMetadata = live.recording().metadata().copy();
        try {
            ActiveRecording done = live.stopRecording(finalMetadata);
            if (done != null) {
                Notify.info(Component.translatable("kinora.recording.saved"), Component.literal(done.writer().path().getFileName().toString()));
            }
        } catch (IOException e) {
            KinoraMod.LOG.error("Could not finish recording", e);
            Notify.warn(Component.translatable("kinora.recording.failed"), Component.translatable("kinora.recording.recoverable"));
        }
        notifyStopped();
    }

    /** Stops without blocking the game thread (disconnects); the file is finished in the background. */
    private void stopAsync(String why) {
        CaptureSession live = session;
        if (live == null || !live.isRecording()) {
            return;
        }
        ReplayMetadata finalMetadata = live.recording().metadata().copy();
        Thread.ofPlatform().name("Kinora finish").start(() -> {
            try {
                ActiveRecording done = live.stopRecording(finalMetadata);
                if (done != null) {
                    KinoraMod.LOG.info("Recording finished ({}): {}", why, done.writer().path());
                }
            } catch (IOException e) {
                KinoraMod.LOG.error("Could not finish recording", e);
            }
        });
        notifyStopped();
    }

    private void notifyStopped() {
        for (KinoraListener l : Kinora.listeners()) {
            l.onRecordingStop();
        }
    }

    private void onWriterFailure(CaptureSession live, RecordingWriter.FailureReason reason) {
        ActiveRecording active = live.recording();
        if (active != null) {
            live.dropRecording(active);
        }
        Component why = reason == RecordingWriter.FailureReason.QUEUE_OVERFLOW
                ? Component.translatable("kinora.recording.too_slow")
                : Component.translatable("kinora.recording.io_error");
        Notify.warn(Component.translatable("kinora.recording.stopped"), why);
        notifyStopped();
    }

    /** Drops a named marker at the current moment. */
    public void marker(String name, int color, String category, int source) {
        CaptureSession live = session;
        if (live == null || !(live.isRecording() || live.bufferEnabled())) {
            return;
        }
        Marker marker = new Marker(0, 0, name, color, category, source);
        live.append(RecordKind.MARKER, marker.encode(), -1, -1);
        if (source == Marker.SOURCE_MANUAL) {
            Notify.info(Component.translatable("kinora.marker.added"), Component.literal(name));
        }
    }

    /** Writes a mod data record (API). Returns false when nothing is capturing. */
    public boolean modData(int trackIndex, int flags, byte[] data) {
        CaptureSession live = session;
        if (live == null || !(live.isRecording() || live.bufferEnabled())) {
            return false;
        }
        dev.kinora.core.io.ByteSink out = new dev.kinora.core.io.ByteSink(data.length + 8);
        out.writeVarInt(trackIndex).writeByte(flags).writeBytes(data);
        live.append(RecordKind.MOD_DATA, out.toByteArray(), -1, (flags & RecordKind.MOD_DATA_FLAG_STATE) != 0 ? trackIndex : -1);
        return true;
    }

    /** Saves the replay buffer to a new file ("clip that"). */
    public void saveBuffer() {
        Minecraft mc = Minecraft.getInstance();
        CaptureSession live = session;
        if (live == null || !live.bufferEnabled()) {
            Notify.warn(Component.translatable("kinora.buffer.unavailable"), Component.translatable("kinora.buffer.enable_hint"));
            return;
        }
        ReplayBuffer.Contents contents = live.bufferContents();
        if (contents == null) {
            Notify.warn(Component.translatable("kinora.buffer.unavailable"), Component.translatable("kinora.buffer.empty"));
            return;
        }
        Path path = KinoraPaths.newReplayFile(MetadataFactory.label(mc) + "_clip");
        ReplayMetadata metadata = MetadataFactory.create(mc, ReplayMetadata.Kind.BUFFER);
        long nowTick = live.sessionTick();
        Thread.ofPlatform().name("Kinora buffer save").start(() -> {
            try {
                writeBuffer(path, metadata, contents, nowTick);
                Notify.info(Component.translatable("kinora.buffer.saved"), Component.literal(path.getFileName().toString()));
            } catch (IOException | RuntimeException e) {
                KinoraMod.LOG.error("Could not save the replay buffer", e);
                Notify.warn(Component.translatable("kinora.buffer.failed"), Component.literal(String.valueOf(e.getMessage())));
            }
        });
    }

    private static void writeBuffer(Path path, ReplayMetadata metadata, ReplayBuffer.Contents contents, long nowSessionTick) throws IOException {
        RecordingWriter writer = RecordingWriter.start(path, metadata, RecordingWriter.Options.defaults(), null);
        writer.modTracks(dev.kinora.mc.api.ModTracks.table());
        long startTick = contents.startSessionTick();
        long startNanos = contents.startNanoTime();
        for (StateModel.Item item : contents.state()) {
            switch (item) {
                case StateModel.Ref ref -> writer.append(ref.captured().kind, 0, 0, ref.captured().payload);
                case StateModel.Synth synth -> writer.append(synth.kind(), 0, 0, synth.payload());
            }
        }
        for (Captured c : contents.records()) {
            writer.append(c.kind, Math.max(0, c.sessionTick - startTick), Math.max(0, c.nanoTime - startNanos), c.payload);
        }
        metadata.endTick = Math.max(0, nowSessionTick - startTick);
        writer.finish(metadata);
    }

    // ------------------------------------------------------------------ sounds

    @Override
    public void onPlaySound(SoundInstance sound, WeighedSoundEvents soundEvent, float range) {
        CaptureSession live = session;
        if (live == null || !(live.isRecording() || live.bufferEnabled())) {
            return;
        }
        SoundSource source = sound.getSource();
        if (source == SoundSource.UI || source == SoundSource.MUSIC || sound.getSound() == null) {
            return;
        }
        String event = sound.getIdentifier().toString();
        boolean fromPacket = live.claimPacketSound(event, sound.getX(), sound.getY(), sound.getZ());
        int flags = (fromPacket ? 0 : SoundRecord.LOCAL)
                | (sound.isRelative() ? SoundRecord.RELATIVE : 0)
                | (sound.isLooping() ? SoundRecord.LOOPING : 0)
                | (sound.getAttenuation() == SoundInstance.Attenuation.LINEAR ? SoundRecord.LINEAR : 0)
                | (sound.getSound().shouldStream() ? SoundRecord.STREAMED : 0);
        SoundRecord record = new SoundRecord(event, sound.getSound().getLocation().toString(), source.getName(),
                sound.getX(), sound.getY(), sound.getZ(), sound.getVolume(), sound.getPitch(), Math.round(range), flags, 0);
        live.append(RecordKind.SOUND, record.encode(), -1, -1);
    }

    // ------------------------------------------------------------------ housekeeping

    private void takeThumbnail(CaptureSession live) {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.gameRenderer.mainRenderTarget().width;
        int factor = Math.max(1, width / 320);
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), factor, image -> {
            try (NativeImage img = image) {
                int w = img.getWidth();
                int h = img.getHeight();
                int[] argb = new int[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int abgr = img.getPixel(x, y);
                        argb[y * w + x] = (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >>> 16) & 0xFF);
                    }
                }
                ActiveRecording active = live.recording();
                if (active != null) {
                    active.writer().thumbnail(active.lastTick(), PngWriter.encodeArgb(w, h, argb, false, 6));
                }
            } catch (RuntimeException e) {
                KinoraMod.LOG.warn("Could not capture a thumbnail", e);
            }
        });
    }

    private void checkDiskSpace(CaptureSession live) {
        ActiveRecording active = live.recording();
        if (active == null) {
            return;
        }
        if (!hasFreeSpace(active.writer().path().getParent())) {
            Notify.warn(Component.translatable("kinora.recording.stopped"),
                    Component.translatable("kinora.recording.disk_low", KinoraConfig.minFreeDiskBytes() / (1024 * 1024)));
            stopAsync("low disk space");
        }
    }

    private static boolean hasFreeSpace(Path dir) {
        try {
            FileStore store = Files.getFileStore(dir);
            return store.getUsableSpace() >= KinoraConfig.minFreeDiskBytes();
        } catch (IOException e) {
            return true;
        }
    }

    /** For diagnostics and tests. */
    public @Nullable String describe() {
        CaptureSession live = session;
        return live == null ? null : live.describeModel();
    }
}
