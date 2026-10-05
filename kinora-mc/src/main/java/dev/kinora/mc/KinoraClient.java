package dev.kinora.mc;

import dev.kinora.api.KinoraConstants;
import dev.kinora.core.format.Marker;
import dev.kinora.mc.capture.RecordingManager;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.ui.KinoraUi;
import dev.kinora.mc.ui.ReplayControls;
import dev.kinora.mc.ui.ReplayHud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

import org.lwjgl.glfw.GLFW;

import java.util.Set;

/** Wires Kinora's client-side systems to the event buses. */
public final class KinoraClient {
    private static final Identifier REPLAYS_ICON = Identifier.fromNamespaceAndPath(KinoraConstants.MOD_ID, "icon/replays");
    /** HUD layers about the local player, which in a replay is only the camera. */
    private static final Set<Identifier> HIDDEN_IN_REPLAY = Set.of(VanillaGuiLayers.CROSSHAIR, VanillaGuiLayers.HOTBAR,
            VanillaGuiLayers.PLAYER_HEALTH, VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.VEHICLE_HEALTH,
            VanillaGuiLayers.AIR_LEVEL, VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.CONTEXTUAL_INFO_BAR,
            VanillaGuiLayers.CONTEXTUAL_INFO_BAR_BACKGROUND, VanillaGuiLayers.SELECTED_ITEM_NAME, VanillaGuiLayers.SPECTATOR_TOOLTIP,
            VanillaGuiLayers.EFFECTS, VanillaGuiLayers.CAMERA_OVERLAYS);

    private static final Identifier RENDER_LAYER = Identifier.fromNamespaceAndPath(KinoraConstants.MOD_ID, "render");

    private static int markerCount;

    private KinoraClient() {}

    static void init(IEventBus modBus) {
        modBus.addListener(KinoraKeys::register);
        modBus.addListener(KinoraClient::registerLayers);
        modBus.addListener(dev.kinora.mc.render.DepthCopy::register);
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) -> e.enqueueWork(dev.kinora.mc.compat.DistantHorizonsCompat::init));

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> ReplayManager.INSTANCE.onClientTickPre());
        NeoForge.EVENT_BUS.addListener(KinoraClient::onClientTickPost);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> {
            if (!ReplayManager.INSTANCE.active()) {
                RecordingManager.INSTANCE.onLoggedIn();
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            RecordingManager.INSTANCE.onLoggingOut();
            ReplayManager.INSTANCE.onLoggingOut();
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Pre e) -> ReplayManager.INSTANCE.onRenderFrame());
        // Per frame, not per tick: a paused replay runs no client ticks.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Post e) -> dev.kinora.mc.dev.DevScript.tick());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterLevel e) -> {
            dev.kinora.mc.render.RenderRunner render = dev.kinora.mc.render.RenderRunner.active();
            if (render != null) {
                render.afterLevel(Minecraft.getInstance().gameRenderer.mainRenderTarget());
            }
        });
        NeoForge.EVENT_BUS.addListener(KinoraClient::onKey);
        NeoForge.EVENT_BUS.addListener(KinoraClient::onScroll);
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Opening e) -> {
            // Nothing opens over a render: not the pause menu when the window loses focus, not chat.
            if (dev.kinora.mc.render.RenderRunner.rendering()) {
                e.setCanceled(true);
                return;
            }
            ReplayManager.INSTANCE.onScreenOpening(e);
        });
        NeoForge.EVENT_BUS.addListener(KinoraClient::onScreenInit);
        NeoForge.EVENT_BUS.addListener(KinoraClient::onGuiLayer);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderNameTagEvent.CanRender e) -> {
            if (ReplayManager.INSTANCE.active() && ReplayManager.INSTANCE.scene().hideNametags()) {
                e.setCanRender(net.minecraft.util.TriState.FALSE);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ToastAddEvent e) -> {
            // A replay is not a live session: its "server" warnings, advancements and tutorials are noise.
            if (ReplayManager.INSTANCE.active() && !dev.kinora.mc.util.Notify.isKinora(e.getToast())) {
                e.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderHandEvent e) -> {
            // In a replay the viewer is a camera, not a player: no hand in front of it.
            if (ReplayManager.INSTANCE.active()) {
                e.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlaySoundEvent e) -> {
            dev.kinora.mc.render.RenderRunner render = dev.kinora.mc.render.RenderRunner.active();
            if (render != null && e.getSound() != null) {
                render.onSound(e.getSound());
            }
            if (ReplayManager.INSTANCE.silenced()) {
                e.setSound(null);
            }
        });
    }

    private static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Identifier.fromNamespaceAndPath(KinoraConstants.MOD_ID, "recording"), KinoraHud::renderRecording);
        event.registerAboveAll(Identifier.fromNamespaceAndPath(KinoraConstants.MOD_ID, "replay"), ReplayHud::render);
        event.registerAboveAll(RENDER_LAYER, dev.kinora.mc.render.RenderOverlay::render);
    }

    private static void onClientTickPost(ClientTickEvent.Post event) {
        if (ReplayManager.INSTANCE.active()) {
            ReplayManager.INSTANCE.onClientTickPost();
        } else {
            RecordingManager.INSTANCE.onClientTick();
            handleRecordingKeys();
        }
    }

    private static void handleRecordingKeys() {
        Minecraft mc = Minecraft.getInstance();
        while (KinoraKeys.TOGGLE_RECORDING.consumeClick()) {
            RecordingManager.INSTANCE.toggle();
        }
        while (KinoraKeys.MARKER.consumeClick()) {
            markerCount++;
            RecordingManager.INSTANCE.marker(Component.translatable("kinora.marker.default_name", markerCount).getString(),
                    0xFFFFB000, "manual", Marker.SOURCE_MANUAL);
        }
        while (KinoraKeys.SAVE_BUFFER.consumeClick()) {
            RecordingManager.INSTANCE.saveBuffer();
        }
        while (KinoraKeys.OPEN_BROWSER.consumeClick()) {
            if (mc.gui.screen() == null && mc.level == null) {
                KinoraUi.openBrowser(null);
            }
        }
    }

    private static void onKey(InputEvent.Key event) {
        dev.kinora.mc.render.RenderRunner render = dev.kinora.mc.render.RenderRunner.active();
        if (render != null) {
            if (event.getAction() == GLFW.GLFW_PRESS && event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
                render.stop();
            }
            return;
        }
        if (event.getAction() == GLFW.GLFW_PRESS || event.getAction() == GLFW.GLFW_REPEAT) {
            ReplayControls.onKey(event.getKey(), event.getModifiers());
        }
    }

    private static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (ReplayManager.INSTANCE.active() && Minecraft.getInstance().gui.screen() == null) {
            ReplayManager.INSTANCE.camera().adjustSpeed(Math.signum(event.getScrollDeltaY()));
            event.setCanceled(true);
        }
    }

    private static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (dev.kinora.mc.render.RenderRunner.rendering()) {
            // Only the progress overlay; it is drawn after the frame is captured anyway.
            if (!event.getName().equals(RENDER_LAYER)) {
                event.setCanceled(true);
            }
            return;
        }
        if (ReplayManager.INSTANCE.active() && (HIDDEN_IN_REPLAY.contains(event.getName())
                || ReplayManager.INSTANCE.scene().hideChat() && event.getName().equals(VanillaGuiLayers.CHAT))) {
            event.setCanceled(true);
        }
    }

    private static boolean devReplayOpened;

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen title) {
            // Development and scripted tests: -Pdev replay opens straight from the title screen.
            String devReplay = System.getProperty("kinora.dev.openReplay");
            if (devReplay != null && !devReplayOpened) {
                devReplayOpened = true;
                Minecraft.getInstance().execute(() -> ReplayManager.INSTANCE.open(java.nio.file.Path.of(devReplay), TitleScreen::new));
            }
            int x = title.width / 2 + 104;
            int y = title.height / 4 + 32 + 24;
            SpriteIconButton button = SpriteIconButton.builder(Component.translatable("kinora.title.replays"),
                    b -> KinoraUi.openBrowser(title), true).sprite(REPLAYS_ICON, 16, 16).size(20, 20).build();
            button.setPosition(x, y);
            button.setTooltip(Tooltip.create(Component.translatable("kinora.title.replays")));
            event.addListener(button);
        }
    }
}
