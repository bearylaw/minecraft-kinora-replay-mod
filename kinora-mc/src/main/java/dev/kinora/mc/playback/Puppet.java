package dev.kinora.mc.playback;

import com.mojang.authlib.GameProfile;

import dev.kinora.mc.adapter.SyntheticPackets;
import dev.kinora.mc.capture.ClientState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The recording player, re-created as an ordinary remote player and driven from CLIENT_STATE
 * records. The server never sent the recording player its own movement, held items or swings, so
 * without the puppet the person who recorded would be missing from their own replay.
 */
final class Puppet {
    private @Nullable UUID uuid;
    private @Nullable String name;
    private int entityId = Integer.MIN_VALUE;
    private ClientState.@Nullable Player last;
    private ClientState.@Nullable Equipment equipment;
    /** The recording player's synced values (skin layers, pose...), applied to the puppet. */
    private ClientState.@Nullable EntityData data;
    private boolean spawned;
    /** The puppet's player-list entry is Kinora's stand-in, made before the server's arrived. */
    private boolean syntheticInfo;
    /**
     * Mods' synced data on the recording player (NeoForge attachments: a robe, a title...), the latest
     * of each kind. The server sends it once, at join: it is in a recording's opening state but not in
     * the snapshots written later, and often arrives before the puppet exists. So it is kept for the
     * whole session, across seeks, and applied every time the puppet spawns.
     */
    private final Map<String, Packet<?>> attachments = new LinkedHashMap<>();

    /** The level was rebuilt (respawn into another dimension, seek): spawn again on the next state. */
    void levelChanged() {
        spawned = false;
    }

    void reset() {
        uuid = null;
        name = null;
        entityId = Integer.MIN_VALUE;
        last = null;
        equipment = null;
        data = null;
        spawned = false;
        syntheticInfo = false;
    }

    /** Remembers a mod's synced data aimed at the recording player; see {@link #attachments}. */
    void rememberAttachments(String kind, Packet<?> packet) {
        attachments.remove(kind);
        attachments.put(kind, packet);
    }

    /**
     * The server's own player-list entry for the recording player arrived after the puppet was given
     * a stand-in one (a snapshot restores Kinora's records first): the stand-in has no skin, so it is
     * dropped for the real one and the puppet spawned again with it.
     */
    void realInfoArrived(ClientPacketListener listener, Packet<?> addPlayer) {
        if (!syntheticInfo || uuid == null) {
            return;
        }
        syntheticInfo = false;
        listener.handlePlayerInfoRemove(new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(java.util.List.of(uuid)));
        if (addPlayer instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket update) {
            listener.handlePlayerInfoUpdate(update);
        }
        spawned = false;
        if (last != null) {
            ensureSpawned();
        }
    }

    @Nullable UUID uuid() {
        return uuid;
    }

    /** The puppet entity, if it is in the level. */
    @Nullable Player entity() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || entityId == Integer.MIN_VALUE) {
            return null;
        }
        Entity e = level.getEntity(entityId);
        return e instanceof Player p ? p : null;
    }

    int entityId() {
        return entityId;
    }

    ClientState.@Nullable Player lastState() {
        return last;
    }

    void apply(ClientState state) {
        switch (state) {
            case ClientState.Identity id -> {
                if (entityId != id.entityId() || !id.uuid().equals(uuid)) {
                    spawned = false;
                }
                uuid = id.uuid();
                name = id.name();
                entityId = id.entityId();
            }
            case ClientState.Player p -> {
                last = p;
                if (entityId == Integer.MIN_VALUE) {
                    entityId = p.entityId();
                }
                boolean fresh = !spawned || entity() == null;
                Player puppet = ensureSpawned();
                if (puppet != null && !fresh) {
                    // Moved like any other player: the move happens during the next tick, so the walk
                    // animation sees it and frames between ticks are interpolated. One step: no lag.
                    puppet.moveOrInterpolateTo(java.util.Optional.of(new net.minecraft.world.phys.Vec3(p.x(), p.y(), p.z())),
                            java.util.Optional.of(p.yRot()), java.util.Optional.of(p.xRot()));
                    puppet.lerpHeadTo(p.yHeadRot(), 1);
                    puppet.setOnGround(p.onGround());
                }
            }
            case ClientState.EntityData d -> {
                data = d;
                applyData(entity());
            }
            case ClientState.Equipment e -> {
                equipment = e;
                applyEquipment(entity());
            }
            case ClientState.Swing s -> {
                Player puppet = entity();
                if (puppet != null) {
                    puppet.swing(s.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, false);
                }
            }
            case ClientState.Vehicle v -> {
                ClientLevel level = Minecraft.getInstance().level;
                Entity vehicle = level == null ? null : level.getEntity(v.entityId());
                if (vehicle != null) {
                    vehicle.setPos(v.x(), v.y(), v.z());
                    vehicle.setYRot(v.yRot());
                    vehicle.setXRot(v.xRot());
                }
            }
        }
    }

    private void applyData(@Nullable Player puppet) {
        if (puppet == null) {
            return;
        }
        if (data == null) {
            // Recorded before Kinora recorded the player's values: show every skin layer, as most players do.
            puppet.getEntityData().set(dev.kinora.mc.mixin.AvatarAccessor.kinora$modelParts(), (byte) 0x7F);
            return;
        }
        puppet.getEntityData().assignValues(data.values());
    }

    private void applyEquipment(@Nullable Player puppet) {
        if (puppet == null || equipment == null) {
            return;
        }
        for (ClientState.Equipment.Slot slot : equipment.slots()) {
            puppet.setItemSlot(slot.slot(), slot.stack().copy());
        }
    }

    private @Nullable Player ensureSpawned() {
        Player existing = entity();
        if (existing != null && spawned) {
            return existing;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (listener == null || mc.level == null || last == null || uuid == null) {
            return existing;
        }
        if (existing != null) {
            // Something else (an old puppet, a server spawn) holds the id; replace it cleanly.
            mc.level.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
        }
        if (listener.getPlayerInfo(uuid) == null) {
            // Servers that hide the tab list never introduce the player; a remote player needs it.
            GameProfile profile = new GameProfile(uuid, name == null ? "Player" : name);
            listener.handlePlayerInfoUpdate(SyntheticPackets.addPlayerInfo(profile, GameType.SURVIVAL, false, mc.level.registryAccess()));
            syntheticInfo = true;
        }
        listener.handleAddEntity(SyntheticPackets.addPlayerEntity(entityId, uuid, last.x(), last.y(), last.z(),
                last.yRot(), last.xRot(), last.yHeadRot()));
        Player puppet = entity();
        if (puppet != null) {
            puppet.setYBodyRot(last.yBodyRot());
            if (puppet instanceof LivingEntity living) {
                living.yBodyRotO = last.yBodyRot();
            }
            if (puppet instanceof LivingEntity living && living.getInterpolation() != null) {
                living.getInterpolation().setInterpolationLength(1);
            }
            applyEquipment(puppet);
            applyData(puppet);
            applyAttachments(listener);
            spawned = true;
        }
        return puppet;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyAttachments(ClientPacketListener listener) {
        for (Packet<?> packet : attachments.values()) {
            try {
                ((Packet) packet).handle(listener);
            } catch (RuntimeException e) {
                dev.kinora.mc.KinoraMod.LOG.warn("Kinora could not give the recorded player their {}", packet.type().id(), e);
            }
        }
    }

    /** Profile of the recording player as playback knows it. */
    @Nullable PlayerInfo info() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener == null || uuid == null ? null : listener.getPlayerInfo(uuid);
    }
}
