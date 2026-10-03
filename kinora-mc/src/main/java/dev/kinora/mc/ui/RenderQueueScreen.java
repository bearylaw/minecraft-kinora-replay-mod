package dev.kinora.mc.ui;

import dev.kinora.core.render.RenderJob;
import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.playback.ReplaySession;
import dev.kinora.mc.render.RenderJobs;
import dev.kinora.mc.render.RenderRunner;
import dev.kinora.mc.ui.kit.Theme;
import dev.kinora.mc.ui.kit.Ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders waiting, stopped, failed and finished. A stopped render resumes where it left off; a job
 * for another replay is started by opening that replay first.
 */
public final class RenderQueueScreen extends Screen {
    private static final int ROW = 24;

    private final @Nullable Screen parent;
    private List<RenderJob> jobs = List.of();
    private int selected = -1;
    private int scroll;

    public RenderQueueScreen(@Nullable Screen parent) {
        super(Component.translatable("kinora.queue.title"));
        this.parent = parent;
    }

    private int listTop() {
        return 28;
    }

    private int listBottom() {
        return this.height - 34;
    }

    private int listLeft() {
        return Math.max(8, this.width / 2 - 220);
    }

    private int listRight() {
        return Math.min(this.width - 8, this.width / 2 + 220);
    }

    @Override
    protected void init() {
        jobs = RenderJobs.list();
        if (selected >= jobs.size()) {
            selected = jobs.size() - 1;
        }
        RenderJob job = selected >= 0 ? jobs.get(selected) : null;
        int y = this.height - 28;
        int x = listLeft();
        int w = (listRight() - listLeft() - 12) / 4;
        Button start = addRenderableWidget(Button.builder(Component.translatable(job != null && job.framesDone > 0
                ? "kinora.queue.resume" : "kinora.queue.start"), b -> start(job)).bounds(x, y, w, 20).build());
        start.active = job != null && job.state != RenderJob.State.DONE && !RenderRunner.rendering() && sameReplay(job);
        if (job != null && !sameReplay(job)) {
            start.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("kinora.queue.other_replay")));
        }
        Button folder = addRenderableWidget(Button.builder(Component.translatable("kinora.queue.open_folder"), b -> openFolder(job))
                .bounds(x + w + 4, y, w, 20).build());
        folder.active = job != null;
        Button remove = addRenderableWidget(Button.builder(Component.translatable("kinora.queue.remove"), b -> {
            RenderJobs.delete(job);
            rebuild();
        }).bounds(x + 2 * (w + 4), y, w, 20).build());
        remove.active = job != null && job.state != RenderJob.State.RENDERING;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x + 3 * (w + 4), y, w, 20).build());
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private static boolean sameReplay(RenderJob job) {
        ReplaySession session = ReplayManager.INSTANCE.session();
        return session != null && session.file().path().toAbsolutePath().normalize().toString().equals(job.replayFile);
    }

    private void start(@Nullable RenderJob job) {
        if (job == null) {
            return;
        }
        if (job.state == RenderJob.State.FAILED) {
            job.state = RenderJob.State.QUEUED;
        }
        RenderRunner.start(job, RenderJobs.fileFor(job));
    }

    private static void openFolder(@Nullable RenderJob job) {
        if (job == null) {
            return;
        }
        Path out = Path.of(job.output);
        Path folder = Files.isDirectory(out) ? out : out.getParent();
        if (folder != null && Files.isDirectory(folder)) {
            Util.getPlatform().openPath(folder);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double mx = event.x();
        double my = event.y();
        if (Ui.inside(mx, my, listLeft(), listTop(), listRight(), listBottom())) {
            int index = (int) ((my - listTop()) / ROW) + scroll;
            if (index >= 0 && index < jobs.size()) {
                selected = index;
                rebuild();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int visible = (listBottom() - listTop()) / ROW;
        scroll = Math.max(0, Math.min(Math.max(0, jobs.size() - visible), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.panel(g, listLeft() - 4, 4, listRight() + 4, this.height - 4);
        g.centeredText(this.font, this.title, this.width / 2, 12, Theme.text());
        if (jobs.isEmpty()) {
            g.centeredText(this.font, Component.translatable("kinora.queue.empty"), this.width / 2, listTop() + 20, Theme.textDim());
        }
        int y = listTop();
        int w = listRight() - listLeft();
        for (int i = scroll; i < jobs.size() && y + ROW <= listBottom(); i++) {
            RenderJob job = jobs.get(i);
            if (i == selected) {
                g.fill(listLeft(), y, listRight(), y + ROW - 2, Theme.selection());
            }
            g.text(this.font, Ui.fit(this.font, job.title, w - 110), listLeft() + 4, y + 2, Theme.text(), false);
            Component state = Component.translatable("kinora.queue.state." + job.state.name().toLowerCase(java.util.Locale.ROOT));
            boolean sel = i == selected;
            int stateColor = sel ? Theme.text() : job.state == RenderJob.State.FAILED ? Theme.danger()
                    : job.state == RenderJob.State.DONE ? Theme.accent() : Theme.textDim();
            g.text(this.font, state, listRight() - 4 - this.font.width(state), y + 2, stateColor, false);
            String detail = job.state == RenderJob.State.FAILED && !job.error.isEmpty() ? job.error
                    : job.framesDone + " / " + job.frameCount + "  " + job.settings.width + "x" + job.settings.height + "  "
                    + Path.of(job.output).getFileName();
            g.text(this.font, Ui.fit(this.font, detail, w - 8), listLeft() + 4, y + 12, sel ? Theme.text() : Theme.textDim(), false);
            y += ROW;
        }
        if (selected >= 0 && selected < jobs.size() && !jobs.get(selected).report.isBlank()) {
            // The finished render's report: where the time went and how to go faster.
            var lines = this.font.split(Component.literal(jobs.get(selected).report), w - 8);
            int ry = listBottom() - lines.size() * 10 - 4;
            g.fill(listLeft(), ry - 4, listRight(), listBottom(), 0xC0101418);
            for (var line : lines) {
                g.text(this.font, line, listLeft() + 4, ry, Theme.textDim(), false);
                ry += 10;
            }
        }
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
