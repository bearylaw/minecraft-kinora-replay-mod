package dev.kinora.mc.playback;

import dev.kinora.api.Kinora;
import dev.kinora.api.event.KinoraListener;
import dev.kinora.core.format.KinoraFile;
import dev.kinora.core.format.KinoraRecovery;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.camera.CameraDirector;
import dev.kinora.mc.hooks.CameraHooks;
import dev.kinora.mc.hooks.TimeHooks;
import dev.kinora.mc.ui.ReplayLoadingScreen;
import dev.kinora.mc.util.Notify;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Opens and closes replays, owns the replay camera, and keeps the rest of the game out of the way
 * while a replay is open: vanilla menus that act on the local player stay shut, the pause key opens
 * Kinora's menu, and leaving a replay always returns the game to the state it was in before.
 */
public final class ReplayManager {
    public static final ReplayManager INSTANCE = new ReplayManager();

    private final CameraDirector director = new CameraDirector();
    private final SceneOverrides scene = new SceneOverrides();
    private final dev.kinora.mc.editor.EditorState editor = new dev.kinora.mc.editor.EditorState();
    private boolean editorActive;
    private @Nullable ReplaySession session;
    private boolean restarting;
    private @Nullable Screen pendingScreen;
    private @Nullable Supplier<Screen> exitScreen;

    private ReplayManager() {}

    public @Nullable ReplaySession session() {
        return session;
    }

    public boolean active() {
        return session != null;
    }

    public CameraDirector camera() {
        return director;
    }

    public SceneOverrides scene() {
        return scene;
    }

    public dev.kinora.mc.editor.EditorState editor() {
        return editor;
    }

    /**
     * The editor screen opened or closed. While it is open with a shot selected, the shot drives
     * replay time and the camera.
     */
    public void setEditorActive(boolean active) {
        editorActive = active;
        updateCameraMode();
    }

    /** Points the camera at the editor's shot, or back to free flight. */
    public void updateCameraMode() {
        if (editorActive && (editor.shot() != null || editor.view() == dev.kinora.mc.editor.EditorState.View.SEQUENCE)) {
            if (director.mode() != CameraDirector.Mode.PATH) {
                director.path(editor);
            }
        } else if (director.mode() == CameraDirector.Mode.PATH) {
            director.free();
        }
    }

    private final ReplaySession.TimeDriver editorDriver = new ReplaySession.TimeDriver() {
        @Override
        public double frame() {
            return editorActive ? editor.frame() : Double.NaN;
        }

        @Override
        public void afterTick(long completedTicks) {
            editor.scene().afterTick(completedTicks);
        }
    };

    /**
     * Opens a replay. Must be called from the title screen or a Kinora screen with no world loaded.
     *
     * @param onExit the screen to show when the replay is closed
     */
    public void open(Path path, Supplier<Screen> onExit) {
        Minecraft mc = Minecraft.getInstance();
        if (session != null) {
            close();
        }
        if (mc.level != null) {
            Notify.warn(Component.translatable("kinora.replay.cannot_open"), Component.translatable("kinora.replay.leave_world_first"));
            return;
        }
        try {
            if (KinoraRecovery.needsRecovery(path)) {
                KinoraRecovery.recover(path);
            }
            KinoraFile file = KinoraFile.open(path);
            ReplaySession s = new ReplaySession(file);
            exitScreen = onExit;
            mc.gui.setScreen(new ReplayLoadingScreen(Component.translatable("kinora.replay.loading", path.getFileName().toString())));
            session = s;
            director.reset();
            scene.reset();
            editor.reset();
            editor.projects().openFor(file.header().fileId().toString(), path.getFileName().toString());
            s.setDriver(editorDriver);
            TimeHooks.install(s);
            CameraHooks.install(director);
            s.startAt(s.startTick());
            KinoraMod.LOG.info("Opened replay {} ({} records, {} snapshots)", path.getFileName(), file.endOrdinal(), file.snapshots().size());
            for (KinoraListener l : Kinora.listeners()) {
                l.onReplayStart();
            }
        } catch (IOException | RuntimeException e) {
            KinoraMod.LOG.error("Could not open replay {}", path, e);
            uninstall();
            if (mc.level != null) {
                mc.disconnect(onExit.get(), true);
            } else {
                mc.gui.setScreen(onExit.get());
            }
            Notify.warn(Component.translatable("kinora.replay.cannot_open"), Component.literal(String.valueOf(e.getMessage())));
        }
    }

    /** Leaves the replay and restores the game. */
    public void close() {
        ReplaySession s = session;
        if (s == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Screen next = exitScreen != null ? exitScreen.get() : new TitleScreen();
        editor.projects().flush();
        editorActive = false;
        uninstall();
        s.close();
        if (mc.level != null || mc.getConnection() != null) {
            mc.disconnect(next, true);
        } else {
            mc.gui.setScreen(next);
        }
        dev.kinora.mc.api.ModTracks.resetAll();
        for (KinoraListener l : Kinora.listeners()) {
            l.onReplayStop();
        }
    }

    private void uninstall() {
        session = null;
        TimeHooks.install(null);
        CameraHooks.install(null);
        Minecraft.getInstance().getSoundManager().resume();
    }

    /** Rebuilds the world for a backward or long seek. */
    void restart(ReplaySession s, double target) {
        Minecraft mc = Minecraft.getInstance();
        restarting = true;
        try {
            Screen back = mc.gui.screen();
            mc.disconnect(new ReplayLoadingScreen(Component.translatable("kinora.replay.seeking")), true, false);
            editor.scene().clear();
            s.restartAt(target);
            if (back instanceof dev.kinora.mc.ui.EditorScreen) {
                // Return to the editor once the world is rebuilt.
                pendingScreen = back;
            }
        } catch (IOException | RuntimeException e) {
            KinoraMod.LOG.error("Seek failed", e);
            Notify.warn(Component.translatable("kinora.replay.seek_failed"), Component.literal(String.valueOf(e.getMessage())));
            close();
        } finally {
            restarting = false;
        }
    }

    // ------------------------------------------------------------------ events

    public void onClientTickPre() {
        if (session == null) {
            return;
        }
        // Vanilla would act on these for the camera player: inventory, chat, attacks, item use...
        Minecraft mc = Minecraft.getInstance();
        drain(mc.options.keyInventory);
        drain(mc.options.keyDrop);
        drain(mc.options.keySwapOffhand);
        drain(mc.options.keyAttack);
        drain(mc.options.keyUse);
        drain(mc.options.keyPickItem);
        drain(mc.options.keyAdvancements);
        drain(mc.options.keySocialInteractions);
        drain(mc.options.keyTogglePerspective);
        drain(mc.options.keyChat);
        drain(mc.options.keyCommand);
        for (KeyMapping hotbar : mc.options.keyHotbarSlots) {
            drain(hotbar);
        }
    }

    private static void drain(KeyMapping key) {
        while (key.consumeClick()) {
            // discarded
        }
    }

    public void onClientTickPost() {
        ReplaySession s = session;
        if (s == null) {
            return;
        }
        s.onClientTickPost();
        scene.apply();
        director.syncPlayer();
        editor.projects().tick();
        if (Minecraft.getInstance().getConnection() == null && s.phase() == ReplaySession.Phase.PLAYING && !restarting) {
            // The connection went away under us (a replayed packet disconnected it).
            KinoraMod.LOG.warn("Replay connection closed unexpectedly");
            uninstall();
            s.close();
        }
    }

    /** Every frame, before rendering. */
    public void onRenderFrame() {
        ReplaySession s = session;
        if (s != null && pendingScreen != null && s.phase() == ReplaySession.Phase.PLAYING) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui.screen() == null || mc.gui.screen() instanceof ReplayLoadingScreen) {
                Screen screen = pendingScreen;
                pendingScreen = null;
                mc.gui.setScreen(screen);
            }
        }
    }

    public void onScreenOpening(ScreenEvent.Opening event) {
        if (session == null) {
            return;
        }
        Screen screen = event.getNewScreen();
        if (screen instanceof PauseScreen) {
            event.setNewScreen(new dev.kinora.mc.ui.ReplayMenuScreen());
        } else if (screen instanceof DeathScreen || screen instanceof AbstractContainerScreen<?> || screen instanceof BookViewScreen
                || screen instanceof AbstractSignEditScreen || screen instanceof ChatScreen) {
            event.setCanceled(true);
        } else if (screen instanceof DisconnectedScreen && !restarting) {
            // A replayed packet made the client disconnect: explain instead of showing a server error.
            ReplaySession s = session;
            uninstall();
            if (s != null) {
                s.close();
            }
            Notify.warn(Component.translatable("kinora.replay.disconnected"), Component.translatable("kinora.replay.disconnected_hint"));
        }
    }

    public void onLoggingOut() {
        if (session != null && !restarting) {
            ReplaySession s = session;
            uninstall();
            s.close();
        }
    }

    /** Hands replay time back to the editor (after a render drove it). */
    public void restoreDriver() {
        if (session != null) {
            session.setDriver(editorDriver);
        }
    }

    public boolean silenced() {
        ReplaySession s = session;
        return s != null && (s.fastForwarding() || s.phase() == ReplaySession.Phase.LOADING || dev.kinora.mc.render.RenderRunner.rendering());
    }
}
