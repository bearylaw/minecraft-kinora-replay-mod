package dev.kinora.mc.ui;

import dev.kinora.mc.ui.kit.Theme;
import dev.kinora.mc.ui.kit.Ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ctrl+K in the editor: type a few letters of any action and press Enter. Matches are ranked by how
 * early and how contiguously the typed letters appear in the action's name.
 */
final class CommandPalette {
    /** An action: its name (searched and shown), a hint (shown dimmed, e.g. its shortcut) and what it does. */
    record Action(String name, String hint, Runnable run) {}

    private static final int WIDTH = 300;
    private static final int ROWS = 10;

    private final List<Action> actions;
    private String query = "";
    private int selected;
    private List<Action> matches;

    CommandPalette(List<Action> actions) {
        this.actions = actions;
        this.matches = actions;
    }

    String query() {
        return query;
    }

    /** Handles a key; returns the action to run on Enter, or null. */
    Action key(int key) {
        switch (key) {
            case GLFW.GLFW_KEY_DOWN -> selected = Math.min(matches.size() - 1, selected + 1);
            case GLFW.GLFW_KEY_UP -> selected = Math.max(0, selected - 1);
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!query.isEmpty()) {
                    setQuery(query.substring(0, query.length() - 1));
                }
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                return matches.isEmpty() ? null : matches.get(Math.max(0, Math.min(selected, matches.size() - 1)));
            }
            default -> {
            }
        }
        return null;
    }

    void type(char c) {
        if (c >= ' ') {
            setQuery(query + c);
        }
    }

    private void setQuery(String q) {
        query = q;
        selected = 0;
        if (q.isBlank()) {
            matches = actions;
            return;
        }
        String needle = q.toLowerCase(Locale.ROOT).strip();
        List<Action> scored = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Action a : actions) {
            int score = score(a.name().toLowerCase(Locale.ROOT), needle);
            if (score >= 0) {
                int i = 0;
                while (i < scores.size() && scores.get(i) <= score) {
                    i++;
                }
                scored.add(i, a);
                scores.add(i, score);
            }
        }
        matches = scored;
    }

    /** Lower is better; -1 if the letters do not all appear in order. */
    static int score(String name, String needle) {
        int at = name.indexOf(needle);
        if (at >= 0) {
            // Whole substring: best when at the start of a word.
            boolean wordStart = at == 0 || name.charAt(at - 1) == ' ' || name.charAt(at - 1) == ':';
            return (wordStart ? 0 : 100) + at;
        }
        int pos = -1;
        int gaps = 0;
        for (char c : needle.toCharArray()) {
            if (c == ' ') {
                continue;
            }
            int next = name.indexOf(c, pos + 1);
            if (next < 0) {
                return -1;
            }
            gaps += next - pos - 1;
            pos = next;
        }
        return 1000 + gaps;
    }

    void render(GuiGraphicsExtractor g, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        int x0 = (screenWidth - WIDTH) / 2;
        int y0 = Math.max(24, screenHeight / 5);
        int shown = Math.min(ROWS, matches.size());
        int height = 22 + Math.max(1, shown) * 12 + 4;
        g.fill(0, 0, screenWidth, screenHeight, 0x60000000);
        Ui.panel(g, x0, y0, x0 + WIDTH, y0 + height);
        String text = query.isEmpty() ? Component.translatable("kinora.palette.prompt").getString() : query + "_";
        g.text(font, text, x0 + 6, y0 + 6, query.isEmpty() ? Theme.textDim() : Theme.text(), false);
        g.fill(x0 + 4, y0 + 17, x0 + WIDTH - 4, y0 + 18, Theme.border());
        if (matches.isEmpty()) {
            g.text(font, Component.translatable("kinora.palette.none"), x0 + 6, y0 + 22, Theme.textDim(), false);
            return;
        }
        int first = Math.max(0, Math.min(selected - ROWS + 1, matches.size() - ROWS));
        first = Math.max(0, Math.min(first, selected));
        for (int i = 0; i < shown; i++) {
            int index = first + i;
            if (index >= matches.size()) {
                break;
            }
            Action a = matches.get(index);
            int y = y0 + 22 + i * 12;
            if (index == selected) {
                g.fill(x0 + 2, y - 1, x0 + WIDTH - 2, y + 11, Theme.selection());
            }
            g.text(font, Ui.fit(font, a.name(), WIDTH - 90), x0 + 6, y + 1, Theme.text(), false);
            if (!a.hint().isEmpty()) {
                g.text(font, a.hint(), x0 + WIDTH - 6 - font.width(a.hint()), y + 1, Theme.textDim(), false);
            }
        }
    }
}
