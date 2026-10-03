# Internals: rendering (Minecraft 26.2, NeoForge 26.2.0.88)

Verified against the decompiled sources. Paths are under `com/mojang/blaze3d/` unless they start with `M/`
(`net/minecraft/`).

## Backends

* Two complete backends: OpenGL (`opengl/`) and Vulkan (`vulkan/`), both `GpuBackend`.
  `M/client/PreferredGraphicsApi` (`DEFAULT`, `OPENGL`, `VULKAN`) picks the order; DEFAULT tries GL first.
* Shaders are GLSL `#version 330`; Vulkan compiles them with shaderc at runtime.
* Everything Kinora does on the GPU goes through the blaze3d abstraction so it works on both.

## Device, textures, buffers

* `RenderSystem.getDevice()` → `GpuDevice`: `createTexture(label, usage, GpuFormat, w, h, layers, mips)`,
  `createTextureView`, `createBuffer(label, usage, size)`, `createCommandEncoder()`, `getDeviceInfo()`
  (`limits().maxTextureSize`, `isZZeroToOne`).
* Texture usage: `COPY_DST 1`, `COPY_SRC 2`, `TEXTURE_BINDING 4`, `RENDER_ATTACHMENT 8`.
  Buffer usage: `MAP_READ 1`, `MAP_WRITE 2`, `CLIENT_STORAGE 4`, `COPY_DST 8`, `COPY_SRC 16`, `UNIFORM 128`.
* `CommandEncoder.createRenderPass(label, colorView, Optional<Vector4fc> clear[, depthView, OptionalDouble])`,
  `copyTextureToBuffer(src, dst, offset, Runnable callback, mip[, x, y, w, h])`,
  `copyTextureToTexture(src, dst, mip, dx, dy, sx, sy, w, h)`, `clearColorTexture`, `clearDepthTexture`.
* `GpuBuffer.map(read, write)` → `GpuBufferSlice.MappedView` (AutoCloseable, `data()` ByteBuffer).
* **Readback is asynchronous, one or two frames late**: GL runs `glReadPixels` into a PBO and the callback
  runs from `RenderSystem.executePendingTasks()` next frame; Vulkan runs it after the submission
  completes. Waiting on the current submit throws.
* GL copies a texture by attaching it as a colour attachment, so a depth texture cannot be read back
  directly on GL: depth is first written into an `R32_FLOAT` colour target by a shader.
* Formats (`GpuFormat`): RGBA8, RGBA16_FLOAT, RGBA32_FLOAT, R32_FLOAT, D32_FLOAT, ...
* `RenderPipeline.builder(...)...withColorTargetState(new ColorTargetState(Optional<BlendFunction>, GpuFormat, mask))`:
  the attachment format must match the pipeline's declared format.

## Render targets and size

* Main target: `GameRenderer.mainRenderTarget()`, a `MainTarget` (RGBA8 + D32_FLOAT).
* `GameRenderer.render` resizes the main target to `windowRenderState` size if they differ;
  `GameRenderer.resize(w, h)` resizes the main target and `levelRenderer.resize`.
* `Window.setWidth/setHeight` only overwrite the framebuffer size fields (no GLFW call). Vanilla's
  panorama screenshot uses exactly this to render 4096² with a small window. Camera aspect, culling,
  `ScreenSize` uniform and GUI projection follow the window size.
* `TextureTarget(label, w, h, useDepth, format)` makes an off-screen target.

## Projection and depth

* `Projection.getMatrix` swaps near and far: **reversed Z** (clear depth 0, compare GREATER_OR_EQUAL,
  zero-to-one clip). Linear depth: `near*far / (near + d*(far - near))` with near 0.05.
* `CameraRenderState.projectionMatrix` (public field) can be overwritten in `RenderFrameEvent.Pre` (after
  extract, before render) for tiled rendering; culling uses `Camera.getCullFrustum()` from extract time.
* No projection event exists.

## Post-processing and passes

* Post-effect JSON (`assets/<ns>/post_effect/<id>.json`) targets are hard-coded RGBA8.
* `FrameGraphSetupEvent` (`getFrameGrapBuilder()`, typo in NeoForge), `RegisterRenderPipelinesEvent`
  (mod bus), `RenderLevelStageEvent.AfterLevel` (before the hand pass clears depth).
* Full-screen pass: vertex shader `minecraft:core/screenquad`, `draw(3, 1, 0, 0)`.
* Shaders: `assets/<ns>/shaders/<path>.vsh/.fsh`, includes from `shaders/include/` (`globals.glsl`).

## Chunk readiness

* `LevelRenderer.hasRenderedAllSections()` (queue empty only), `isSectionCompiledAndVisible(BlockPos)`,
  `expectedChunks()`, `LevelExtractor.countRenderedSections()`, `SectionRenderDispatcher.getCompileQueueSize()`.
* `options.prioritizeChunkUpdates()` (`NONE`, `PLAYER_AFFECTED`, `NEARBY`): NEARBY compiles nearby
  sections synchronously.
* `options.chunkSectionFadeInTime()` (default 0.75 s, wall clock) must be 0 for deterministic output.
* Skins load asynchronously: `SkinManager.get(GameProfile)` future.
* Kinora's settle rule: sections queue empty, no expected chunks, rendered-section count unchanged for N
  frames, skins of visible players loaded.

## GUI and hand

* The GUI is drawn into the main target after the world (`GameRenderer.render`: clear depth, `guiRenderer.render()`).
* Hand is skipped when the HUD is hidden, not first person, or spectator; `RenderHandEvent` cancels it.

## Screenshot

`Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)`: buffer `MAP_READ|COPY_DST`,
`copyTextureToBuffer`, map in the callback, flip rows (textures are bottom-up on both backends), alpha
forced to 255. No tiled facility exists.

## What Kinora's offline renderer does with this (M4, measured)

* One output image per game frame, in the normal game loop: the replay's time driver sets the tick and
  partial tick, the camera director takes the shot's camera, the window size is overridden to the
  output size (re-applied every frame, since a real resize resets it), and `GameRendererCaptureMixin`
  reads the main target back just before `GuiRenderer.render()` (after outlines and post effects;
  `AfterLevel` comes too early). The GUI drawn after it (the progress overlay) never reaches the file.
* **Settle rule.** `expectedChunks()` is useless in a replay: it lists chunks the camera could see that
  were never recorded, so it never empties (this made every frame wait for the timeout). Kinora waits
  for: compile queue empty (`hasRenderedAllSections`) and every section builder back in
  `renderBuffers().sectionBufferPool()` (free count at its maximum). If a frame had to wait, one more
  idle frame is rendered so the last compiled sections are uploaded. After a load or seek, the drawn
  section count (`levelExtractor.countRenderedSections()`) must also hold still for 10 frames.
* **Throttles removed while rendering:** vsync off, `FramerateLimitTracker.getFramerateLimit` lifted
  (`FramerateLimitMixin`; vanilla caps a minimised window at 10 fps and an idle player at 30), chunk
  fade-in 0, sounds muted, every screen opening cancelled (focus loss would open the pause menu).
* Measured on the dev machine, flat test world: 640x360 at ~50 fps, 1920x1080 at ~37 fps to PNG
  (PNG encoding on parallel threads; single-threaded it was ~7 fps).
* Determinism: two renders of the same shot are bit-identical (verified for 300 frames at 1080p, and for
  a shot with animated water in view). Three history-dependent inputs had to be pinned:
  * the level clock: it keeps ticking during the loading screen for a wall-clock-dependent number of
    ticks; `ReplaySession.resyncTime` re-applies the last time packet, moved on to the current tick,
    when loading ends (clouds move with game time);
  * snapshot restores: the restored time packet is advanced from its recorded tick to the snapshot
    tick (`PlaybackFilter.setTimeAdvance`);
  * animated textures: `SpriteAnimationMixin` sets each animation's frame from the replay tick.
* Video: frames go to FFmpeg as raw RGBA through a pipe, one NUT part per run (exact timestamps;
  Matroska rounds to milliseconds and gave 17/16/17 ms frame durations), joined with the concat
  demuxer; MP4/MOV get `-video_track_timescale <fps numerator>`. Verified with ffprobe: H.264, H.265
  (MP4), ProRes 422 HQ (MOV), stopped-and-resumed H.264: exact frame counts and 5.000 s duration.
  The gyan.dev "essentials" FFmpeg has no SVT-AV1; the dialog marks formats the FFmpeg lacks.
* Resource packs: nothing pack-specific in the pipeline; renders with Faithful 32x are bit-identical
  across runs. (Packs that only declare `pack_format` are flagged incompatible by 26.2 itself.)
* Sodium replaces vanilla's section builder: `TerrainReadiness` asks
  `SodiumWorldRenderer.isTerrainRenderComplete()` reflectively. Iris: the optional
  `kinora.compat.mixins.json` pins `frameTimeCounter`/`frameTime`/`frameCounter` to the render clock.
