package com.voxkbd.core.naming;

import com.voxkbd.core.Constants;
import com.voxkbd.core.key.PhysicalKey;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and parses the fixed internal synthetic-key name {@code VOXKBD_<kb>_<phys>}
 * (decision D2/D15/D16, §8 separator = {@code _}).
 *
 * <p>The internal name is immutable and identical across all four platforms and every modpack.
 * Only the human-facing *display name* (a virtual keyboard's label) may be overridden by a
 * resource pack (decision D15).</p>
 */
public final class VoxKbdNames {
    private VoxKbdNames() {}

    private static final Pattern NAME_PATTERN =
            Pattern.compile("^" + Constants.PREFIX + "_(\\d+)_([A-Za-z0-9_]+)$");

    /** Build internal name, e.g. {@code VOXKBD_1_W}. */
    public static String internalName(int keyboard, String physToken) {
        if (physToken == null || physToken.isEmpty()) throw new IllegalArgumentException("physToken empty");
        return Constants.PREFIX + Constants.SEP + keyboard + Constants.SEP + physToken;
    }

    public static String internalName(int keyboard, PhysicalKey key) {
        return internalName(keyboard, key.token());
    }

    /** True if a string is a VOXKBD synthetic internal name. */
    public static boolean isVoxKbd(String name) {
        return name != null && name.startsWith(Constants.PREFIX + Constants.SEP);
    }

    /**
     * Parse an internal name. Returns null if it does not match the fixed pattern.
     *
     * @param name internal name such as {@code VOXKBD_2_A}
     */
    public static Parsed parse(String name) {
        if (name == null) return null;
        Matcher m = NAME_PATTERN.matcher(name);
        if (!m.matches()) return null;
        return new Parsed(Integer.parseInt(m.group(1)), m.group(2));
    }

    /** i18n key for a synthetic key's on-screen label (default {@code VOXKBD <kb> <phys>}). */
    public static String keyTranslationKey(int keyboard, String physToken) {
        return "key.voxkbd." + keyboard + "." + physToken;
    }

    /** i18n / resource-pack key for a virtual keyboard's display name (decision D15). */
    public static String keyboardNameKey(int id) {
        return Constants.MOD_ID + ".kb." + id;
    }

    /** Parsed internal name. */
    public record Parsed(int keyboard, String physToken) {}
}
