package dev.kinora.mc.playback;

import com.mojang.authlib.GameProfile;

import dev.kinora.mc.capture.CaptureHooks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.telemetry.TelemetryEventSender;
import net.minecraft.client.telemetry.WorldSessionTelemetryManager;
import net.minecraft.network.Connection;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.server.ServerLinks;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.Map;
import java.util.UUID;

/**
 * A client connection with no server behind it. Minecraft's own pipeline (frame splitter, packet
 * decoder, bundler, packet handler) runs on a Netty {@link EmbeddedChannel}; recorded frames are
 * written into it on the game thread, so every handler runs inline and protocol switches complete
 * before the next frame. What the client sends goes nowhere.
 *
 * <p>The channel is not a {@code LocalChannel}, so the client treats this like a remote server:
 * registry tags and components from the recording are applied in full.
 */
public final class PlaybackConnection {
    private final EmbeddedChannel channel;
    private final Connection connection;

    private PlaybackConnection(EmbeddedChannel channel, Connection connection) {
        this.channel = channel;
        this.connection = connection;
    }

    /**
     * Builds the connection and puts it in the configuration phase, as if login had just finished.
     *
     * @param camera  identity of the local player in playback: a fresh UUID, never the viewer's own
     * @param server  the synthetic server entry the client sees
     */
    public static PlaybackConnection open(Minecraft minecraft, GameProfile camera, ServerData server, PlaybackFilter filter) {
        return CaptureHooks.withoutCapture(() -> {
            Connection connection = new Connection(PacketFlow.CLIENTBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(false, false);
            ChannelPipeline pipeline = channel.pipeline();
            Connection.configureSerialization(pipeline, PacketFlow.CLIENTBOUND, false, null);
            // A live client's encoder starts on the handshake protocol and is swapped out once the
            // handshake has been sent. Playback skips the handshake, so start unconfigured instead,
            // which is the state setupOutboundProtocol expects.
            pipeline.replace("encoder", "outbound_config", new UnconfiguredPipelineHandler.Outbound());
            connection.configurePacketHandler(pipeline);
            pipeline.addBefore("packet_handler", "kinora:playback", filter);
            try {
                channel.register();
            } catch (Exception e) {
                throw new IllegalStateException("could not register the playback channel", e);
            }
            filter.bind(connection);
            CommonListenerCookie cookie = new CommonListenerCookie(
                    new LevelLoadTracker(),
                    camera,
                    // Replays are not play sessions: no telemetry events.
                    new WorldSessionTelemetryManager(TelemetryEventSender.DISABLED, false, null, null, UUID.randomUUID()),
                    ClientRegistryLayer.createRegistryAccess().compositeAccess(),
                    FeatureFlags.DEFAULT_FLAGS,
                    null,
                    server,
                    null,
                    Map.of(),
                    null,
                    Map.of(),
                    ServerLinks.EMPTY,
                    Map.of(),
                    false,
                    ConnectionType.OTHER);
            connection.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, new ClientConfigurationPacketListenerImpl(minecraft, connection, cookie));
            connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
            PlaybackConnection playback = new PlaybackConnection(channel, connection);
            playback.drainOutbound();
            return playback;
        });
    }

    public Connection connection() {
        return connection;
    }

    /**
     * Feeds one PACKET record payload (phase byte, then the frame). Game thread only; the packet is
     * decoded and handled before this returns.
     */
    public void feed(byte[] recordPayload) {
        int frameLength = recordPayload.length - 1;
        ByteBuf buf = Unpooled.buffer(frameLength + 5);
        int value = frameLength;
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
        buf.writeBytes(recordPayload, 1, frameLength);
        channel.writeInbound(buf);
        drainOutbound();
        // Anything the pipeline passed all the way through (it should not) is released.
        Object leftover;
        while ((leftover = channel.readInbound()) != null) {
            ReferenceCountUtil.release(leftover);
        }
    }

    /** Ticks the connection like Minecraft does for a live one (flushes, ticks the listener). */
    public void tick() {
        connection.tick();
        drainOutbound();
    }

    private void drainOutbound() {
        Object out;
        while ((out = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(out);
        }
    }

    public boolean isOpen() {
        return channel.isOpen() && connection.isConnected();
    }

    public void close() {
        drainOutbound();
        channel.close();
    }
}
