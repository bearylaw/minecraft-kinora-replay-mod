package dev.kinora.mc.ui.kit;

import dev.kinora.mc.KinoraConfig;

/**
 * Kinora's UI colours, in one place so the high-contrast theme (and any future theme) is a switch
 * here. All 0xAARRGGBB.
 */
public final class Theme {
    private Theme() {}

    private static boolean hc() {
        return KinoraConfig.highContrast();
    }

    public static int panel() {
        return hc() ? 0xF0000000 : 0xD8141418;
    }

    public static int panelLight() {
        return hc() ? 0xF0202020 : 0xD8202028;
    }

    public static int border() {
        return hc() ? 0xFFFFFFFF : 0xFF3A3A46;
    }

    public static int text() {
        return 0xFFFFFFFF;
    }

    public static int textDim() {
        return hc() ? 0xFFE0E0E0 : 0xFFA0A0AC;
    }

    public static int accent() {
        return hc() ? 0xFFFFD000 : 0xFFE0A030;
    }

    public static int selection() {
        return hc() ? 0xFF00FFFF : 0xFF56B4E9;
    }

    public static int hover() {
        return hc() ? 0x60FFFFFF : 0x30FFFFFF;
    }

    public static int danger() {
        return 0xFFE05050;
    }

    public static int playhead() {
        return hc() ? 0xFFFF4040 : 0xFFE0A030;
    }
}
