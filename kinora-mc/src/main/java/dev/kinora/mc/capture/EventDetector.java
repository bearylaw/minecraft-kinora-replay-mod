package dev.kinora.mc.capture;

import dev.kinora.core.format.Marker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Marks the moments worth finding again while recording: the player's deaths, kills, bursts of
 * damage taken, explosions, chat, advancements, dimension changes and mining streaks. Markers carry
 * {@link Marker#SOURCE_DETECTED}; the replay bar shows them and N / M jump between them.
 *
 * <p>Packets arrive on the network thread; everything is decided on the game thread, after the
 * packet, where the level can be asked about the entities involved.
 */
final class EventDetector {
    private static final int RED = 0xFFD55E00;
    private static final int ORANGE = 0xFFE69F00;
    private static final int YELLOW = 0xFFF0E442;
    private static final int BLUE = 0xFF56B4E9;
    private static final int GREEN = 0xFF009E73;
    private static final int PURPLE = 0xFFCC79A7;

    private final Map<String, Long> lastByKind = new HashMap<>();
    /** Entities the player hurt, and when: a death soon after is the player's kill. */
    private final Map<Integer, Long> hitByPlayer = new HashMap<>();
    private final ArrayDeque<Long> damageTaken = new ArrayDeque<>();
    private final ArrayDeque<Long> blocksBroken = new ArrayDeque<>();

    /** Network thread: look at a packet; anything interesting is decided on the game thread. */
    void onPacket(Packet<?> packet) {
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                onPacket(sub);
            }
            return;
        }
        if (packet instanceof ClientboundDamageEventPacket || packet instanceof ClientboundEntityEventPacket
                || packet instanceof ClientboundPlayerCombatKillPacket || packet instanceof ClientboundExplodePacket
                || packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundBlockUpdatePacket
                || packet instanceof ClientboundSystemChatPacket || packet instanceof ClientboundPlayerChatPacket
                || packet instanceof ClientboundUpdateAdvancementsPacket) {
            Minecraft.getInstance().execute(() -> decide(packet));
        }
    }

    private void decide(Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int self = mc.player.getId();
        switch (packet) {
            case ClientboundPlayerCombatKillPacket p when p.playerId() == self -> mark("death", 0, "Death", RED);
            case ClientboundDamageEventPacket p -> {
                if (p.entityId() == self) {
                    // Several hits within three seconds: a fight worth seeing.
                    damageTaken.addLast(now);
                    while (!damageTaken.isEmpty() && now - damageTaken.peekFirst() > 3000) {
                        damageTaken.removeFirst();
                    }
                    if (damageTaken.size() >= 3) {
                        mark("damage", 10_000, "Taking damage", ORANGE);
                    }
                } else if (p.sourceCauseId() == self || p.sourceDirectId() == self) {
                    hitByPlayer.put(p.entityId(), now);
                }
            }
            case ClientboundEntityEventPacket p when p.getEventId() == 3 -> {
                // Entity event 3 is a death.
                Entity e = p.getEntity(level);
                Long hit = e == null ? null : hitByPlayer.remove(e.getId());
                if (e instanceof LivingEntity && hit != null && now - hit < 5000) {
                    mark("kill", 0, "Kill: " + e.getName().getString(), RED);
                }
            }
            case ClientboundExplodePacket p -> {
                if (p.center().distanceTo(mc.player.position()) < 48) {
                    mark("explosion", 3000, "Explosion", ORANGE);
                }
            }
            case ClientboundRespawnPacket p -> {
                if (!p.commonPlayerSpawnInfo().dimension().equals(level.dimension())) {
                    mark("dimension", 0, "Entered " + p.commonPlayerSpawnInfo().dimension().identifier().getPath().replace('_', ' '), PURPLE);
                }
            }
            case ClientboundBlockUpdatePacket p -> {
                if (p.getBlockState().isAir() && !level.getBlockState(p.getPos()).isAir()
                        && net.minecraft.world.phys.Vec3.atCenterOf(p.getPos()).distanceTo(mc.player.position()) < 8) {
                    blocksBroken.addLast(now);
                    while (!blocksBroken.isEmpty() && now - blocksBroken.peekFirst() > 10_000) {
                        blocksBroken.removeFirst();
                    }
                    if (blocksBroken.size() >= 12) {
                        mark("mining", 30_000, "Mining streak", YELLOW);
                    }
                }
            }
            case ClientboundSystemChatPacket p when !p.overlay() -> chat(p.content());
            case ClientboundPlayerChatPacket p -> chat(p.unsignedContent() != null ? p.unsignedContent() : Component.literal(p.body().content()));
            case ClientboundUpdateAdvancementsPacket p when !p.shouldReset() -> {
                var advancements = mc.getConnection() == null ? null : mc.getConnection().getAdvancements();
                for (var entry : p.getProgress().entrySet()) {
                    if (!entry.getValue().isDone() || advancements == null) {
                        continue;
                    }
                    var holder = advancements.get(entry.getKey());
                    var display = holder == null ? null : holder.value().display().orElse(null);
                    if (display != null && display.shouldAnnounceChat()) {
                        mark("advancement", 0, "Advancement: " + display.getTitle().getString(), GREEN);
                    }
                }
            }
            default -> {
            }
        }
    }

    private void chat(Component message) {
        String text = message.getString().strip();
        if (!text.isEmpty()) {
            mark("chat", 2000, "Chat: " + (text.length() > 40 ? text.substring(0, 40) + "..." : text), BLUE);
        }
    }

    /** Adds a marker unless one of the same kind was added within {@code quietMillis}. */
    private void mark(String kind, long quietMillis, String name, int color) {
        long now = System.currentTimeMillis();
        Long last = lastByKind.get(kind);
        if (last != null && now - last < quietMillis) {
            return;
        }
        lastByKind.put(kind, now);
        RecordingManager.INSTANCE.marker(name, color, kind, Marker.SOURCE_DETECTED);
    }
}
