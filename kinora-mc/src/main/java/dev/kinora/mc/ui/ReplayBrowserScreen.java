package dev.kinora.mc.ui;

import dev.kinora.mc.playback.ReplayManager;
import dev.kinora.mc.util.KinoraPaths;
import dev.kinora.mc.util.Notify;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Lists the replays on disk and opens them. */
public final class ReplayBrowserScreen extends Screen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final @Nullable Screen parent;
    private ReplayList replayList;
    private EditBox search;
    private Button openButton;
    private Button deleteButton;
    private List<ReplayLibrary.Entry> entries = List.of();

    public ReplayBrowserScreen(@Nullable Screen parent) {
        super(Component.translatable("kinora.browser.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        search = new EditBox(this.font, this.width / 2 - 100, 22, 200, 18, Component.translatable("kinora.browser.search"));
        search.setHint(Component.translatable("kinora.browser.search"));
        search.setResponder(text -> refill());
        addRenderableWidget(search);

        replayList = new ReplayList(this.minecraft, this.width, this.height - 96, 46, 36);
        addRenderableWidget(replayList);

        int y = this.height - 46;
        openButton = addRenderableWidget(Button.builder(Component.translatable("kinora.browser.open"), b -> openSelected())
                .bounds(this.width / 2 - 154, y, 100, 20).build());
        deleteButton = addRenderableWidget(Button.builder(Component.translatable("kinora.browser.delete"), b -> deleteSelected())
                .bounds(this.width / 2 - 50, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("kinora.browser.folder"), b -> Util.getPlatform().openPath(KinoraPaths.replays()))
                .bounds(this.width / 2 + 54, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("kinora.browser.refresh"), b -> reload())
                .bounds(this.width / 2 - 154, y + 24, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(this.width / 2 + 4, y + 24, 150, 20).build());
        reload();
    }

    /** Shared packages dropped onto the window are imported. */
    @Override
    public void onFilesDrop(List<Path> files) {
        for (Path f : files) {
            if (f.getFileName().toString().endsWith("." + dev.kinora.core.project.SharePackage.EXTENSION)) {
                importPackage(f);
            }
        }
        reload();
    }

    /** Unpacks a shared replay + project package into the replays and projects folders. */
    private static void importPackage(Path pack) {
        try {
            var imported = dev.kinora.core.project.SharePackage.importPack(pack, dev.kinora.mc.util.KinoraPaths.replays(),
                    dev.kinora.mc.util.KinoraPaths.projects(), dev.kinora.mc.editor.ProjectManager.versionsDir());
            dev.kinora.mc.util.Notify.info(Component.translatable("kinora.share.imported"), Component.literal(imported.replay().getFileName().toString()));
        } catch (java.io.IOException | RuntimeException e) {
            dev.kinora.mc.util.Notify.warn(Component.translatable("kinora.share.import_failed"), Component.literal(String.valueOf(e.getMessage())));
        }
    }

    /** Packages put into the replays folder are imported when the browser opens, then set aside. */
    private static void importDroppedPackages() {
        Path dir = dev.kinora.mc.util.KinoraPaths.replays();
        try (var files = java.nio.file.Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("." + dev.kinora.core.project.SharePackage.EXTENSION)).toList()) {
                importPackage(p);
                java.nio.file.Files.move(p, dev.kinora.mc.util.KinoraPaths.recycle().resolve(p.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException ignored) {
        }
    }

    private void reload() {
        importDroppedPackages();
        entries = ReplayLibrary.scan();
        refill();
    }

    private void refill() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        replayList.clear();
        for (ReplayLibrary.Entry e : entries) {
            String haystack = (e.name() + " " + (e.metadata() == null ? "" : String.valueOf(e.metadata().serverName) + " "
                    + e.metadata().worldName + " " + (e.metadata().player == null ? "" : e.metadata().player.name()))).toLowerCase(Locale.ROOT);
            if (query.isEmpty() || haystack.contains(query)) {
                replayList.add(new Row(e));
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean selected = replayList.getSelected() != null;
        openButton.active = selected && replayList.getSelected().entry.compatibility() != ReplayLibrary.Compatibility.UNREADABLE;
        deleteButton.active = selected;
    }

    private void openSelected() {
        Row row = replayList.getSelected();
        if (row != null) {
            Screen back = this;
            ReplayManager.INSTANCE.open(row.entry.path(), () -> new ReplayBrowserScreen(parent instanceof ReplayBrowserScreen ? null : parent));
            if (!ReplayManager.INSTANCE.active()) {
                Minecraft.getInstance().gui.setScreen(back);
            }
        }
    }

    private void deleteSelected() {
        Row row = replayList.getSelected();
        if (row == null) {
            return;
        }
        try {
            ReplayLibrary.recycle(row.entry.path());
            Notify.info(Component.translatable("kinora.browser.recycled"), Component.literal(row.entry.name()));
        } catch (IOException e) {
            Notify.warn(Component.translatable("kinora.browser.delete_failed"), Component.literal(String.valueOf(e.getMessage())));
        }
        reload();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        if (entries.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("kinora.browser.empty"), this.width / 2, this.height / 2 - 20, 0xFFB0B0B0);
            graphics.centeredText(this.font, Component.translatable("kinora.browser.empty_hint"), this.width / 2, this.height / 2 - 8, 0xFF808080);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    private final class ReplayList extends ObjectSelectionList<Row> {
        ReplayList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void clear() {
            clearEntries();
        }

        void add(Row row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return Math.min(420, this.width - 40);
        }

        @Override
        public void setSelected(@Nullable Row selected) {
            super.setSelected(selected);
            updateButtons();
        }
    }

    private final class Row extends ObjectSelectionList.Entry<Row> {
        private final ReplayLibrary.Entry entry;

        Row(ReplayLibrary.Entry entry) {
            this.entry = entry;
        }

        @Override
        public Component getNarration() {
            return Component.literal(entry.name());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = getContentX() + 4;
            int y = getContentY() + 2;
            var font = ReplayBrowserScreen.this.font;
            graphics.text(font, entry.name(), x, y, 0xFFFFFFFF, false);
            String where = "";
            if (entry.metadata() != null) {
                where = entry.metadata().singleplayer ? String.valueOf(entry.metadata().worldName)
                        : entry.metadata().serverName != null ? entry.metadata().serverName : "";
            }
            String info = DATE.format(Instant.ofEpochMilli(entry.modified().toMillis())) + "  " + duration(entry.durationTicks())
                    + "  " + size(entry.size()) + (where.isEmpty() ? "" : "  " + where);
            graphics.text(font, info, x, y + 11, 0xFFA0A0A0, false);
            if (entry.compatibility() != ReplayLibrary.Compatibility.OK) {
                int color = entry.compatibility() == ReplayLibrary.Compatibility.UNREADABLE ? 0xFFFF6060 : 0xFFFFC040;
                graphics.text(font, entry.detail(), x, y + 22, color, false);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            replayList.setSelected(this);
            if (doubleClick) {
                openSelected();
            }
            return true;
        }
    }

    static String duration(long ticks) {
        long seconds = ticks / 20;
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    static String size(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
