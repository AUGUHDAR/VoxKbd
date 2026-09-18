package com.voxkbd.daemon.input;

import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.core.key.PhysicalKeyRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps Windows virtual-key codes (as delivered by a low-level keyboard hook) to {@link PhysicalKey}.
 *
 * <p>Windows VK codes equal GLFW/ASCII for letters (A=65..Z=90) and digits (0=48..9=57), but differ
 * for every other key (e.g. VK_RETURN=13 vs GLFW ENTER=257). This table normalizes the OS event to
 * the mod's fixed physical-key tokens. This is legitimate OS-specific mapping data, not a hardcoded
 * game key list (the lock set itself is always built dynamically at runtime).</p>
 *
 * <p>Coverage is the full {@link PhysicalKey} keyboard section: letters, digits, the eleven US
 * punctuation keys, F1–F12, navigation/editing keys, both modifier sides, CAPSLOCK and the whole
 * numpad. Keys outside the physical-key table (PrintScreen, Pause, ScrollLock, …) resolve to null
 * and pass through untouched.</p>
 */
public final class WinVkMap {
    private WinVkMap() {}

    /** LLKHF_EXTENDED: the KBDLLHOOKSTRUCT flag distinguishing numpadENTER from main ENTER. */
    public static final int LLKHF_EXTENDED = 0x01;

    private static final Map<Integer, PhysicalKey> VK_TO_PHYS = new HashMap<>();
    static {
        // Letters / digits share codes with GLFW.
        for (PhysicalKey k : PhysicalKeyRegistry.all()) {
            if (k.category() == PhysicalKey.Category.KEYBOARD) {
                int vk = k.glfwCode();
                // For letters/digits the GLFW code equals the Windows VK; map directly.
                if ((vk >= 65 && vk <= 90) || (vk >= 48 && vk <= 57)) {
                    VK_TO_PHYS.put(vk, k);
                }
            }
        }
        // Editing / navigation / whitespace (Windows VK -> PhysicalKey).
        put(32, PhysicalKey.SPACE);
        put(13, PhysicalKey.ENTER);          // numpad ENTER is split off via LLKHF_EXTENDED
        put(9, PhysicalKey.TAB);
        put(27, PhysicalKey.ESC);
        put(8, PhysicalKey.BACKSPACE);
        put(45, PhysicalKey.INSERT);
        put(46, PhysicalKey.DELETE);
        put(36, PhysicalKey.HOME);
        put(35, PhysicalKey.END);
        put(33, PhysicalKey.PAGEUP);
        put(34, PhysicalKey.PAGEDOWN);
        put(37, PhysicalKey.LEFT);
        put(39, PhysicalKey.RIGHT);
        put(38, PhysicalKey.UP);
        put(40, PhysicalKey.DOWN);
        put(20, PhysicalKey.CAPSLOCK);
        // US punctuation row: VK_OEM_1..VK_OEM_8.
        put(186, PhysicalKey.SEMICOLON);     // VK_OEM_1  ;
        put(187, PhysicalKey.EQUAL);         // VK_OEM_PLUS  =
        put(188, PhysicalKey.COMMA);         // VK_OEM_COMMA  ,
        put(189, PhysicalKey.MINUS);         // VK_OEM_MINUS  -
        put(190, PhysicalKey.PERIOD);        // VK_OEM_PERIOD .
        put(191, PhysicalKey.SLASH);         // VK_OEM_2  /
        put(192, PhysicalKey.GRAVE);         // VK_OEM_3  `
        put(219, PhysicalKey.LBRACKET);      // VK_OEM_4  [
        put(220, PhysicalKey.BACKSLASH);     // VK_OEM_5  \
        put(221, PhysicalKey.RBRACKET);      // VK_OEM_6  ]
        put(222, PhysicalKey.APOSTROPHE);    // VK_OEM_7  '
        // F1..F12: VK_F1..VK_F12 = 0x70..0x7B.
        PhysicalKey[] fkeys = {
                PhysicalKey.F1, PhysicalKey.F2, PhysicalKey.F3, PhysicalKey.F4,
                PhysicalKey.F5, PhysicalKey.F6, PhysicalKey.F7, PhysicalKey.F8,
                PhysicalKey.F9, PhysicalKey.F10, PhysicalKey.F11, PhysicalKey.F12 };
        for (int i = 0; i < fkeys.length; i++) put(0x70 + i, fkeys[i]);
        // Numpad (NumLock on): VK_NUMPAD0..9 = 0x60..0x69, VK_MULTIPLY..VK_DECIMAL.
        PhysicalKey[] kpdigits = {
                PhysicalKey.KP_0, PhysicalKey.KP_1, PhysicalKey.KP_2, PhysicalKey.KP_3,
                PhysicalKey.KP_4, PhysicalKey.KP_5, PhysicalKey.KP_6, PhysicalKey.KP_7,
                PhysicalKey.KP_8, PhysicalKey.KP_9 };
        for (int i = 0; i < kpdigits.length; i++) put(0x60 + i, kpdigits[i]);
        put(0x6A, PhysicalKey.KP_MULTIPLY);
        put(0x6B, PhysicalKey.KP_ADD);
        put(0x6D, PhysicalKey.KP_SUBTRACT);
        put(0x6E, PhysicalKey.KP_DECIMAL);
        put(0x6F, PhysicalKey.KP_DIVIDE);
        put(0x92, PhysicalKey.KP_EQUAL);     // VK_OEM_NEC_EQUAL (numpad = on some layouts)
        // Modifiers, both sides. The generic VK_SHIFT/CONTROL/MENU (16/17/18) appear only from
        // injected events; the LL hook reports the per-side 160..165 codes for physical presses.
        put(16, PhysicalKey.LSHIFT);
        put(160, PhysicalKey.LSHIFT);
        put(161, PhysicalKey.RSHIFT);
        put(17, PhysicalKey.LCTRL);
        put(162, PhysicalKey.LCTRL);
        put(163, PhysicalKey.RCTRL);
        put(18, PhysicalKey.LALT);
        put(164, PhysicalKey.LALT);
        put(165, PhysicalKey.RALT);
        put(91, PhysicalKey.LWIN);
        put(92, PhysicalKey.RWIN);
    }

    private static void put(int vk, PhysicalKey k) { VK_TO_PHYS.put(vk, k); }

    /** Resolve a Windows VK code to a PhysicalKey, or null if not handled. */
    public static PhysicalKey physicalKeyOfWinVk(int vk) {
        return VK_TO_PHYS.get(vk);
    }

    /**
     * Resolve a Windows VK code, splitting VK_RETURN on the low-level hook's extended-key flag:
     * Enter on the numpad reports {@code vk=13 + LLKHF_EXTENDED} and must become {@code KP_ENTER},
     * not the main {@code ENTER}.
     */
    public static PhysicalKey physicalKeyOfWinVk(int vk, boolean extended) {
        if (extended && vk == 13) return PhysicalKey.KP_ENTER;
        return VK_TO_PHYS.get(vk);
    }

    public static boolean has(int vk) { return VK_TO_PHYS.containsKey(vk); }
}
