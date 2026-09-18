package com.voxkbd.core.key;

import com.voxkbd.core.Constants;

/**
 * Allocates GLFW *extended* keycodes for synthetic {@code VOXKBD_<kb>_<phys>} keys
 * (decision D16 / §5.3).
 *
 * <p>Formula: {@code keycode = BASE + kb * STRIDE + idx(physicalKey)}.</p>
 *
 * <p>The resulting code is the canonical, cross-platform GLFW code that the mod registers its
 * {@code KeyBinding} against and that the Android backend delivers directly into GLFW (the mixin
 * calls {@code glfwInputKey(window, code, scancode, action, mods)} — LWJGL accepts any int).</p>
 *
 * <p>Note on desktop: a separate-process daemon cannot emit a 400+ code into MC's GLFW via OS
 * injection (Robot/keyboard hooks are limited to the real keycode range). The desktop backend
 * therefore delivers the synthetic key in-process (see voxkbd-mod / voxkbd-daemon design notes);
 * the extended code computed here remains the stable logical identity so the bound action and the
 * VOXKBD *name* are identical across platforms and packs (decision D15/D16 intent).</p>
 */
public final class KeycodeTable {
    private final int base;
    private final int stride;

    public KeycodeTable(int base, int stride) {
        if (stride < PhysicalKeyRegistry.size()) {
            throw new IllegalArgumentException(
                    "STRIDE (" + stride + ") must be >= physical-key table size (" + PhysicalKeyRegistry.size() + ")");
        }
        this.base = base;
        this.stride = stride;
    }

    public static KeycodeTable defaults() {
        return new KeycodeTable(Constants.DEFAULT_KEYCODE_BASE, Constants.DEFAULT_KEYCODE_STRIDE);
    }

    public int base() { return base; }
    public int stride() { return stride; }

    /** Extended GLFW keycode for a given virtual keyboard index and physical key. */
    public int keycodeFor(int keyboard, PhysicalKey key) {
        return base + keyboard * stride + PhysicalKeyRegistry.idx(key);
    }

    /** Extended GLFW keycode for a given virtual keyboard index and physical-key token. */
    public int keycodeFor(int keyboard, String physToken) {
        PhysicalKey key = PhysicalKeyRegistry.byToken(physToken);
        if (key == null) throw new IllegalArgumentException("Unknown physical key token: " + physToken);
        return keycodeFor(keyboard, key);
    }

    /**
     * Reverse lookup. Returns null when the code is outside the allocated range (e.g. a real
     * physical keycode, which is never synthetic).
     */
    public Decoded decode(int glfwKeycode) {
        if (glfwKeycode < base) return null;
        int kb = (glfwKeycode - base) / stride;
        int idx = (glfwKeycode - base) % stride;
        PhysicalKey[] values = PhysicalKey.values();
        if (idx < 0 || idx >= values.length) return null;
        return new Decoded(kb, values[idx]);
    }

    /** Decoded synthetic key: virtual keyboard index + physical key. */
    public record Decoded(int keyboard, PhysicalKey key) {}
}
