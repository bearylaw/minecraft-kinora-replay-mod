# Internals: rebuilding a world from packets (Minecraft 26.2, NeoForge 26.2.0.88)

Verified against the decompiled sources. CPL = `ClientPacketListener`, CL = `ClientLevel`,
`P/` = `net/minecraft/network/protocol/game/`.

## Login and respawn

* `ClientboundLoginPacket` record: `(int playerId, boolean hardcore, Set<ResourceKey<Level>> levels,
  int maxPlayers, int chunkRadius, int simulationDistance, boolean reducedDebugInfo, boolean showDeathScreen,
  boolean doLimitedCrafting, CommonPlayerSpawnInfo commonPlayerSpawnInfo, boolean onlineMode,
  boolean enforcesSecureChat)`.
* `CommonPlayerSpawnInfo` record: `(Holder<DimensionType> dimensionType, ResourceKey<Level> dimension,
  long seed, GameType gameType, @Nullable GameType previousGameType, boolean isDebug, boolean isFlat,
  Optional<GlobalPos> lastDeathLocation, int portalCooldown, int seaLevel)`.
* `handleLogin` builds the `ClientLevel`, creates the LocalPlayer from the cookie's profile, then
  `player.setId(packet.playerId())`, `level.addEntity(player)`, `setCameraEntity(player)`,
  `startWaitingForNewLevel(...)`.
* `ClientboundRespawnPacket(CommonPlayerSpawnInfo, byte dataToKeep)`: new level on dimension change,
  always a new LocalPlayer keeping the old id.

## Packets that act on the local player

`ClientboundPlayerPositionPacket` (moves + replies), `RotatePlayer`, `PlayerLookAt`, `SetHealth`,
`SetExperience`, `PlayerAbilities`, `GameEvent` (`CHANGE_GAME_MODE`, `WIN_GAME`, `DEMO_EVENT`, ...),
container and inventory packets, `OpenScreen`, `OpenBook`, `OpenSignEditor`, `MountScreenOpen`,
`PlayerCombatKill` (death screen / respawn), explosion knockback, `SetCamera`, `MoveVehicle`.
Kinora's playback filter drops or redirects all of them.

## Entities

* `ClientboundAddEntityPacket(int id, UUID uuid, double x, double y, double z, float xRot, float yRot,
  EntityType<?> type, int data, Vec3 movement, double yHeadRot)`.
* Player entities need a `PlayerInfo` first (`ClientboundPlayerInfoUpdatePacket` ADD_PLAYER); that packet
  has no client-side constructor, so Kinora writes its wire format and decodes it with `STREAM_CODEC`.
* `ClientboundSetEntityDataPacket(int id, List<DataValue<?>>)`; `SynchedEntityData.getNonDefaultValues()`
  (null when all default; values are live references).
* `ClientboundSetEquipmentPacket(int, List<Pair<EquipmentSlot, ItemStack>>)`,
  `ClientboundRotateHeadPacket(Entity, byte)`, `ClientboundAnimatePacket(Entity, int action)`,
  `ClientboundEntityPositionSyncPacket(int id, PositionMoveRotation, boolean onGround)`,
  `PositionMoveRotation(Vec3 position, Vec3 deltaMovement, float yRot, float xRot)`,
  `ClientboundMoveEntityPacket.Pos/PosRot/Rot` (deltas against `Entity.getPositionCodec()`),
  `ClientboundRemoveEntitiesPacket(int...)`, `ClientboundSetEntityMotionPacket(int, Vec3)`.
* `RemotePlayer(ClientLevel, GameProfile)`: `noPhysics`, pose only from synced data
  (`DATA_SHARED_FLAGS_ID`, `DATA_POSE`, `DATA_LIVING_ENTITY_FLAGS`); equipment must arrive before
  the using-item flag.
* **The server never sends the recording player its own movement, head yaw, equipment, swing, or the
  sounds and level events it caused.** Kinora records these from the LocalPlayer (CLIENT_STATE records).

## Entity ids

* Server ids count up from 1. `CL.addEntity` first removes any entity with the same id; duplicate UUIDs
  are not indexed. `Entity.getId()` throws for id 0.
* Playback gives the camera LocalPlayer a reserved id and a random UUID, and spawns the recorded player
  as a `RemotePlayer` puppet with the recorded id and UUID.

## Spectator

* Two states: `MultiPlayerGameMode.localPlayerMode` (from login/respawn/`CHANGE_GAME_MODE`) and
  `Player.isSpectator()` (from the local UUID's `PlayerInfo`). Both must be SPECTATOR for noclip, the
  instant level-ready path, no hand and no hotbar.

## Chunk cache

* `ClientChunkCache` ignores chunks outside the view radius of the center; only
  `SetChunkCacheCenter`/`SetChunkCacheRadius` packets move them. Moving the camera does not drop chunks.
* Light updates are queued (`queueLightUpdate`).

## Time and weather

* `ClientboundSetTimePacket(long gameTime, Map<Holder<WorldClock>, ClockNetworkState> clockUpdates)`;
  `ClockNetworkState(long totalTicks, float partialTick, float rate)`; `ClientClockManager` on the listener.
* Weather is only `setRainLevel`/`setThunderLevel` (`GameEvent` 1, 2, 7, 8).

## Loading

* `LevelLoadTracker.WaitingForServer` never times out without `GameEvent LEVEL_CHUNKS_LOAD_START` (13).
* Spectators are ready immediately after that.

## Failure modes

* `handlePlayerChat` disconnects on a chat index gap (seeking breaks it): playback rewrites player chat
  as system chat.
* Exceptions on the queued path disconnect via `onPacketError`; Kinora feeds packets on the main
  thread and catches per packet.
