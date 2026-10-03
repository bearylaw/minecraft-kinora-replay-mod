package dev.kinora.mc.capture;

import dev.kinora.core.format.RecordKind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the recording player's own state at the end of every client tick: what the server never
 * sends back to the player who caused it (see {@code docs/internals/world-reconstruction.md}).
 */
final class PlayerStateRecorder {
    private final Map<EquipmentSlot, ItemStack> lastEquipment = new EnumMap<>(EquipmentSlot.class);
    private int lastSwingTime;
    private boolean lastSwinging;
    private int lastEntityId = Integer.MIN_VALUE;
    private List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> lastData = List.of();

    /** Forget what was sent, so the next tick writes everything (a new recording started). */
    void reset() {
        lastEquipment.clear();
        lastSwinging = false;
        lastSwingTime = 0;
        lastEntityId = Integer.MIN_VALUE;
        lastData = List.of();
    }

    void tick(CaptureSession session, Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        RegistryAccess registries = minecraft.level.registryAccess();
        int id = player.getId();
        if (id != lastEntityId) {
            // New recording, or a respawn gave the player a fresh entity: re-introduce it fully.
            session.append(RecordKind.CLIENT_STATE,
                    new ClientState.Identity(id, player.getUUID(), player.getGameProfile().name()).encode(registries),
                    ClientState.IDENTITY, -1);
            lastEquipment.clear();
            lastData = List.of();
            lastEntityId = id;
        }

        // The player's synced values, whole, whenever one changes (skin layers, pose, crouching...).
        List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> data = new ArrayList<>();
        for (var item : ((dev.kinora.mc.mixin.SynchedEntityDataAccessor) player.getEntityData()).kinora$items()) {
            if (item != null) {
                data.add(item.value());
            }
        }
        if (!data.equals(lastData)) {
            session.append(RecordKind.CLIENT_STATE, new ClientState.EntityData(id, data).encode(registries), ClientState.ENTITY_DATA, -1);
            lastData = data;
        }

        float fov = minecraft.gameRenderer.mainCamera().getFov();
        session.append(RecordKind.CLIENT_STATE, new ClientState.Player(id, player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.getYHeadRot(), player.yBodyRot, player.onGround(), fov,
                minecraft.options.getCameraType().ordinal()).encode(registries), ClientState.PLAYER, -1);

        List<ClientState.Equipment.Slot> changed = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack now = player.getItemBySlot(slot);
            ItemStack before = lastEquipment.get(slot);
            if (before == null || !ItemStack.matches(before, now)) {
                changed.add(new ClientState.Equipment.Slot(slot, now.copy()));
                lastEquipment.put(slot, now.copy());
            }
        }
        if (!changed.isEmpty()) {
            // The model keeps only the latest EQUIPMENT record, so it must always be complete.
            List<ClientState.Equipment.Slot> all = new ArrayList<>();
            lastEquipment.forEach((slot, stack) -> all.add(new ClientState.Equipment.Slot(slot, stack)));
            session.append(RecordKind.CLIENT_STATE, new ClientState.Equipment(id, all).encode(registries), ClientState.EQUIPMENT, -1);
        }

        boolean swingStarted = player.swinging && (!lastSwinging || player.swingTime < lastSwingTime);
        if (swingStarted) {
            session.append(RecordKind.CLIENT_STATE,
                    new ClientState.Swing(id, player.swingingArm == InteractionHand.OFF_HAND).encode(registries), -1, -1);
        }
        lastSwinging = player.swinging;
        lastSwingTime = player.swingTime;

        Entity vehicle = player.getVehicle();
        if (vehicle != null && vehicle.isLocalInstanceAuthoritative()) {
            session.append(RecordKind.CLIENT_STATE, new ClientState.Vehicle(vehicle.getId(), vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                    vehicle.getYRot(), vehicle.getXRot()).encode(registries), -1, -1);
        }
    }
}
