package com.voxkbd.core.config;

import java.util.EnumSet;
import java.util.Set;

/**
 * Parses/serializes the "combo + digit" prefix mask (decision D8: any combination of
 * Ctrl / Alt / Shift / Win is allowed). The prefix is stored in config as a {@code "_"}-joined
 * uppercase name string such as {@code "CTRL_ALT"}; only the trailing digit(s) are fixed
 * (decision D9) and are NOT part of this mask.
 */
public final class ComboPrefixMask {
    private ComboPrefixMask() {}

    public enum Mod { CTRL, ALT, SHIFT, WIN }

    private static final int CTRL = 1 << 0;
    private static final int ALT = 1 << 1;
    private static final int SHIFT = 1 << 2;
    private static final int WIN = 1 << 3;

    public static int of(Set<Mod> mods) {
        int m = 0;
        for (Mod mod : mods) m |= switch (mod) {
            case CTRL -> CTRL;
            case ALT -> ALT;
            case SHIFT -> SHIFT;
            case WIN -> WIN;
        };
        return m;
    }

    /** True when {@code mask} contains the {@link Mod#CTRL} bit (left or right). */
    public static boolean hasCtrl(int mask) { return (mask & CTRL) != 0; }
    /** True when {@code mask} contains the {@link Mod#ALT} bit (left or right). */
    public static boolean hasAlt(int mask) { return (mask & ALT) != 0; }
    /** True when {@code mask} contains the {@link Mod#SHIFT} bit (left or right). */
    public static boolean hasShift(int mask) { return (mask & SHIFT) != 0; }
    /** True when {@code mask} contains the {@link Mod#WIN} bit (left or right). */
    public static boolean hasWin(int mask) { return (mask & WIN) != 0; }

    public static Set<Mod> toMods(int mask) {
        Set<Mod> set = EnumSet.noneOf(Mod.class);
        if ((mask & CTRL) != 0) set.add(Mod.CTRL);
        if ((mask & ALT) != 0) set.add(Mod.ALT);
        if ((mask & SHIFT) != 0) set.add(Mod.SHIFT);
        if ((mask & WIN) != 0) set.add(Mod.WIN);
        return set;
    }

    /** Parse a config string like {@code "CTRL_ALT"} (case-insensitive, "_"/"+" separators). */
    public static int parse(String s) {
        if (s == null || s.isBlank()) return 0;
        int mask = 0;
        for (String part : s.split("[_+]")) {
            mask |= switch (part.trim().toUpperCase()) {
                case "CTRL", "CONTROL", "C" -> CTRL;
                case "ALT", "A" -> ALT;
                case "SHIFT", "S" -> SHIFT;
                case "WIN", "WINDOWS", "META", "SUPER", "M" -> WIN;
                default -> 0;
            };
        }
        return mask;
    }

    /** Serialize a mask back to a config string (empty string when none). */
    public static String format(int mask) {
        if (mask == 0) return "";
        StringBuilder sb = new StringBuilder();
        if ((mask & CTRL) != 0) sb.append("CTRL_");
        if ((mask & ALT) != 0) sb.append("ALT_");
        if ((mask & SHIFT) != 0) sb.append("SHIFT_");
        if ((mask & WIN) != 0) sb.append("WIN_");
        String s = sb.toString();
        return s.endsWith("_") ? s.substring(0, s.length() - 1) : s;
    }

    // ---- GLFW interop (stable ABI bits, shared by the Windows hook and the Android mixin) ----

    private static final int GLFW_MOD_SHIFT = 0x0001;
    private static final int GLFW_MOD_CONTROL = 0x0002;
    private static final int GLFW_MOD_ALT = 0x0004;
    private static final int GLFW_MOD_SUPER = 0x0008;

    /**
     * Convert live GLFW modifier bits (as reported by a capture backend) into this mask's bit
     * ordering. Shared by every backend so "prefix held" is decided identically everywhere.
     */
    public static int fromGlfwMods(int glfwMods) {
        int m = 0;
        if ((glfwMods & GLFW_MOD_CONTROL) != 0) m |= CTRL;
        if ((glfwMods & GLFW_MOD_ALT) != 0) m |= ALT;
        if ((glfwMods & GLFW_MOD_SHIFT) != 0) m |= SHIFT;
        if ((glfwMods & GLFW_MOD_SUPER) != 0) m |= WIN;
        return m;
    }

    /** True when {@code physToken} is one of the digit keys {@code D0}..{@code D9} (D9). */
    public static boolean isDigitToken(String physToken) {
        return physToken != null && physToken.length() == 2
                && physToken.charAt(0) == 'D' && physToken.charAt(1) >= '0' && physToken.charAt(1) <= '9';
    }
}
