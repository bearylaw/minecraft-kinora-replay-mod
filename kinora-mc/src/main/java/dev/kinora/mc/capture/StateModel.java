package dev.kinora.mc.capture;

import com.mojang.datafixers.util.Pair;

import dev.kinora.core.format.RecordKind;
import dev.kinora.mc.adapter.PacketIO;
import dev.kinora.mc.mixin.MoveEntityPacketAccessor;
import dev.kinora.mc.mixin.RotateHeadPacketAccessor;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundCustomReportDetailsPacket;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.BossEvent;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;
import net.neoforged.neoforge.network.payload.AuxiliaryLightDataPayload;
import net.neoforged.neoforge.network.payload.MinecraftRegisterPayload;
import net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload;
import net.neoforged.neoforge.network.payload.SyncAttachmentsPayload;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A packet-level model of what the client knows, kept up to date as packets arrive, from which a
 * snapshot can be produced at any moment: the records that rebuild the same state in a fresh
 * client (see {@code docs/design.md}, section 2.3).
 *
 * <p>Most of the state is references to captured frames (the last chunk-data packet of each loaded
 * chunk, for instance). Entity state is merged and re-synthesized, so a long-lived entity costs one
 * spawn packet, one data packet and one equipment packet however much it changed: its position is
 * tracked through relative moves exactly as the client tracks it ({@link VecDeltaCodec}).
 *
 * <p>Not thread-safe: the owning {@link CaptureSession} serialises access.
 */
public final class StateModel {
    /** How many packets of one unknown kind are kept. */
    private static final int UNKNOWN_CAP = 64;
    private static final int LIST_CAP = 256;
    private static final int LIGHT_CAP = 64;

    /** A step of a snapshot: an existing captured record, or one synthesized for the snapshot. */
    public sealed interface Item permits Ref, Synth {}

    public record Ref(Captured captured) implements Item {}

    public record Synth(int kind, byte[] payload) implements Item {}

    private final List<Captured> configuration = new ArrayList<>();
    private @Nullable Captured login;
    private int selfId = Integer.MIN_VALUE;
    private @Nullable Captured respawn;
    private @Nullable String dimension;
    private final LinkedHashMap<String, Captured> latest = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<Captured>> lists = new LinkedHashMap<>();
    private final LinkedHashMap<Captured, Set<UUID>> playerInfo = new LinkedHashMap<>();
    private final Long2ObjectLinkedOpenHashMap<ChunkState> chunks = new Long2ObjectLinkedOpenHashMap<>();
    private final Int2ObjectLinkedOpenHashMap<EntityState> entities = new Int2ObjectLinkedOpenHashMap<>();
    private final LinkedHashMap<String, ArrayDeque<Captured>> unknown = new LinkedHashMap<>();
    private final LinkedHashMap<String, ArrayDeque<Captured>> channelPayloads = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Captured> clientState = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Captured> modState = new LinkedHashMap<>();
    private @Nullable ProtocolInfo<?> playProtocol;
    /**
     * The world clocks (time of day and the like) as the client has them at {@link #clockGameTime}.
     * Servers send a clock's state only when a player joins or it changes (/time set, sleeping);
     * the time packet sent every second carries the game time alone. The last time packet is
     * therefore not enough to rebuild the time of day, and this follows the clocks the way
     * {@code ClientClockManager} does.
     */
    private final LinkedHashMap<Holder<WorldClock>, ClockMirror> clocks = new LinkedHashMap<>();
    private long clockGameTime;

    /** One clock, advanced exactly as {@code ClientClockManager.ClockInstance} is. */
    private static final class ClockMirror {
        long totalTicks;
        float partialTick;
        float rate = 1.0F;
    }

    private static final class ChunkState {
        final Captured data;
        final LinkedHashMap<Long, Captured> blocks = new LinkedHashMap<>();
        final List<Captured> sections = new ArrayList<>();
        final LinkedHashMap<Long, Captured> blockEntities = new LinkedHashMap<>();
        final LinkedHashMap<Long, Captured> blockEvents = new LinkedHashMap<>();
        final ArrayDeque<Captured> light = new ArrayDeque<>();
        final LinkedHashMap<String, Captured> scoped = new LinkedHashMap<>();

        ChunkState(Captured data) {
            this.data = data;
        }
    }

    private static final class EntityState {
        final int id;
        final @Nullable ClientboundAddEntityPacket spawn;
        final VecDeltaCodec position = new VecDeltaCodec();
        float yRot;
        float xRot;
        float headYaw;
        Vec3 motion = Vec3.ZERO;
        final LinkedHashMap<Integer, SynchedEntityData.DataValue<?>> data = new LinkedHashMap<>();
        final EnumMap<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        final LinkedHashMap<String, Captured> scoped = new LinkedHashMap<>();
        @Nullable Captured passengers;
        @Nullable Captured link;

        EntityState(int id, @Nullable ClientboundAddEntityPacket spawn) {
            this.id = id;
            this.spawn = spawn;
            if (spawn != null) {
                position.setBase(new Vec3(spawn.getX(), spawn.getY(), spawn.getZ()));
                yRot = spawn.getYRot();
                xRot = spawn.getXRot();
                headYaw = spawn.getYHeadRot();
                motion = spawn.getMovement();
            }
        }
    }

    // ------------------------------------------------------------------ updates

    /** Called for every configuration-phase frame, in order. */
    public void onConfiguration(Captured frame) {
        configuration.add(frame);
    }

    /** Called for every decoded play packet with the frame it came from. */
    public void onPlayPacket(Packet<?> packet, Captured frame, ProtocolInfo<?> protocol) {
        playProtocol = protocol;
        switch (packet) {
            case ClientboundBundlePacket bundle -> throw new IllegalArgumentException("bundles are split by the caller");
            case ClientboundLoginPacket p -> {
                resetWorld();
                resetConnectionState();
                login = frame;
                respawn = null;
                selfId = p.playerId();
                dimension = p.commonPlayerSpawnInfo().dimension().identifier().toString();
                entities.put(selfId, new EntityState(selfId, null));
            }
            case ClientboundRespawnPacket p -> {
                String newDimension = p.commonPlayerSpawnInfo().dimension().identifier().toString();
                if (!newDimension.equals(dimension)) {
                    resetWorld();
                    entities.put(selfId, new EntityState(selfId, null));
                }
                dimension = newDimension;
                respawn = frame;
                latest.remove("camera");
            }
            case ClientboundStartConfigurationPacket p -> {
                // Reconfiguration: the client throws the level away and configures afresh.
                resetWorld();
                resetConnectionState();
                configuration.clear();
                login = null;
                respawn = null;
            }

            // Chunks.
            case ClientboundLevelChunkWithLightPacket p -> chunks.put(ChunkPos.pack(p.getX(), p.getZ()), new ChunkState(frame));
            case ClientboundForgetLevelChunkPacket p -> chunks.remove(p.pos().pack());
            case ClientboundBlockUpdatePacket p -> withChunk(p.getPos(), c -> c.blocks.put(p.getPos().asLong(), frame));
            case ClientboundSectionBlocksUpdatePacket p -> {
                BlockPos[] first = new BlockPos[1];
                p.runUpdates((pos, state) -> {
                    if (first[0] == null) {
                        first[0] = pos.immutable();
                    }
                });
                if (first[0] != null) {
                    withChunk(first[0], c -> {
                        c.sections.add(frame);
                        if (c.sections.size() > LIST_CAP) {
                            c.sections.removeFirst();
                        }
                    });
                }
            }
            case ClientboundBlockEntityDataPacket p -> withChunk(p.getPos(), c -> c.blockEntities.put(p.getPos().asLong(), frame));
            case ClientboundBlockEventPacket p -> withChunk(p.getPos(), c -> c.blockEvents.put(p.getPos().asLong(), frame));
            case ClientboundLightUpdatePacket p -> {
                ChunkState c = chunks.get(ChunkPos.pack(p.getX(), p.getZ()));
                if (c != null) {
                    c.light.add(frame);
                    if (c.light.size() > LIGHT_CAP) {
                        c.light.removeFirst();
                    }
                }
            }
            case ClientboundChunksBiomesPacket p -> {
                for (ClientboundChunksBiomesPacket.ChunkBiomeData d : p.chunkBiomeData()) {
                    ChunkState c = chunks.get(d.pos().pack());
                    if (c != null) {
                        c.scoped.put("biomes", frame);
                    }
                }
            }
            case ClientboundBlockDestructionPacket p -> {
                if (p.getProgress() < 0 || p.getProgress() > 9) {
                    latest.remove("destruction:" + p.getId());
                } else {
                    latest.put("destruction:" + p.getId(), frame);
                }
            }

            // Entities.
            case ClientboundAddEntityPacket p -> entities.put(p.getId(), new EntityState(p.getId(), p));
            case ClientboundRemoveEntitiesPacket p -> p.getEntityIds().forEach(id -> {
                if (id != selfId) {
                    entities.remove(id);
                }
            });
            case ClientboundSetEntityDataPacket p -> withEntity(p.id(), e -> {
                for (SynchedEntityData.DataValue<?> value : p.packedItems()) {
                    e.data.put(value.id(), value);
                }
            });
            case ClientboundSetEquipmentPacket p -> withEntity(p.getEntity(), e -> {
                for (Pair<EquipmentSlot, ItemStack> slot : p.getSlots()) {
                    e.equipment.put(slot.getFirst(), slot.getSecond());
                }
            });
            case ClientboundMoveEntityPacket p -> withEntity(((MoveEntityPacketAccessor) p).kinora$entityId(), e -> {
                if (p.hasPosition()) {
                    e.position.setBase(e.position.decode(p.getXa(), p.getYa(), p.getZa()));
                }
                if (p.hasRotation()) {
                    e.yRot = p.getYRot();
                    e.xRot = p.getXRot();
                }
            });
            case ClientboundEntityPositionSyncPacket p -> withEntity(p.id(), e -> {
                e.position.setBase(p.values().position());
                e.yRot = p.values().yRot();
                e.xRot = p.values().xRot();
                e.motion = p.values().deltaMovement();
            });
            case ClientboundTeleportEntityPacket p -> withEntity(p.id(), e -> {
                PositionMoveRotation current = new PositionMoveRotation(e.position.getBase(), e.motion, e.yRot, e.xRot);
                PositionMoveRotation result = PositionMoveRotation.calculateAbsolute(current, p.change(), p.relatives());
                e.position.setBase(result.position());
                e.motion = result.deltaMovement();
                e.yRot = result.yRot();
                e.xRot = result.xRot();
            });
            case ClientboundRotateHeadPacket p -> withEntity(((RotateHeadPacketAccessor) p).kinora$entityId(), e -> e.headYaw = p.getYHeadRot());
            case ClientboundSetEntityMotionPacket p -> withEntity(p.id(), e -> e.motion = p.movement());
            case ClientboundUpdateAttributesPacket p -> withEntity(p.getEntityId(), e -> {
                StringBuilder key = new StringBuilder("attributes");
                for (ClientboundUpdateAttributesPacket.AttributeSnapshot s : p.getValues()) {
                    key.append(':').append(s.attribute().getRegisteredName());
                }
                e.scoped.put(key.toString(), frame);
            });
            case ClientboundUpdateMobEffectPacket p -> withEntity(p.getEntityId(), e -> e.scoped.put("effect:" + p.getEffect().getRegisteredName(), frame));
            case ClientboundRemoveMobEffectPacket p -> withEntity(p.entityId(), e -> e.scoped.remove("effect:" + p.effect().getRegisteredName()));
            case ClientboundSetPassengersPacket p -> withEntity(p.getVehicle(), e -> e.passengers = frame);
            case ClientboundSetEntityLinkPacket p -> withEntity(p.getSourceId(), e -> e.link = frame);
            case ClientboundProjectilePowerPacket p -> withEntity(p.getId(), e -> e.scoped.put("power", frame));
            case ClientboundMoveMinecartPacket p -> withEntity(p.entityId(), e -> {
                if (!p.lerpSteps().isEmpty()) {
                    var last = p.lerpSteps().getLast();
                    e.position.setBase(last.position());
                    e.motion = last.movement();
                    e.yRot = last.yRot();
                    e.xRot = last.xRot();
                }
            });

            // Players, scoreboard, bars, maps.
            case ClientboundPlayerInfoUpdatePacket p -> {
                Set<UUID> ids = new HashSet<>();
                for (ClientboundPlayerInfoUpdatePacket.Entry entry : p.entries()) {
                    ids.add(entry.profileId());
                }
                if (!p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
                        && !p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT)) {
                    // A pure update supersedes an earlier pure update of the same players and kind.
                    playerInfo.entrySet().removeIf(old -> old.getValue().equals(ids) && sameActions(old.getKey(), p));
                }
                playerInfo.put(frame, ids);
            }
            case ClientboundPlayerInfoRemovePacket p -> {
                Set<UUID> removed = new HashSet<>(p.profileIds());
                playerInfo.entrySet().removeIf(e -> {
                    e.getValue().removeAll(removed);
                    return e.getValue().isEmpty();
                });
            }
            case ClientboundBossEventPacket p -> p.dispatch(new ClientboundBossEventPacket.Handler() {
                @Override
                public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay,
                                boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
                    List<Captured> list = new ArrayList<>();
                    list.add(frame);
                    lists.put("boss:" + id, list);
                }

                @Override
                public void remove(UUID id) {
                    lists.remove("boss:" + id);
                }

                @Override
                public void updateProgress(UUID id, float progress) {
                    append("boss:" + id, frame);
                }

                @Override
                public void updateName(UUID id, Component name) {
                    append("boss:" + id, frame);
                }

                @Override
                public void updateStyle(UUID id, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
                    append("boss:" + id, frame);
                }

                @Override
                public void updateProperties(UUID id, boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
                    append("boss:" + id, frame);
                }
            });
            case ClientboundSetObjectivePacket p -> {
                String key = "objective:" + p.getObjectiveName();
                if (p.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE) {
                    lists.remove(key);
                } else if (p.getMethod() == ClientboundSetObjectivePacket.METHOD_ADD) {
                    List<Captured> list = new ArrayList<>();
                    list.add(frame);
                    lists.put(key, list);
                } else {
                    append(key, frame);
                }
            }
            case ClientboundSetDisplayObjectivePacket p -> latest.put("display:" + p.getSlot().getSerializedName(), frame);
            case ClientboundSetScorePacket p -> latest.put("score:" + p.owner() + "\u0000" + p.objectiveName(), frame);
            case ClientboundResetScorePacket p -> latest.keySet().removeIf(k -> k.startsWith("score:" + p.owner() + "\u0000")
                    && (p.objectiveName() == null || k.equals("score:" + p.owner() + "\u0000" + p.objectiveName())));
            case ClientboundSetPlayerTeamPacket p -> {
                String key = "team:" + p.getName();
                if (p.getParameters().isEmpty() && p.getPlayers().isEmpty()) {
                    lists.remove(key);
                } else {
                    append(key, frame);
                }
            }
            case ClientboundMapItemDataPacket p -> append("map:" + p.mapId().id(), frame);
            case ClientboundUpdateAdvancementsPacket p -> {
                if (p.shouldReset()) {
                    lists.remove("advancements");
                }
                append("advancements", frame);
            }

            // The recording player's own HUD state.
            case ClientboundContainerSetContentPacket p -> {
                if (p.containerId() == 0) {
                    List<Captured> list = new ArrayList<>();
                    list.add(frame);
                    lists.put("inventory", list);
                }
            }
            case ClientboundContainerSetSlotPacket p -> {
                if (p.getContainerId() == 0) {
                    append("inventory", frame);
                }
            }
            case ClientboundSetPlayerInventoryPacket p -> latest.put("inventorySlot:" + p.slot(), frame);
            case ClientboundSetCursorItemPacket p -> latest.put("cursor", frame);
            case ClientboundSetHeldSlotPacket p -> latest.put("heldSlot", frame);
            case ClientboundSetHealthPacket p -> latest.put("health", frame);
            case ClientboundSetExperiencePacket p -> latest.put("experience", frame);
            case ClientboundPlayerAbilitiesPacket p -> latest.put("abilities", frame);
            case ClientboundCooldownPacket p -> latest.put("cooldown:" + p.cooldownGroup(), frame);
            case ClientboundSetCameraPacket p -> latest.put("camera", frame);

            // World-wide values: only the last one matters.
            case ClientboundChangeDifficultyPacket p -> latest.put("difficulty", frame);
            case ClientboundSetTimePacket p -> {
                onTime(p);
                latest.put("time", frame);
            }
            case ClientboundSetChunkCacheCenterPacket p -> latest.put("chunkCenter", frame);
            case ClientboundSetChunkCacheRadiusPacket p -> latest.put("chunkRadius", frame);
            case ClientboundSetSimulationDistancePacket p -> latest.put("simulationDistance", frame);
            case ClientboundSetDefaultSpawnPositionPacket p -> latest.put("spawn", frame);
            case ClientboundTickingStatePacket p -> latest.put("tickingState", frame);
            case ClientboundCommandsPacket p -> latest.put("commands", frame);
            case ClientboundServerDataPacket p -> latest.put("serverData", frame);
            case ClientboundTabListPacket p -> latest.put("tabList", frame);
            case ClientboundUpdateRecipesPacket p -> latest.put("recipes", frame);
            case ClientboundGameRuleValuesPacket p -> latest.put("gameRules", frame);
            case ClientboundCustomReportDetailsPacket p -> latest.put("reportDetails", frame);
            case ClientboundServerLinksPacket p -> latest.put("serverLinks", frame);
            case ClientboundUpdateTagsPacket p -> append("tags", frame);
            case ClientboundRecipeBookAddPacket p -> append("recipeBook", frame);
            case ClientboundRecipeBookRemovePacket p -> append("recipeBook", frame);
            case ClientboundRecipeBookSettingsPacket p -> latest.put("recipeBookSettings", frame);
            case ClientboundInitializeBorderPacket p -> {
                latest.keySet().removeIf(k -> k.startsWith("border."));
                latest.put("border.init", frame);
            }
            case ClientboundSetBorderCenterPacket p -> latest.put("border.center", frame);
            case ClientboundSetBorderLerpSizePacket p -> latest.put("border.size", frame);
            case ClientboundSetBorderSizePacket p -> latest.put("border.size", frame);
            case ClientboundSetBorderWarningDelayPacket p -> latest.put("border.warningDelay", frame);
            case ClientboundSetBorderWarningDistancePacket p -> latest.put("border.warningDistance", frame);
            case ClientboundGameEventPacket p -> {
                ClientboundGameEventPacket.Type type = p.getEvent();
                if (type == ClientboundGameEventPacket.START_RAINING || type == ClientboundGameEventPacket.STOP_RAINING) {
                    latest.put("weather.raining", frame);
                } else if (type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
                    latest.put("weather.rain", frame);
                } else if (type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
                    latest.put("weather.thunder", frame);
                } else if (type == ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START) {
                    latest.put("chunksLoadStart", frame);
                } else if (type == ClientboundGameEventPacket.CHANGE_GAME_MODE) {
                    latest.put("gameMode", frame);
                } else if (type == ClientboundGameEventPacket.IMMEDIATE_RESPAWN || type == ClientboundGameEventPacket.LIMITED_CRAFTING) {
                    latest.put("gameEvent:" + System.identityHashCode(type), frame);
                }
            }

            case ClientboundCustomPayloadPacket p -> onPayload(p.payload(), frame);

            // Events and things that only make sense live: not part of a snapshot.
            case ClientboundAnimatePacket p -> { }
            case ClientboundEntityEventPacket p -> { }
            case ClientboundHurtAnimationPacket p -> { }
            case ClientboundDamageEventPacket p -> { }
            case ClientboundTakeItemEntityPacket p -> { }
            case ClientboundSoundPacket p -> { }
            case ClientboundSoundEntityPacket p -> { }
            case ClientboundStopSoundPacket p -> { }
            case ClientboundLevelEventPacket p -> { }
            case ClientboundLevelParticlesPacket p -> { }
            case ClientboundExplodePacket p -> { }
            case ClientboundSystemChatPacket p -> { }
            case ClientboundPlayerChatPacket p -> { }
            case ClientboundDisguisedChatPacket p -> { }
            case ClientboundDeleteChatPacket p -> { }
            case ClientboundSetActionBarTextPacket p -> { }
            case ClientboundSetTitleTextPacket p -> { }
            case ClientboundSetSubtitleTextPacket p -> { }
            case ClientboundSetTitlesAnimationPacket p -> { }
            case ClientboundClearTitlesPacket p -> { }
            case ClientboundChunkBatchStartPacket p -> { }
            case ClientboundChunkBatchFinishedPacket p -> { }
            case ClientboundBlockChangedAckPacket p -> { }
            case ClientboundPlayerPositionPacket p -> { }
            case ClientboundPlayerRotationPacket p -> { }
            case ClientboundPlayerLookAtPacket p -> { }
            case ClientboundPlayerCombatEndPacket p -> { }
            case ClientboundPlayerCombatEnterPacket p -> { }
            case ClientboundPlayerCombatKillPacket p -> { }
            case ClientboundOpenScreenPacket p -> { }
            case ClientboundOpenBookPacket p -> { }
            case ClientboundOpenSignEditorPacket p -> { }
            case ClientboundMountScreenOpenPacket p -> { }
            case ClientboundContainerClosePacket p -> { }
            case ClientboundContainerSetDataPacket p -> { }
            case ClientboundMerchantOffersPacket p -> { }
            case ClientboundPlaceGhostRecipePacket p -> { }
            case ClientboundCommandSuggestionsPacket p -> { }
            case ClientboundCustomChatCompletionsPacket p -> { }
            case ClientboundTagQueryPacket p -> { }
            case ClientboundAwardStatsPacket p -> { }
            case ClientboundSelectAdvancementsTabPacket p -> { }
            case ClientboundMoveVehiclePacket p -> { }
            case ClientboundTickingStepPacket p -> { }
            case ClientboundLowDiskSpaceWarningPacket p -> { }
            case ClientboundDebugSamplePacket p -> { }
            default -> {
                String key = packet.type().id().toString();
                if (key.startsWith("minecraft:keep_alive") || key.startsWith("minecraft:ping") || key.contains("debug")) {
                    return;
                }
                ArrayDeque<Captured> deque = unknown.computeIfAbsent(key, k -> new ArrayDeque<>());
                deque.add(frame);
                if (deque.size() > UNKNOWN_CAP) {
                    deque.removeFirst();
                }
            }
        }
    }

    private void onPayload(CustomPacketPayload payload, Captured frame) {
        switch (payload) {
            case BrandPayload b -> latest.put("brand", frame);
            case AdvancedAddEntityPayload a -> withEntity(a.entityId(), e -> e.scoped.put("neoforge:spawn", frame));
            case AuxiliaryLightDataPayload a -> {
                ChunkState c = chunks.get(a.pos().pack());
                if (c != null) {
                    c.scoped.put("neoforge:aux_light", frame);
                }
            }
            case SyncAttachmentsPayload s -> {
                String key = "attachments:" + s.types();
                switch (s.target()) {
                    case SyncAttachmentsPayload.EntityTarget(int id) -> withEntity(id, e -> e.scoped.put(key, frame));
                    case SyncAttachmentsPayload.ChunkTarget(ChunkPos pos) -> {
                        ChunkState c = chunks.get(pos.pack());
                        if (c != null) {
                            c.scoped.put(key, frame);
                        }
                    }
                    case SyncAttachmentsPayload.BlockEntityTarget(BlockPos pos) -> withChunk(pos, c -> c.scoped.put(key + "@" + pos.asLong(), frame));
                    case SyncAttachmentsPayload.LevelTarget() -> latest.put(key, frame);
                }
            }
            case MinecraftRegisterPayload r -> channel("minecraft:register", frame);
            case MinecraftUnregisterPayload u -> channel("minecraft:register", frame);
            default -> channel(payload.type().id().toString(), frame);
        }
    }

    private void channel(String key, Captured frame) {
        ArrayDeque<Captured> deque = channelPayloads.computeIfAbsent(key, k -> new ArrayDeque<>());
        deque.add(frame);
        if (deque.size() > UNKNOWN_CAP) {
            deque.removeFirst();
        }
    }

    /** The latest CLIENT_STATE record of a type (player state, equipment, identity). */
    public void onClientState(int type, Captured record) {
        clientState.put(type, record);
    }

    /** The latest state record of a mod data track. */
    public void onModState(int track, Captured record) {
        modState.put(track, record);
    }

    private void append(String key, Captured frame) {
        List<Captured> list = lists.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(frame);
        if (list.size() > LIST_CAP) {
            list.removeFirst();
        }
    }

    private void withChunk(BlockPos pos, java.util.function.Consumer<ChunkState> action) {
        ChunkState c = chunks.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        if (c != null) {
            action.accept(c);
        }
    }

    private void withEntity(int id, java.util.function.Consumer<EntityState> action) {
        EntityState e = entities.get(id);
        if (e != null) {
            action.accept(e);
        }
    }

    private static boolean sameActions(Captured frame, ClientboundPlayerInfoUpdatePacket packet) {
        // Frames are compared by their decoded content only when needed; identical action sets
        // encode the same leading EnumSet bit field, which is the first byte after the packet id.
        byte[] a = frame.payload;
        int idEnd = 1;
        while (idEnd < a.length && (a[idEnd] & 0x80) != 0) {
            idEnd++;
        }
        int bits = 0;
        int i = 0;
        for (ClientboundPlayerInfoUpdatePacket.Action action : ClientboundPlayerInfoUpdatePacket.Action.values()) {
            if (packet.actions().contains(action)) {
                bits |= 1 << i;
            }
            i++;
        }
        return idEnd + 1 < a.length && (a[idEnd + 1] & 0xFF) == (bits & 0xFF);
    }

    private void resetWorld() {
        chunks.clear();
        entities.clear();
        latest.keySet().removeIf(k -> k.startsWith("destruction:"));
    }

    private void onTime(ClientboundSetTimePacket p) {
        long delta = p.gameTime() - clockGameTime;
        clockGameTime = p.gameTime();
        for (ClockMirror c : clocks.values()) {
            double partial = c.partialTick + (double) delta * c.rate;
            long whole = (long) Math.floor(partial);
            c.partialTick = (float) (partial - whole);
            c.totalTicks += whole;
        }
        p.clockUpdates().forEach((clock, state) -> {
            ClockMirror c = clocks.computeIfAbsent(clock, k -> new ClockMirror());
            c.totalTicks = state.totalTicks();
            c.partialTick = state.partialTick();
            c.rate = state.rate();
        });
    }

    private void resetConnectionState() {
        clocks.clear();
        clockGameTime = 0;
        latest.clear();
        lists.clear();
        playerInfo.clear();
        unknown.clear();
        clientState.clear();
    }

    // ------------------------------------------------------------------ snapshot

    /** True once the model has seen a login, so a snapshot would rebuild a world. */
    public boolean hasWorld() {
        return login != null;
    }

    public int selfId() {
        return selfId;
    }

    /**
     * The records that rebuild the current state, in the order they must be applied. Synthesized
     * packets are encoded with the play protocol last seen.
     */
    public List<Item> snapshot() {
        List<Item> out = new ArrayList<>(configuration.size() + chunks.size() * 2 + entities.size() * 3 + 64);
        for (Captured c : configuration) {
            out.add(new Ref(c));
        }
        if (login == null) {
            return out;
        }
        out.add(new Ref(login));
        if (respawn != null) {
            out.add(new Ref(respawn));
        }
        // The player list first: the puppet takes the recording player's profile (and skin) from it.
        for (Captured c : playerInfo.keySet()) {
            out.add(new Ref(c));
        }
        // Kinora's own records describing the recording player come early: playback spawns the
        // puppet from them, and server data for the player (entity data, effects) needs it.
        for (Captured c : clientState.values()) {
            out.add(new Ref(c));
        }
        Captured chunksLoadStart = null;
        for (Map.Entry<String, Captured> e : latest.entrySet()) {
            if (e.getKey().equals("chunksLoadStart")) {
                chunksLoadStart = e.getValue();
            } else if (e.getKey().equals("time")) {
                // Every clock as of the last time packet, then that packet itself: playback moves
                // the packet on to the snapshot's tick, and the client moves the clocks with it.
                if (!clocks.isEmpty()) {
                    Map<Holder<WorldClock>, ClockNetworkState> states = new LinkedHashMap<>();
                    clocks.forEach((clock, c) -> states.put(clock, new ClockNetworkState(c.totalTicks, c.partialTick, c.rate)));
                    out.add(synth(new ClientboundSetTimePacket(clockGameTime, states)));
                }
                out.add(new Ref(e.getValue()));
            } else if (!e.getKey().startsWith("destruction:")) {
                out.add(new Ref(e.getValue()));
            }
        }
        for (ArrayDeque<Captured> deque : channelPayloads.values()) {
            for (Captured c : deque) {
                out.add(new Ref(c));
            }
        }
        for (ChunkState c : chunks.values()) {
            out.add(new Ref(c.data));
            for (Captured l : c.light) {
                out.add(new Ref(l));
            }
            for (Captured s : c.sections) {
                out.add(new Ref(s));
            }
            for (Captured b : c.blocks.values()) {
                out.add(new Ref(b));
            }
            for (Captured b : c.blockEntities.values()) {
                out.add(new Ref(b));
            }
            for (Captured b : c.scoped.values()) {
                out.add(new Ref(b));
            }
            for (Captured b : c.blockEvents.values()) {
                out.add(new Ref(b));
            }
        }
        for (Map.Entry<String, Captured> e : latest.entrySet()) {
            if (e.getKey().startsWith("destruction:")) {
                out.add(new Ref(e.getValue()));
            }
        }
        List<Item> afterEntities = new ArrayList<>();
        for (EntityState e : entities.values()) {
            if (e.spawn != null) {
                Vec3 pos = e.position.getBase();
                ClientboundAddEntityPacket add = new ClientboundAddEntityPacket(e.id, e.spawn.getUUID(), pos.x, pos.y, pos.z,
                        e.xRot, e.yRot, e.spawn.getType(), e.spawn.getData(), e.motion, e.headYaw);
                out.add(synth(add));
            }
            Captured spawnData = e.scoped.get("neoforge:spawn");
            if (spawnData != null) {
                out.add(new Ref(spawnData));
            }
            if (!e.equipment.isEmpty()) {
                List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
                e.equipment.forEach((slot, stack) -> slots.add(Pair.of(slot, stack)));
                out.add(synth(new ClientboundSetEquipmentPacket(e.id, slots)));
            }
            if (!e.data.isEmpty()) {
                out.add(synth(new ClientboundSetEntityDataPacket(e.id, new ArrayList<>(e.data.values()))));
            }
            for (Map.Entry<String, Captured> s : e.scoped.entrySet()) {
                if (!s.getKey().equals("neoforge:spawn")) {
                    out.add(new Ref(s.getValue()));
                }
            }
            if (e.passengers != null) {
                afterEntities.add(new Ref(e.passengers));
            }
            if (e.link != null) {
                afterEntities.add(new Ref(e.link));
            }
        }
        out.addAll(afterEntities);
        for (List<Captured> list : lists.values()) {
            for (Captured c : list) {
                out.add(new Ref(c));
            }
        }
        for (ArrayDeque<Captured> deque : unknown.values()) {
            for (Captured c : deque) {
                out.add(new Ref(c));
            }
        }
        for (Captured c : modState.values()) {
            out.add(new Ref(c));
        }
        if (chunksLoadStart != null) {
            out.add(new Ref(chunksLoadStart));
        }
        return out;
    }

    private Item synth(Packet<?> packet) {
        if (playProtocol == null) {
            throw new IllegalStateException("no play protocol seen yet");
        }
        return new Synth(RecordKind.PACKET, PacketIO.encodeRecord(playProtocol, packet));
    }

    /** Rough counts for diagnostics. */
    public String describe() {
        return "config=" + configuration.size() + " chunks=" + chunks.size() + " entities=" + entities.size()
                + " lists=" + lists.size() + " latest=" + latest.size() + " unknown=" + unknown.size();
    }
}
