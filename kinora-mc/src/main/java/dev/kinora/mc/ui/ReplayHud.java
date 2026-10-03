package dev.kinora.mc.ui;

import dev.kinora.core.format.Marker;
import dev.kinora.mc.camera.CameraDirector;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** The overlay shown over a replay while flying: time, progress, markers, speed and mode. */
public final class ReplayHud {
    private ReplayHud() {}

    public static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        ReplaySession session = ReplayManager.INSTANCE.session();
        if (session == null || mc.gui.hud.isHidden() || mc.gui.screen() != null) {
            return;
        }
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int barX = 12;
        int barW = width - 24;
        int barY = height - 14;
        double start = session.startTick();
        double end = Math.max(start + 1, session.endTick());
        double now = session.clock().time();
        double progress = Math.max(0, Math.min(1, (now - start) / (end - start)));

        graphics.fill(barX - 4, barY - 16, barX + barW + 4, barY + 8, 0x80000000);
        graphics.fill(barX, barY, barX + barW, barY + 3, 0xFF3A3A3A);
        graphics.fill(barX, barY, barX + (int) (barW * progress), barY + 3, 0xFFE0A030);
        for (Marker m : session.markers()) {
            int mx = barX + (int) (barW * Math.max(0, Math.min(1, (m.tick() - start) / (end - start))));
            graphics.fill(mx, barY - 3, mx + 1, barY + 6, m.color() | 0xFF000000);
        }
        String time = clock(now - start) + " / " + clock(end - start);
        String state = session.fastForwarding() ? "⏩" : session.paused() ? "⏸" : "▶";
        String speed = session.clock().speed() == 1.0 ? "" : String.format(Locale.ROOT, "  %sx", trim(session.clock().speed()));
        graphics.text(mc.font, state + " " + time + speed, barX, barY - 12, 0xFFFFFFFF, false);

        CameraDirector camera = ReplayManager.INSTANCE.camera();
        Component mode = Component.translatable("kinora.hud.camera." + camera.mode().name().toLowerCase(Locale.ROOT));
        int modeWidth = mc.font.width(mode);
        graphics.text(mc.font, mode, barX + barW - modeWidth, barY - 12, 0xFFB0D0FF, false);
        if (session.errors() > 0) {
            Component errors = Component.translatable("kinora.hud.errors", session.errors());
            graphics.text(mc.font, errors, barX + barW - modeWidth - mc.font.width(errors) - 12, barY - 12, 0xFFFF8060, false);
        }
        var live = dev.kinora.mc.editor.LiveCutSession.active();
        if (live != null) {
            renderLiveCut(graphics, mc, live);
            return;
        }
        if (session.paused()) {
            graphics.centeredText(mc.font, Component.translatable("kinora.hud.help",
                    ReplayKeys.keyName(ReplayKeys.Action.PLAY_PAUSE), ReplayKeys.keyName(ReplayKeys.Action.BACK),
                    ReplayKeys.keyName(ReplayKeys.Action.FORWARD), ReplayKeys.keyName(ReplayKeys.Action.TICK_BACK),
                    ReplayKeys.keyName(ReplayKeys.Action.TICK_FORWARD), ReplayKeys.keyName(ReplayKeys.Action.SLOWER),
                    ReplayKeys.keyName(ReplayKeys.Action.FASTER), ReplayKeys.keyName(ReplayKeys.Action.SPECTATE),
                    ReplayKeys.keyName(ReplayKeys.Action.ORBIT), ReplayKeys.keyName(ReplayKeys.Action.EDITOR)),
                    width / 2, barY - 30, 0xC0FFFFFF);
        }
    }

    /** The cameras of a live cut, the one on air marked, and its keys. */
    private static void renderLiveCut(GuiGraphicsExtractor graphics, Minecraft mc, dev.kinora.mc.editor.LiveCutSession live) {
        var cameras = live.cameras();
        int x = 8;
        int y = 8;
        int w = 150;
        for (var c : cameras) {
            w = Math.max(w, mc.font.width(c.name) + 30);
        }
        Component keys = Component.translatable("kinora.live.keys", ReplayKeys.keyName(ReplayKeys.Action.LIVE_CUT));
        w = Math.max(w, mc.font.width(keys) + 8);
        graphics.fill(x - 4, y - 4, x + w, y + 14 + cameras.size() * 11 + 14, 0xA0000000);
        graphics.text(mc.font, Component.translatable("kinora.live.on_air", live.cuts()), x, y, 0xFFFF5050, false);
        for (int i = 0; i < cameras.size(); i++) {
            boolean on = i == live.camera();
            int cy = y + 14 + i * 11;
            if (on) {
                graphics.fill(x - 2, cy - 1, x + w - 6, cy + 9, 0xC0B03030);
            }
            graphics.text(mc.font, (i + 1) + "  " + cameras.get(i).name, x, cy, on ? 0xFFFFFFFF : 0xFFB0B0B0, false);
        }
        graphics.text(mc.font, keys, x, y + 14 + cameras.size() * 11 + 3, 0xFF909090, false);
    }

    static String clock(double ticks) {
        double seconds = Math.max(0, ticks) / 20.0;
        long whole = (long) seconds;
        int tenths = (int) ((seconds - whole) * 10);
        return whole >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d.%d", whole / 3600, (whole / 60) % 60, whole % 60, tenths)
                : String.format(Locale.ROOT, "%d:%02d.%d", whole / 60, whole % 60, tenths);
    }

    static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
