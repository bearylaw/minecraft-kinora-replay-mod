package dev.kinora.mc.ui;

import dev.kinora.core.format.ClipExporter;
import dev.kinora.mc.KinoraMod;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;
import dev.kinora.mc.ui.kit.Theme;
import dev.kinora.mc.ui.kit.Ui;
import dev.kinora.mc.util.KinoraPaths;
import dev.kinora.mc.util.Notify;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Saves a stretch of the open replay as its own, smaller replay file, for sharing a moment or
 * keeping a highlight. Times are seconds from the start of the replay.
 */
public final class ClipScreen extends Screen {
    private final @Nullable Screen parent;
    private EditBox startBox;
    private EditBox endBox;

    public ClipScreen(@Nullable Screen parent) {
        super(Component.translatable("kinora.clip.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ReplaySession session = ReplayManager.INSTANCE.session();
        double now = session == null ? 0 : (session.clock().time() - session.startTick()) / 20.0;
        double length = session == null ? 0 : (session.endTick() - session.startTick()) / 20.0;
        int x = this.width / 2 - 100;
        int y = this.height / 2 - 40;
        startBox = new EditBox(this.font, x, y + 10, 95, 16, Component.translatable("kinora.clip.start"));
        startBox.setValue(String.format(Locale.ROOT, "%.1f", Math.max(0, now - 10)));
        endBox = new EditBox(this.font, x + 105, y + 10, 95, 16, Component.translatable("kinora.clip.end"));
        endBox.setValue(String.format(Locale.ROOT, "%.1f", Math.min(length, now + 10)));
        addRenderableWidget(startBox);
        addRenderableWidget(endBox);
        addRenderableWidget(Button.builder(Component.translatable("kinora.clip.export"), b -> export()).bounds(x, y + 40, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x + 102, y + 40, 98, 20).build());
    }

    private void export() {
        ReplaySession session = ReplayManager.INSTANCE.session();
        if (session == null) {
            return;
        }
        double start;
        double end;
        try {
            start = Double.parseDouble(startBox.getValue().strip());
            end = Double.parseDouble(endBox.getValue().strip());
        } catch (NumberFormatException e) {
            return;
        }
        long base = session.startTick();
        String name = session.file().path().getFileName().toString().replaceFirst("\\.kinora$", "");
        Path out = KinoraPaths.replays().resolve(KinoraPaths.sanitize(name + "_clip_" + (int) start + "-" + (int) end, 120) + ".kinora");
        try {
            var result = ClipExporter.export(session.file(), base + Math.round(start * 20), base + Math.round(end * 20), out);
            Notify.info(Component.translatable("kinora.clip.done"), Component.literal(out.getFileName().toString()));
            KinoraMod.LOG.info("Kinora exported a clip: {} ({} records, {} bytes)", out, result.records(), result.bytes());
            Util.getPlatform().openPath(KinoraPaths.replays());
            onClose();
        } catch (Exception e) {
            KinoraMod.LOG.warn("Kinora could not export a clip", e);
            Notify.warn(Component.translatable("kinora.clip.failed"), Component.literal(String.valueOf(e.getMessage())));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = this.width / 2 - 110;
        int y = this.height / 2 - 66;
        Ui.panel(g, x, y, x + 220, y + 110);
        g.centeredText(this.font, this.title, this.width / 2, y + 8, Theme.text());
        g.text(this.font, Component.translatable("kinora.clip.start"), this.width / 2 - 100, this.height / 2 - 40, Theme.textDim(), false);
        g.text(this.font, Component.translatable("kinora.clip.end"), this.width / 2 + 5, this.height / 2 - 40, Theme.textDim(), false);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
