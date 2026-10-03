package dev.kinora.mc.capture;

import dev.kinora.core.format.RecordKind;
import dev.kinora.core.format.ReplayMetadata;
import dev.kinora.core.recording.RecordingWriter;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.adapter.PacketIO;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Everything Kinora tracks about one client connection: the state model (always, so a recording
 * can start at any moment), the replay buffer (when enabled) and the recording (when running).
 *
 * <p>Threads: frames and decoded packets arrive on the Netty event loop; ticks, client-state
 * records and control calls come from the game thread. One lock orders them, so the order of
 * records in a file is the order in which the model saw them.
 */
public final class CaptureSession {
    private final Connection connection;
    private final Object lock = new Object();
    private final StateModel model = new StateModel();
    /** Frames captured since the last decoded packet; a bundle spans several. */
    private final List<Captured> pending = new ArrayList<>(4);
    private volatile long sessionTick;
    private @Nullable ReplayBuffer buffer;
    private @Nullable ActiveRecording recording;
    private @Nullable ProtocolInfo<?> playProtocol;
    private boolean modelFailed;
    private volatile boolean closed;
    /** Sounds announced by packets, so the sound listener can tell them from client-made sounds. */
    private final java.util.concurrent.ConcurrentLinkedDeque<PendingSound> packetSounds = new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** A sound packet seen on the network thread, waiting for the game thread to play it. */
    record PendingSound(String event, double x, double y, double z, boolean entityBound, long nanoTime) {}

    CaptureSession(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    public long sessionTick() {
        return sessionTick;
    }

    // ------------------------------------------------------------------ network thread

    /** A frame is about to be decoded. Netty thread. */
    void onFrame(ByteBuf frame) {
        if (closed) {
            return;
        }
        ProtocolInfo<?> protocol = connection.getInboundProtocol();
        int phase = PacketIO.phaseOf(protocol.id());
        if (phase == 0) {
            return;
        }
        Captured captured = new Captured(RecordKind.PACKET, sessionTick, System.nanoTime(), PacketIO.recordPayload(phase, frame));
        pending.add(captured);
    }

    /** A decoded packet is about to be handled. Netty thread. */
    private final EventDetector events = new EventDetector();

    void onPacket(Packet<?> packet) {
        if (closed || pending.isEmpty()) {
            pending.clear();
            return;
        }
        ProtocolInfo<?> protocol = connection.getInboundProtocol();
        List<Captured> frames = new ArrayList<>(pending);
        pending.clear();
        notePacketSounds(packet);
        if (recording != null || buffer != null) {
            events.onPacket(packet);
        }
        synchronized (lock) {
            for (Captured frame : frames) {
                if (buffer != null) {
                    buffer.add(frame);
                }
                if (recording != null) {
                    recording.append(frame);
                }
            }
            if (modelFailed) {
                return;
            }
            try {
                if (protocol.id() == ConnectionProtocol.CONFIGURATION) {
                    for (Captured frame : frames) {
                        model.onConfiguration(frame);
                    }
                } else if (protocol.id() == ConnectionProtocol.PLAY) {
                    playProtocol = protocol;
                    if (packet instanceof BundlePacket<?> bundle) {
                        // Delimiter, sub-packets, delimiter.
                        Iterator<Captured> it = frames.iterator();
                        if (it.hasNext()) {
                            it.next();
                        }
                        for (Packet<?> sub : bundle.subPackets()) {
                            if (!it.hasNext()) {
                                break;
                            }
                            model.onPlayPacket(sub, it.next(), protocol);
                        }
                    } else {
                        model.onPlayPacket(packet, frames.getLast(), protocol);
                    }
                }
            } catch (RuntimeException e) {
                // Recording carries on; only snapshots (seeking, mid-session starts, the buffer) suffer.
                modelFailed = true;
                KinoraMod.LOG.error("Kinora's state model failed on {}; seeking in this recording will replay from the start", packet.type().id(), e);
            }
        }
    }

    private void notePacketSounds(Packet<?> packet) {
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                notePacketSounds(sub);
            }
        } else if (packet instanceof ClientboundSoundPacket p) {
            packetSounds.add(new PendingSound(p.getSound().value().location().toString(), p.getX(), p.getY(), p.getZ(), false, System.nanoTime()));
        } else if (packet instanceof ClientboundSoundEntityPacket p) {
            packetSounds.add(new PendingSound(p.getSound().value().location().toString(), 0, 0, 0, true, System.nanoTime()));
        }
        if (packetSounds.size() > 512) {
            packetSounds.pollFirst();
        }
    }

    /**
     * True if a sound the game is starting was announced by a sound packet, in which case playback
     * regenerates it from that packet. Consumes the match. Game thread.
     */
    boolean claimPacketSound(String event, double x, double y, double z) {
        long now = System.nanoTime();
        java.util.Iterator<PendingSound> it = packetSounds.iterator();
        while (it.hasNext()) {
            PendingSound s = it.next();
            if (now - s.nanoTime() > 5_000_000_000L) {
                it.remove();
                continue;
            }
            if (s.event().equals(event) && (s.entityBound()
                    || (Math.abs(s.x() - x) < 0.2 && Math.abs(s.y() - y) < 0.2 && Math.abs(s.z() - z) < 0.2))) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ game thread

    /** One client tick has completed. Game thread. */
    public void tick() {
        sessionTick++;
    }

    /** Appends a Kinora-generated record (client state, sound, marker, mod data). Game thread. */
    public void append(int kind, byte[] payload, int clientStateType, int modTrack) {
        synchronized (lock) {
            Captured captured = new Captured(kind, sessionTick, System.nanoTime(), payload);
            if (buffer != null) {
                buffer.add(captured);
            }
            if (recording != null) {
                recording.append(captured);
            }
            if (clientStateType >= 0) {
                model.onClientState(clientStateType, captured);
            }
            if (modTrack >= 0) {
                model.onModState(modTrack, captured);
            }
        }
    }

    public boolean isRecording() {
        return recording != null;
    }

    public @Nullable ActiveRecording recording() {
        return recording;
    }

    public boolean hasWorld() {
        synchronized (lock) {
            return model.hasWorld();
        }
    }

    /**
     * Starts writing to a new file. The current state is written first, at tick 0, so the file
     * plays from the moment of the call even when the session started long before.
     */
    public ActiveRecording startRecording(Path path, ReplayMetadata metadata, Consumer<RecordingWriter.FailureReason> onFailure) throws IOException {
        synchronized (lock) {
            if (recording != null) {
                throw new IllegalStateException("already recording");
            }
            if (modelFailed) {
                throw new IOException("Kinora lost track of this session's state and cannot start a recording mid-session. Reconnect to record.");
            }
            ActiveRecording active = ActiveRecording.start(path, metadata, sessionTick, onFailure);
            List<StateModel.Item> state = model.snapshot();
            for (StateModel.Item item : state) {
                switch (item) {
                    case StateModel.Ref ref -> active.appendAt(ref.captured(), 0, 0);
                    case StateModel.Synth synth -> active.appendRaw(synth.kind(), synth.payload(), 0, 0);
                }
            }
            recording = active;
            return active;
        }
    }

    /** Stops the recording and finishes the file. Blocks until written. */
    public @Nullable ActiveRecording stopRecording(ReplayMetadata finalMetadata) throws IOException {
        ActiveRecording active;
        synchronized (lock) {
            active = recording;
            recording = null;
        }
        if (active != null) {
            finalMetadata.endTick = Math.max(finalMetadata.endTick, active.currentTick(sessionTick));
            finalMetadata.durationNanos = active.nanosOf(System.nanoTime());
            active.writer().finish(finalMetadata);
        }
        return active;
    }

    /** Called when the writer stopped by itself: forget it without blocking. */
    void dropRecording(ActiveRecording active) {
        synchronized (lock) {
            if (recording == active) {
                recording = null;
            }
        }
    }

    /** Writes a seek snapshot into the running recording. Game thread. */
    public void snapshot() {
        synchronized (lock) {
            if (recording == null || modelFailed || !model.hasWorld()) {
                return;
            }
            try {
                recording.snapshot(model.snapshot(), sessionTick, System.nanoTime());
            } catch (RuntimeException e) {
                KinoraMod.LOG.warn("Kinora could not write a seek snapshot", e);
            }
        }
    }

    public void enableBuffer(int windowTicks) {
        synchronized (lock) {
            if (buffer == null) {
                buffer = new ReplayBuffer(windowTicks);
            } else {
                buffer.setWindowTicks(windowTicks);
            }
        }
    }

    public void disableBuffer() {
        synchronized (lock) {
            buffer = null;
        }
    }

    public boolean bufferEnabled() {
        return buffer != null;
    }

    /** Marks a buffer snapshot point and trims the buffer. Game thread. */
    public void bufferMark() {
        synchronized (lock) {
            if (buffer == null || modelFailed || !model.hasWorld()) {
                return;
            }
            try {
                buffer.mark(sessionTick, System.nanoTime(), model.snapshot());
            } catch (RuntimeException e) {
                KinoraMod.LOG.warn("Kinora could not take a buffer snapshot", e);
            }
            buffer.trim(sessionTick);
        }
    }

    /** What the buffer holds now, or null if it has nothing usable yet. */
    public ReplayBuffer.@Nullable Contents bufferContents() {
        synchronized (lock) {
            return buffer == null ? null : buffer.contents(sessionTick);
        }
    }

    public long bufferBytes() {
        synchronized (lock) {
            return buffer == null ? 0 : buffer.bytes();
        }
    }

    public String describeModel() {
        synchronized (lock) {
            return model.describe() + (modelFailed ? " (failed)" : "");
        }
    }

    void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }
}
