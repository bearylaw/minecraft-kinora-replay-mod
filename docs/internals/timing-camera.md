# Internals: client loop, timing and camera (Minecraft 26.2, NeoForge 26.2.0.88)

Verified against the decompiled sources. `M/` = `net/minecraft/`.

## Main loop (`M/client/Minecraft.java`)

* `private final DeltaTracker.Timer deltaTracker = new DeltaTracker.Timer(20.0F, 0L, this::getTickTargetMillis)`.
* `runTick(boolean advanceGameTime)`:
  1. `int ticksToDo = advanceGameTime ? deltaTracker.advanceGameTime(Util.getMillis()) : 0;`
  2. `packetProcessor.processQueuedPackets(); runAllTasks();` — packets once per frame, before ticks
  3. `if (ticksToDo > 0 && isLevelRunningNormally()) textureManager.tick();` — **once per frame**
  4. `for (i < Math.min(10, ticksToDo)) tick();` — extra ticks are dropped
  5. `soundManager.updateSource(camera)`, `mouseHandler.handleAccumulatedMovement()`, `renderFrame`
  6. after rendering: pause/frozen state updated for the *next* frame
* `renderFrame`: `gameRenderer.update(deltaTracker)` (Camera.update) → `pick` → `gameRenderer.extract` →
  `RenderFrameEvent.Pre` → `gameRenderer.render` → `RenderFrameEvent.Post` → present → `FramerateLimiter`.
* `tick()`: `ClientTickEvent.Pre` → tick-rate manager → game mode → gui → keybinds → `gameRenderer.tick` →
  `level.tickEntities` → block entities → music/sound → `level.tick` → `animateTick` → particles →
  `ClientTickEvent.Post`.
* `getTickTargetMillis` clamps the client to at most 20 TPS (`Math.max(50, mspt)`).

## DeltaTracker (`M/client/DeltaTracker.java`)

* `Timer.advanceGameTime(long ms)` returns whole ticks and keeps the fraction in `deltaTickResidual`.
* `getGameTimeDeltaPartialTick(boolean ignoreFrozen)`: `frozen && !ignore ? 1 : (paused ? pausedResidual : residual)`.
* No setter for the partial tick: Kinora overrides it with a mixin (see `docs/mixins.md`).

## Tick rate (`M/world/TickRateManager.java`)

Client instance: `ClientLevel.tickRateManager()`. Frozen (`!runsNormally()`): non-player entities, block
entities, time, weather, particles, animateTick and texture animation stop; players keep ticking; frozen
entities extract with partial tick 1.

## Camera (`M/client/Camera.java`)

* `update(DeltaTracker)`: `alignWithEntity(pt)` → `calculateFov` → `prepareCullFrustum` → `setupPerspective`.
* `alignWithEntity` posts `ViewportEvent.ComputeCameraAngles(camera, pt, yaw, pitch, roll=0)`, then sets
  position from the entity, then `detached = !cameraType.isFirstPerson()` and moves back if detached.
* Protected `setPosition(Vec3)`, `setRotation(float yRot, float xRot, float roll)`; roll is part of the
  quaternion (view matrix and frustum).
* FOV: private `calculateFov(float)` → `ViewportEvent.ComputeFov` (fires twice per frame: world and HUD).
* `Camera.tick()` samples biome environment attributes at the camera position.
* `Minecraft.setCameraEntity(Entity)`; `LocalPlayer.isControlledCamera()` gates input and position sends.

**Decision:** one mixin into `Camera` (after `alignWithEntity`) sets the exact double position, yaw,
pitch, roll and clears `detached`; another `@ModifyReturnValue` on `calculateFov` sets FOV. The camera
entity stays the LocalPlayer.

## Interpolation

* `Entity.xo/yo/zo`, `xOld/yOld/zOld`, `yRotO/xRotO`; `setOldPosAndRot()` before each tick.
* `EntityRenderer.extractRenderState` lerps from `xOld` with the partial tick; `ageInTicks = tickCount + pt`.
* `InterpolationHandler` (3 steps by default) smooths network moves per tick.

## Wall-clock visuals (must be redirected for deterministic renders)

| Site | Drives |
| --- | --- |
| `TextureTransform` (`Util.getMillis`) | enchantment glint |
| `WorldBorderRenderer` | world border scroll |
| `SectionRenderDispatcher` upload time / `LevelRenderer` visibility | chunk fade-in; also gates entity and block-entity visibility (≥ 0.3) |
| `LerpingBossEvent`, `Hud`, `SubtitleOverlay`, `ToastManager` | GUI only |
| `AtmosphericFogEnvironment`, spyglass scope | `getGameTimeDeltaTicks()` smoothing |
| particle and `animateTick` randomness | `System.nanoTime`-seeded / thread-local random |

Deterministic already: clouds (`gameTime + pt`), weather (`gameTime + pt`, column-seeded random), sky
(`EnvironmentAttributeProbe` lerp), end portal `GameTime` uniform, banners, beacons.

## Animated textures

`TextureAtlas.tick` → `SpriteContents.AnimationState.tick` (integer sub-frames, no partial tick). Called once
per *frame* that has ticks: Kinora calls it once per tick instead during playback.

## Events

`ClientTickEvent.Pre/Post`, `RenderFrameEvent.Pre/Post` (too late to move the camera),
`ClientPauseChangeEvent.Pre` (cancellable), `ExtractLevelRenderStateEvent`, `RenderLevelStageEvent`
(`AfterSky`, `AfterOpaqueBlocks`, `AfterOpaqueFeatures`, `AfterTranslucentFeatures`, `AfterTranslucentBlocks`,
`AfterTranslucentParticles`, `AfterWeather`, `AfterLevel`).
