# Mixins

Kinora uses NeoForge events wherever one exists. This is the complete list of mixins, why each is
needed, and what to check when Minecraft changes. All are client-only and inert unless a replay, a
recording or a render is active (each one first asks a `hooks/` class, which returns vanilla
behaviour when Kinora is idle).

`kinora.mixins.json` (required):

| Mixin | Target | Injection | Why no event works |
| --- | --- | --- | --- |
| `CameraMixin` | `Camera.update`, `Camera.calculateFov` | after `alignWithEntity`; return of `calculateFov` | Places the camera exactly (double position, yaw, pitch, roll, FOV) for replays and renders. `ViewportEvent.ComputeCameraAngles` sets angles but not position, and fires before the position is set. It is also where the editor's path gizmos are emitted, because the frame's gizmo collector is open there. |
| `CameraTileMixin` | `Camera.extractRenderState` | tail | Tiled renders: the projection is scaled and shifted to the tile's piece of the frame. No event exposes the projection matrix before the level is drawn. |
| `ConnectionMixin` | `Connection.configurePacketHandler` | tail | Adds Kinora's capture handlers to the Netty pipeline once it exists, to record raw packet frames before decoding. |
| `DeltaTrackerTimerMixin` | `DeltaTracker.Timer.getGameTimeDeltaPartialTick`, `getGameTimeDeltaTicks` | return value | The partial tick must be the replay clock's exact fraction; the timer has no setter. |
| `MinecraftTimingMixin` | `Minecraft.runTick` | wraps `Timer.advanceGameTime`; constant `10`; wraps `TextureManager.tick` | Replays decide how many ticks run per frame (pause, slow motion, fast-forward beyond vanilla's 10-tick cap), and texture animation steps once per replay tick. |
| `EntityRenderDispatcherMixin` | `EntityRenderDispatcher.shouldRender` | return value | Hides entities and categories the user hid in a replay. |
| `KeyMappingMixin` | `KeyMapping.isDown`, `KeyMapping.consumeClick` | head, cancellable | In a replay only Kinora's keys, the movement keys (camera flight), screenshot and fullscreen act; every other mapping, other mods' included, reads as not pressed, as the director's keys overlap them. Mods that read raw key events instead of key mappings are not covered. |
| `FriendsKeyMixin` | `Minecraft.toggleFriendsScreen` | head, cancellable | The friends list's key (O) is a global key, checked before key mappings are read, so `KeyMappingMixin` does not stop it; in a replay it would take the orbit camera's key. Not handled, the key goes on to Kinora. |
| `MinecraftGlowMixin` | `Minecraft.shouldEntityAppearGlowing` | return value | Highlights entities without touching their glowing flag, which recorded packets would overwrite. |
| `GameRendererCaptureMixin` | `GameRenderer.render` | before `GuiRenderer.render()` | Offline renders read the finished world image after outlines and post effects, before the GUI. `RenderLevelStageEvent.AfterLevel` is too early (before outlines and post effects). |
| `FramerateLimitMixin` | `FramerateLimitTracker.getFramerateLimit` | return value | Lifts the frame cap during renders (vanilla caps minimised windows at 10 fps, idle players at 30). |
| `SkinLookupMixin` | `SkinManager.createLookup` | argument at head | In a replay, players keep their recorded skins without a verified signature. Vanilla requires one for everyone but the local player, and the recorded player is never the local one in a replay, so an offline or development client showed default skins. authlib still limits texture URLs to Mojang's domains. |
| `SpriteAnimationMixin` | `SpriteContents$AnimationState.tick` | head, cancellable | In replays, animated textures show the frame for the replay tick. Otherwise renders differ with how long loading took. |
| `ClientLevelTickMixin` | `ClientLevel.tickEntities` | head, cancellable | Entities do not tick while a replay restores its world: loading takes a varying number of vanilla ticks, and entities ticking through them would start with different animation state each time. |
| `SectionResortMixin` | `SectionRenderDispatcher$RenderSection.resortTransparency` | head | Counts queued translucent re-sorts so the render's settle check waits for them, like for chunk builds. |
| `TranslucentResortMixin` | `LevelRenderer.scheduleTranslucentSectionResort` | the `Math.max` resort budget | During renders every visible translucent section is re-sorted each frame (vanilla does a rolling eighth), so the settle wait covers sorting and water edges match between runs. |
| `MoveEntityPacketAccessor`, `RotateHeadPacketAccessor` | `ClientboundMoveEntityPacket.entityId`, `ClientboundRotateHeadPacket.entityId` | accessors | The recording state model needs the entity id these packets keep private. |

`kinora.compat.mixins.json` (optional; `CompatMixinPlugin` applies each mixin only when its mod is installed):

| Mixin | Target | Why |
| --- | --- | --- |
| `IrisTimerMixin` | `net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer.beginFrame` | Shader animation time (`frameTimeCounter`, `frameTime`) follows the video's time during renders. |
| `IrisFrameCounterMixin` | `...SystemTimeUniforms$FrameCounter.beginFrame` | `frameCounter` counts output images during renders, so temporal effects repeat. |

Sodium needs no mixin: `TerrainReadiness` calls `SodiumWorldRenderer.isTerrainRenderComplete()`
reflectively.

## Checking after a Minecraft update

1. Build: a missing target method fails mixin application at startup (`defaultRequire` is 1 for the
   main config).
2. Run `tools/dev/scripts/editor-smoke.txt` (camera, timing, editor), `render-determinism.txt`
   (capture point, timing, textures; the two renders must be bit-identical) and `record-events.txt`
   (connection capture).
3. Read the injection points against the new decompiled sources: `GameRenderer.render` (is
   `GuiRenderer.render()` still the GUI step?), `Minecraft.runTick` (is `10` still the tick cap
   constant?), `Camera.update` (still calls `alignWithEntity`?).
