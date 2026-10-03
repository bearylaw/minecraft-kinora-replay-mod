package dev.kinora.mc.capture;

import dev.kinora.mc.KinoraMod;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Attaches Kinora's two taps to every client connection's pipeline:
 *
 * <ul>
 * <li>{@code kinora:frames}, directly before the packet decoder, sees each frame as bytes;</li>
 * <li>{@code kinora:packets}, directly before the packet handler, sees the decoded packet.</li>
 * </ul>
 *
 * Both run on the connection's event loop, in that order, within the same read, so the frames a
 * decoded packet came from are exactly those captured since the previous packet.
 */
public final class CaptureHooks {
    private static final Map<Connection, CaptureSession> SESSIONS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> SUPPRESS = ThreadLocal.withInitial(() -> false);

    private CaptureHooks() {}

    /** Called from {@code ConnectionMixin} at the end of {@code Connection.configurePacketHandler}. */
    public static void attach(Connection connection, ChannelPipeline pipeline) {
        if (SUPPRESS.get() || connection.getReceiving() != PacketFlow.CLIENTBOUND) {
            return;
        }
        if (pipeline.get("inbound_config") == null && pipeline.get("decoder") == null) {
            return;
        }
        try {
            CaptureSession session = new CaptureSession(connection);
            String decoderName = pipeline.get("inbound_config") != null ? "inbound_config" : "decoder";
            pipeline.addBefore(decoderName, "kinora:frames", new FrameTap(session));
            pipeline.addBefore("packet_handler", "kinora:packets", new PacketTap(session));
            SESSIONS.put(connection, session);
        } catch (RuntimeException e) {
            KinoraMod.LOG.error("Kinora could not attach to a connection; it will not be recordable", e);
        }
    }

    /** Runs {@code action} with capture disabled for connections created on this thread (playback). */
    public static <T> T withoutCapture(java.util.function.Supplier<T> action) {
        boolean previous = SUPPRESS.get();
        SUPPRESS.set(true);
        try {
            return action.get();
        } finally {
            SUPPRESS.set(previous);
        }
    }

    public static @Nullable CaptureSession session(@Nullable Connection connection) {
        return connection == null ? null : SESSIONS.get(connection);
    }

    private static final class FrameTap extends ChannelInboundHandlerAdapter {
        private final CaptureSession session;
        private boolean failed;

        FrameTap(CaptureSession session) {
            this.session = session;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!failed && msg instanceof ByteBuf frame) {
                try {
                    session.onFrame(frame);
                } catch (Throwable t) {
                    failed = true;
                    KinoraMod.LOG.error("Kinora stopped capturing this connection", t);
                }
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            session.close();
            super.channelInactive(ctx);
        }
    }

    private static final class PacketTap extends ChannelInboundHandlerAdapter {
        private final CaptureSession session;
        private boolean failed;

        PacketTap(CaptureSession session) {
            this.session = session;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!failed && msg instanceof Packet<?> packet) {
                try {
                    session.onPacket(packet);
                } catch (Throwable t) {
                    failed = true;
                    KinoraMod.LOG.error("Kinora stopped capturing this connection", t);
                }
            }
            super.channelRead(ctx, msg);
        }
    }
}
