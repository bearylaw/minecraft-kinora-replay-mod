# Internals: GUI, input and sound (Minecraft 26.2, NeoForge 26.2.0.88)

Verified against the decompiled sources. `M/` = `net/minecraft/`.

## Renames that matter in 26.2

* `GuiGraphics` → **`GuiGraphicsExtractor`**; `render` → **`extractRenderState`**.
* `Minecraft.setScreen` / `Minecraft.screen` → **`Minecraft.gui.setScreen(..)` / `gui.screen()`**; NeoForge adds
  `pushScreenLayer` / `popScreenLayer`.
* The HUD is `Minecraft.gui.hud` (`Hud`); `options.hideGui` is gone: `Hud.isHidden()` / `Hud.toggle()`.

## Screens

* `Screen(Component)`, `init()`, `tick()`, `removed()`, `onClose()` (pops the screen layer),
  `isPauseScreen()`, `extractRenderState(GuiGraphicsExtractor, int, int, float)`,
  `extractBackground(...)`, `addRenderableWidget`, `addWidget`, `removeWidget`, `clearWidgets`.
* Input uses records from `M/client/input/`: `MouseButtonEvent(x, y, MouseButtonInfo)`,
  `KeyEvent(key, scancode, modifiers)`, `CharacterEvent(codepoint)`, all with `hasShiftDown()` etc.
* `mouseClicked(MouseButtonEvent, boolean doubleClick)`, `mouseReleased(MouseButtonEvent)`,
  `mouseDragged(MouseButtonEvent, dx, dy)`, `mouseScrolled(x, y, scrollX, scrollY)`, `keyPressed(KeyEvent)`,
  `charTyped(CharacterEvent)`.
* `ContainerEventHandler` only forwards drags of button 0 to the focused child: timeline screens handle
  drags themselves.

## Drawing (`GuiGraphicsExtractor`)

* `pose()` is a `Matrix3x2fStack`; `enableScissor/disableScissor`; `fill`, `fillGradient`, `outline`,
  `horizontalLine`, `verticalLine`; `text(Font, String|Component, x, y, color[, shadow])` (alpha 0 draws
  nothing); `blit(...)`, `blitSprite(...)`; `blit(GpuTextureView, GpuSampler, x0, y0, x1, y1, u0, u1, v0, v1)`.
* Rendering is deferred: calls record render-state objects drawn later by `GuiRenderer`. Custom geometry
  (diagonal lines, curves) goes through an own `GuiElementRenderState` submitted with
  `submitGuiElementRenderState`, emitting quads in `POSITION_COLOR` (`RenderPipelines.GUI`).
* Dynamic textures: `DynamicTexture`, `AbstractTexture.getTextureView()/getSampler()`;
  `RenderSystem.getSamplerCache().getClampToEdge(FilterMode)`.

## Widgets

`AbstractWidget` (abstract `extractWidgetRenderState`, `updateWidgetNarration`), `Button.builder(..)`,
`EditBox`, `AbstractSliderButton`, `CycleButton`, `Tooltip.create`.

## Hooking screens and HUD

* `ScreenEvent.Init.Post#addListener` adds a widget that renders and receives input.
* `RenderGuiLayerEvent.Pre` (cancellable) with `VanillaGuiLayers` ids (`CHAT`, `HOTBAR`, `CROSSHAIR`, ...).
* `RenderHandEvent` (cancellable), `RenderNameTagEvent.CanRender` (`TriState`).

## Keys and mouse

* `KeyMapping(String, IKeyConflictContext, InputConstants.Type, int, KeyMapping.Category)`;
  `KeyMapping.Category(Identifier)`, registered by `RegisterKeyMappingsEvent.registerCategory`.
* Clicks are only counted with no screen open; in screens match `KeyMapping.matches(KeyEvent)`.
* `InputConstants.isKeyDown(Window, key)`; `MouseHandler.grabMouse()` closes the current screen.
* `CalculatePlayerTurnEvent` (sensitivity, cinematic camera); `InputEvent.MouseScrollingEvent`
  (only without a screen); `InputEvent.MouseButton.Pre` (cancellable); `InputEvent.Key`.
* No gamepad support exists in vanilla (no GLFW joystick calls).

## Config, commands, toasts

* `ModConfigSpec.Builder` (`define`, `defineInRange`, `defineEnum`, `comment`, `translation`, `push/pop`),
  `ModContainer.registerConfig(ModConfig.Type.CLIENT, spec)`, `ConfigurationScreen` via `IConfigScreenFactory`.
* `RegisterClientCommandsEvent#getDispatcher()`.
* `SystemToast.add(ToastManager, SystemToastId, Component, Component)`, `minecraft.gui.toastManager()`.
* `Util.getPlatform().openPath(Path)`.

## Sound

* `SoundManager.play(SoundInstance)`; `SoundEngine.play` posts `PlaySoundEvent` (setSound(null)
  suppresses), resolves the variant, computes `volume = clamp(instanceVolume) * finalSourceVolume * gain`
  and `pitch = clamp(pitch, 0.5, 2.0)`, then calls **`SoundEventListener.onPlaySound(instance, events, range)`**
  (`SoundManager.addListener`) before the zero-volume check: Kinora's capture point.
* Attenuation (`Channel`): `AL_LINEAR_DISTANCE`, max distance `d = max(volume,1) * sound.attenuationDistance`,
  gain = `clamp(1 - dist/d, 0, 1)`.
* Listener: `SoundEngine.updateSource(Camera)` → `ListenerTransform(position, forward, up)`.
* `Sound.getPath()` = `ns:sounds/<path>.ogg`; `WeighedSoundEvents.getSound(RandomSource)`.
* Decoding: `M/client/sounds/JOrbisAudioStream(InputStream)`, `getFormat()`, `readChunk(FloatConsumer)`
  (interleaved float PCM). Safe per instance on any thread.
* Packet sounds (`ClientboundSoundPacket`, `ClientboundSoundEntityPacket`) carry a seed and are
  reproducible; client-local sounds (`playLocalSound`, `LocalPlayer.playSound`) use a random seed.
* `SelectMusicEvent#setMusic(null)` suppresses music; `SoundManager.updateCategoryVolume(source, gain)`
  mutes per category without touching options.
