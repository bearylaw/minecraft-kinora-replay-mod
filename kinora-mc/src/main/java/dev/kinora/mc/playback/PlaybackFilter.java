package dev.kinora.mc.playback;

import dev.kinora.mc.KinoraMod;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.world.level.GameType;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Sits in front of the playback connection's packet handler and decides what the client sees.
 *
 * <ul>
 * <li>The camera (the local player of playback) gets a reserved entity id and spectator mode, so
 *     the recorded player's id is free for the puppet that stands in for them.</li>
 * <li>Packets that act on "the local player" in a live game (teleports, health, inventories, menus,
 *     death screen, resource-pack prompts, transfers) are dropped: they were about the recording
 *     player, who is now the puppet.</li>
 * <li>Signed player chat becomes unsigned chat, so seeking never trips the chat index check.</li>
 * </ul>
 *
 * <p>It also handles the packets itself, one at a time inside a try/catch: a packet that fails is
 * logged and skipped instead of disconnecting the replay, which is what Minecraft's own handler
 * would do.
 */
public final class PlaybackFilter extends ChannelInboundHandlerAdapter {
    /** Entity id of the playback camera. Vanilla servers count ids up from 1 and never reach it. */
    public static final int CAMERA_ENTITY_ID = Integer.MAX_VALUE - 1024;

    /** Told about every packet after the client handled it. */
    public interface Observer {
        void afterHandled(Packet<?> packet);
    }

    private final Observer observer;
    private @Nullable Connection connection;
    private int recordedPlayerId = Integer.MIN_VALUE;
    private int errors;
    private int decodeErrors;

    public PlaybackFilter(Observer observer) {
        this.observer = observer;
    }

    void bind(Connection connection) {
        this.connection = connection;
    }

    /** The recording player's entity id, from the last login packet; MIN_VALUE before one arrived. */
    public int recordedPlayerId() {
        return recordedPlayerId;
    }

    public int errors() {
        return errors + decodeErrors;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof Packet<?> packet)) {
            ctx.fireChannelRead(msg);
            return;
        }
        Packet<?> filtered = filter(packet);
        if (filtered != null) {
            handle(filtered);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // A frame that does not decode is skipped; the replay goes on.
        decodeErrors++;
        if (decodeErrors <= 20) {
            KinoraMod.LOG.warn("Kinora skipped a recorded packet that does not decode: {}", cause.toString());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handle(Packet<?> packet) {
        Connection c = connection;
        PacketListener listener = c == null ? null : c.getPacketListener();
        if (listener == null || !listener.shouldHandleMessage(packet)) {
            return;
        }
        try {
            ((Packet) packet).handle(listener);
        } catch (RunningOnDifferentThreadException ignored) {
            // Queued for the game thread by the handler; it will run there.
        } catch (Throwable t) {
            errors++;
            if (errors <= 20) {
                KinoraMod.LOG.warn("Kinora skipped a recorded {} that failed to apply", packet.type().id(), t);
            }
            return;
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                observer.afterHandled(sub);
            }
        } else {
            observer.afterHandled(packet);
        }
    }

    /**
     * Ticks between when a time packet was recorded and when it is applied. A snapshot restores the
     * last time packet before it, which can be up to a second old; without this the world's clock
     * (clouds, sky, day time) would run behind until the next time packet.
     */
    private long timeAdvance;

    void setTimeAdvance(long ticks) {
        timeAdvance = ticks;
    }

    /** The last time packet the client saw, as it saw it. */
    private @Nullable ClientboundSetTimePacket lastTime;
    private boolean lastTimeApplied;

    /** Takes the "a time packet went through" flag, so the session can note the tick it happened at. */
    boolean takeTimeApplied() {
        boolean applied = lastTimeApplied;
        lastTimeApplied = false;
        return applied;
    }

    /** The last time packet, moved on by {@code ticks}; null if none came yet. */
    @Nullable ClientboundSetTimePacket lastTimeAdvancedBy(long ticks) {
        return lastTime == null ? null : ticks <= 0 ? lastTime : advance(lastTime, ticks);
    }

    static ClientboundSetTimePacket advance(ClientboundSetTimePacket p, long ticks) {
        java.util.Map<net.minecraft.core.Holder<net.minecraft.world.clock.WorldClock>, net.minecraft.world.clock.ClockNetworkState> clocks =
                new java.util.HashMap<>();
        p.clockUpdates().forEach((clock, state) -> {
            double advanced = state.totalTicks() + state.partialTick() + ticks * (double) state.rate();
            long whole = (long) Math.floor(advanced);
            clocks.put(clock, new net.minecraft.world.clock.ClockNetworkState(whole, (float) (advanced - whole), state.rate()));
        });
        return new ClientboundSetTimePacket(p.gameTime() + ticks, clocks);
    }

    /** The packet the client should see instead of {@code packet}, or null to drop it. */
    @Nullable Packet<?> filter(Packet<?> packet) {
        return switch (packet) {
            case ClientboundSetTimePacket p -> {
                lastTime = timeAdvance > 0 ? advance(p, timeAdvance) : p;
                lastTimeApplied = true;
                yield lastTime;
            }
            case ClientboundBundlePacket bundle -> {
                List<Packet<? super ClientGamePacketListener>> kept = new ArrayList<>();
                for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                    @SuppressWarnings("unchecked")
                    Packet<? super ClientGamePacketListener> f = (Packet<? super ClientGamePacketListener>) filter(sub);
                    if (f != null) {
                        kept.add(f);
                    }
                }
                yield kept.isEmpty() ? null : new ClientboundBundlePacket(kept);
            }
            case ClientboundLoginPacket p -> {
                recordedPlayerId = p.playerId();
                yield new ClientboundLoginPacket(CAMERA_ENTITY_ID, false, p.levels(), p.maxPlayers(), p.chunkRadius(), p.simulationDistance(),
                        false, false, p.doLimitedCrafting(), spectator(p.commonPlayerSpawnInfo()), false, false);
            }
            case ClientboundRespawnPacket p -> new ClientboundRespawnPacket(spectator(p.commonPlayerSpawnInfo()), p.dataToKeep());
            case ClientboundPlayerChatPacket p -> new ClientboundDisguisedChatPacket(
                    p.unsignedContent() != null ? p.unsignedContent() : net.minecraft.network.chat.Component.literal(p.body().content()), p.chatType());
            case ClientboundGameEventPacket p -> keepGameEvent(p) ? p : null;

            // The local player in a live game is the recording player, who is the puppet now.
            case ClientboundPlayerPositionPacket p -> null;
            case ClientboundPlayerRotationPacket p -> null;
            case ClientboundPlayerLookAtPacket p -> null;
            case ClientboundSetHealthPacket p -> null;
            case ClientboundSetExperiencePacket p -> null;
            case ClientboundPlayerAbilitiesPacket p -> null;
            case ClientboundSetCameraPacket p -> null;
            case ClientboundMoveVehiclePacket p -> null;
            case ClientboundPlayerCombatKillPacket p -> null;
            case ClientboundPlayerCombatEnterPacket p -> null;
            case ClientboundPlayerCombatEndPacket p -> null;
            case ClientboundContainerSetContentPacket p -> null;
            case ClientboundContainerSetSlotPacket p -> null;
            case ClientboundContainerSetDataPacket p -> null;
            case ClientboundContainerClosePacket p -> null;
            case ClientboundOpenScreenPacket p -> null;
            case ClientboundOpenBookPacket p -> null;
            case ClientboundOpenSignEditorPacket p -> null;
            case ClientboundMountScreenOpenPacket p -> null;
            case ClientboundMerchantOffersPacket p -> null;
            case ClientboundSetCursorItemPacket p -> null;
            case ClientboundSetPlayerInventoryPacket p -> null;
            case ClientboundSetHeldSlotPacket p -> null;
            case ClientboundCooldownPacket p -> null;
            case ClientboundPlaceGhostRecipePacket p -> null;
            case ClientboundRecipeBookAddPacket p -> null;
            case ClientboundRecipeBookRemovePacket p -> null;
            case ClientboundRecipeBookSettingsPacket p -> null;
            case ClientboundUpdateAdvancementsPacket p -> null;
            case ClientboundSelectAdvancementsTabPacket p -> null;
            case ClientboundAwardStatsPacket p -> null;
            case ClientboundCommandSuggestionsPacket p -> null;
            case ClientboundCustomChatCompletionsPacket p -> null;
            case ClientboundTagQueryPacket p -> null;
            case ClientboundDeleteChatPacket p -> null;

            // A replay never leaves, downloads, prompts or answers.
            case ClientboundDisconnectPacket p -> null;
            case ClientboundTransferPacket p -> null;
            case ClientboundResourcePackPushPacket p -> null;
            case ClientboundResourcePackPopPacket p -> null;
            case ClientboundShowDialogPacket p -> null;
            case ClientboundClearDialogPacket p -> null;
            case ClientboundCodeOfConductPacket p -> null;
            case ClientboundKeepAlivePacket p -> null;
            case ClientboundPingPacket p -> null;
            default -> packet;
        };
    }

    private static boolean keepGameEvent(ClientboundGameEventPacket p) {
        ClientboundGameEventPacket.Type type = p.getEvent();
        return type == ClientboundGameEventPacket.START_RAINING
                || type == ClientboundGameEventPacket.STOP_RAINING
                || type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE
                || type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE
                || type == ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START;
    }

    private static CommonPlayerSpawnInfo spectator(CommonPlayerSpawnInfo info) {
        return new CommonPlayerSpawnInfo(info.dimensionType(), info.dimension(), info.seed(), GameType.SPECTATOR, info.previousGameType(),
                info.isDebug(), info.isFlat(), info.lastDeathLocation(), info.portalCooldown(), info.seaLevel());
    }
}
