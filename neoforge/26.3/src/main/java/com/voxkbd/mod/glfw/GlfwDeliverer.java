package com.voxkbd.mod.glfw;

import com.voxkbd.core.key.KeycodeTable;
import com.voxkbd.core.naming.VoxKbdNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

/**
 * Delivers a synthetic extended keycode into MC's in-process input (decision D16 / §5.6).
 *
 * <p>MC 26.3 runs on SDL3, so there is no GLFW key callback to invoke any more. The equivalent
 * entry point is {@code KeyboardHandler#keyPress(window, action, KeyEvent)}, which is exactly what
 * the SDL event pump calls; injecting there keeps the synthetic codes on the same path as real
 * ones. The synthetic code has no distinct logical/scancode pair, so both KeyEvent slots carry it.</p>
 *
 * <p>On desktop the daemon captures + translates an unlocked physical key and forwards a
 * {@code key_event}; the MOD (the only component inside MC's process) calls
 * {@link #deliver(int, String, int, int)} which synthesizes {@code VOXKBD_<kb>_<phys>} and injects it.</p>
 */
public final class GlfwDeliverer {

    private static volatile KeycodeTable table = KeycodeTable.defaults();

    private GlfwDeliverer() {}

    /** Set the keycode table matching the loaded config (call once during init). */
    public static void setTable(KeycodeTable table) {
        if (table != null) GlfwDeliverer.table = table;
    }

    public static KeycodeTable table() {
        return table;
    }

    /**
     * Inject a synthetic key event for virtual keyboard {@code kb}, physical token {@code phys}.
     *
     * @param action 0 = release, 1 = press, 2 = repeat.
     * @param mods   modifier bitmask captured at source.
     */
    public static void deliver(int kb, String phys, int action, int mods) {
        int code = table.keycodeFor(kb, phys);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || mc.keyboardHandler == null) return;
        long window = mc.getWindow().handle();
        // The capture hook runs on its own thread, but MC's key handling (KeyMapping state, screens,
        // options) is main-thread-only. Marshal the delivery onto the render thread; the single
        // executor queue preserves press/release ordering.
        try {
            mc.execute(() -> mc.keyboardHandler.keyPress(window, action, new KeyEvent(code, code, mods)));
        } catch (Throwable ignored) {
            // Game shutting down / queue unavailable — drop the event rather than crash the hook.
        }
    }

    /** Resolve the synthetic internal name (used for diagnostics / logging only). */
    public static String internalName(int kb, String phys) {
        return VoxKbdNames.internalName(kb, phys);
    }
}
