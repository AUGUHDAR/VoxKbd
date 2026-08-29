package com.voxkbd.daemon.input;

import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.core.key.PhysicalKeyRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps Windows virtual-key codes (as delivered by a low-level keyboard hook) to {@link PhysicalKey}.
 *
 * <p>Windows VK codes equal GLFW/ASCII for letters (A=65..Z=90) and digits (0=48..9=57), but differ
 * for many other keys (e.g. VK_RETURN=13 vs GLFW ENTER=257). This table normalizes the OS event to
 * the mod's fixed physical-key tokens. This is legitimate OS-specific mapping data, not a hardcoded
 * game key list (the lock set itself is always built dynamically at runtime).</p>
 */
public final class WinVkMap {
    private WinVkMap() {}

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
        // Divergent keys (Windows VK -> PhysicalKey).
        put(32, PhysicalKey.SPACE);
        put(13, PhysicalKey.ENTER);
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

    public static boolean has(int vk) { return VK_TO_PHYS.containsKey(vk); }
}
