# Internals: networking (Minecraft 26.2, NeoForge 26.2.0.88)

Verified against the decompiled sources (`kinora-mc/build/moddev/artifacts/minecraft-patched-26.2.0.88-sources.jar`)
and the NeoForge sources jar. `MC/` = `net/minecraft/`. Line numbers are from those sources and will drift.

## Connection pipeline

`MC/network/Connection.java`, `public class Connection extends SimpleChannelInboundHandler<Packet<?>>`.

| Member | Notes |
| --- | --- |
| `Connection(PacketFlow receiving)` | stores direction only |
| `channelActive` | sets `channel`; NeoForge `ConnectionUtils.setConnection` stores attribute `neoforge:connection` |
| `channelRead0(ctx, Packet)` | if open and `listener.shouldHandleMessage`, `packet.handle(listener)`; swallows `RunningOnDifferentThreadException`; other exceptions disconnect |
| `setupInboundProtocol(ProtocolInfo<T>, T listener)` | writes an `InboundConfigurationTask` through the pipeline; `inbound_config` replaces itself with `decoder` (`PacketDecoder`), plus `bundler` after it if the protocol has a `BundlerInfo` |
| `setupOutboundProtocol(ProtocolInfo<?>)` | `outbound_config` → `encoder`, `unbundler`, then `NetworkFilters.injectIfNecessary` (needs `encoder` present) |
| `configureSerialization(pipeline, flow, local, monitor)` (static) | `splitter`, unnamed `FlowControlHandler`, `inbound_config`, `prepender`, `outbound_config` |
| `configurePacketHandler(pipeline)` | `hackfix`, `packet_handler` (the Connection) |
| `connectToLocalServer`, `connect`, `connectToServer`, `fromChannel` | all go through `configureSerialization` + `configurePacketHandler` |
| `setupCompression(threshold, validate)` | `decompress` after `splitter`; skipped by the client for memory connections |
| `tick()` | flushes queue, ticks a `TickablePacketListener`, `handleDisconnection()` when closed |
| `isMemoryConnection()` | `LocalChannel`/`LocalServerChannel` only |
| `getInboundProtocol()` (NeoForge) | the protocol that is decoding right now |

Terminal packets (`ClientboundLoginFinishedPacket`, `ClientboundFinishConfigurationPacket`,
`ClientboundStartConfigurationPacket`) make `PacketDecoder` swap itself back to `inbound_config` and set
autoRead false (`ProtocolSwapHandler`), until the main thread calls `setupInboundProtocol`.

`PacketUtils.ensureRunningOnSameThread(packet, listener, processor)` handles inline on the game thread
and otherwise queues to `Minecraft.packetProcessor()`, drained in `runTick` (`processQueuedPackets`).

**Singleplayer is serialized too**: the integrated server's `PacketEncoder` produces ByteBufs, wrapped as
`HiddenByteBuf` by `LocalFrameEncoder`, unwrapped by the client's `LocalFrameDecoder` (`splitter`), then
decoded by `decoder`. No length prefix, no compression.

## Encoding and decoding

* `ProtocolInfo<T>`: `id()`, `flow()`, `codec()` (`StreamCodec<ByteBuf, Packet<? super T>>`), `bundlerInfo()`.
* `GameProtocols.CLIENTBOUND_TEMPLATE` (`SimpleUnboundProtocol<ClientGamePacketListener, RegistryFriendlyByteBuf>`),
  bound with `RegistryFriendlyByteBuf.decorator(RegistryAccess, ConnectionType)`. `bind` builds a new codec each call.
* `ConfigurationProtocols.CLIENTBOUND` is already bound (`FriendlyByteBuf::new`).
* Packet ids: `IdDispatchCodec` writes VarInt id then body; `PacketDecoder` rejects trailing bytes.
* Only `ClientboundBundleDelimiterPacket` is registered: a `ClientboundBundlePacket` cannot be encoded directly.
* Custom payloads: `CustomPacketPayload.codec` → vanilla map → `NetworkRegistry.getCodec(id, protocol, flow)` →
  `DiscardedPayload` (whose encoder writes nothing, so re-encoding is lossy).

**Decision:** Kinora captures raw frames (VarInt id + body) with a handler placed directly before
`inbound_config`/`decoder`. That position survives protocol switches (the decoder is replaced in place),
sees exactly what the decoder sees in both remote and local mode, and is lossless for bundles, split
packets and unknown mod payloads. The phase is read from `Connection.getInboundProtocol().id()` as each
frame passes, which is reliable because of the autoRead gating above.

## Login → configuration → play

* `ClientHandshakePacketListenerImpl.handleLoginFinished` (Netty thread) creates
  `new ClientConfigurationPacketListenerImpl(minecraft, connection, new CommonListenerCookie(...))`.
* `record CommonListenerCookie(LevelLoadTracker levelLoadTracker, GameProfile localGameProfile,
  WorldSessionTelemetryManager telemetryManager, RegistryAccess.Frozen receivedRegistries,
  FeatureFlagSet enabledFeatures, @Nullable String serverBrand, @Nullable ServerData serverData,
  @Nullable Screen postDisconnectScreen, Map<Identifier, byte[]> serverCookies,
  ChatComponent.@Nullable State chatState, Map<String,String> customReportDetails, ServerLinks serverLinks,
  Map<UUID, PlayerInfo> seenPlayers, boolean seenInsecureChatWarning, ConnectionType connectionType)`.
* Configuration handlers that reply or open UI: `handleSelectKnownPacks` (replies), `handleCodeOfConduct`
  (opens `CodeOfConductScreen` when `serverData` is null), `handleResourcePackPush` (prompt + download),
  `handleTransfer` (throws when `serverData` is null), `handleRequestCookie`, keep-alive and ping.
* `RegistryDataCollector.collectGameRegistries` resolves entries without data against the client's own
  vanilla packs (`KnownPacksManager`); memory connections skip static-registry tags.
* `handleConfigurationFinished` (main thread) binds the play protocol and creates `ClientPacketListener`;
  `ClientPacketListener.handleConfigurationStart` (reconfiguration) clears the level and goes back.

## NeoForge negotiation (client)

* Per-connection state lives in channel attributes (`neoforge:payload_setup`, `neoforge:connection_type`,
  `neoforge:adhoc_channels`, `neoforge:common_channels`, ...). Payload *decoding* is global; *handling* is
  per connection.
* Disconnect points during a replayed configuration: `ClientNetworkRegistry.handleModdedPayload`
  (no setup / channel / handler), `configureOtherConnection`, `checkCommonVersion`,
  `ClientPayloadHandler` (frozen registry sync mutates global registries via `RegistryManager.applySnapshot`;
  reverted by `Minecraft.disconnect` when a player connection exists), `CheckFeatureFlags`,
  `CheckExtensibleEnums`, `ClientRegistryManager` data maps, `GenericPacketSplitter` (needs
  `neoforge:splitter`, installed by `NetworkFilters.injectIfNecessary` only when `encoder` exists).
* `NetworkRegistry.checkPacket` throws `UnsupportedOperationException` when the client *sends* a modded
  payload on an unnegotiated channel.

## Lifecycle hooks

* No configuration-phase client event exists. `ClientPlayerNetworkEvent.LoggingIn` fires in
  `handleLogin` (main thread); `.Clone` in `handleRespawn`; `.LoggingOut` in `Minecraft.disconnect`.
* Multiplayer: `ConnectScreen.startConnecting(...)` → "Server Connector" thread → `Connection.connect`.
* Singleplayer: `Minecraft.doWorldLoad` → `Connection.connectToLocalServer` → `pendingConnection` (private).
* Disconnect: `Minecraft.disconnectFromWorld(Component)`, `disconnect(Screen, boolean[, boolean])`,
  `clearClientLevel(Screen)`. A listener disconnect goes through `ClientCommonPacketListenerImpl.onDisconnect`.
* Nobody ticks a replay connection before play begins unless Kinora does.

## Playback through an EmbeddedChannel

* Build with `new EmbeddedChannel(false, false)`, add handlers by name (`configureSerialization`,
  `configurePacketHandler`), then `register()` so `channelActive` fires.
* `EmbeddedEventLoop.inEventLoop()` is always true; drive the channel from the game thread only, so
  `ensureRunningOnSameThread` handles inline and protocol swaps complete synchronously.
* Drain or discard `outboundMessages()`.
* `isMemoryConnection()` is false, which is what playback needs (full tag/component application).
