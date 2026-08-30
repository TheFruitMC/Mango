package org.fruitmc.mango.client.gui.style;

import net.minecraft.util.ARGB;

public final class MangoTheme {

    public static final int BG = 0xFF1C1C1E;
    public static final int SIDEBAR = 0xFF202022;
    public static final int CARD = 0xFF2A2A2D;
    public static final int CARD_HOVER = 0xFF323236;
    public static final int CARD_SELECTED = 0xFF38383C;
    public static final int CARD_DISABLED = 0xFF202022;
    public static final int TEXT = 0xFFE8E8EC;
    public static final int TEXT_SECONDARY = 0xFF8E8E93;
    public static final int SIDEBAR_TEXT = 0xFFE8E8EC;
    public static final int SIDEBAR_TEXT_SECONDARY = 0xFF8E8E93;
    public static final int ACCENT = 0xFFFFD60A;
    public static final int ACCENT_HOVER = 0xFFFFE34D;
    public static final int SEPARATOR = 0xFF38383C;
    public static final int TRACK = 0xFF3A3A3E;
    public static final int TRACK_DISABLED = 0xFF2A2A2D;
    public static final int THUMB = 0xFFE8E8EC;
    public static final int SWITCH_OFF = 0xFF48484A;

    private MangoTheme() {
    }

    public static int withAlpha(int rgb, int alpha) {
        return ARGB.color(alpha, ARGB.red(rgb), ARGB.green(rgb), ARGB.blue(rgb));
    }
}
