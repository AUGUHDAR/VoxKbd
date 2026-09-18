package com.voxkbd.mod.glfw;

import com.voxkbd.core.key.KeycodeTable;
import com.voxkbd.core.naming.VoxKbdNames;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallback;

/**
 * Delivers a synthetic extended GLFW keycode into MC's in-process GLFW (decision D16 / §5.6).
 *
 * <p>On desktop the daemon captures + translates an unlocked physical key and forwards a
 * {@code key_event}; the MOD (the only component inside MC's process) calls {@link #deliver(int, String, int, int)}
 * which synthesizes {@code VOXKBD_<kb>_<phys>} and injects it. On Android the mixin calls the same
 * method in-process. LWJGL accepts any {@code int} code, so the 400+ extended codes register here.</p>
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
     * @param action GLFW action: 0 = release, 1 = press, 2 = repeat.
     * @param mods   GLFW modifier bitmask captured at source.
     */
    public static void deliver(int kb, String phys, int action, int mods) {
        int code = table.keycodeFor(kb, phys);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return;
        long window = mc.getWindow().getWindow();
        // The capture hook runs on its own thread, but MC's key handling (KeyMapping state, screens,
        // options) is main-thread-only. Marshal the delivery onto the render thread; the single
        // executor queue preserves press/release ordering.
        try {
            mc.execute(() -> invokeKeyCallback(window, code, action, mods));
        } catch (Throwable ignored) {
            // Game shutting down / queue unavailable — drop the event rather than crash the hook.
        }
    }

    /**
     * Must run on the MC thread. The bundled LWJGL build does not expose glfwInputKey, so inject the
     * synthetic key by invoking the window's GLFW key callback directly
     * (retrieve -> invoke -> restore). scancode 0: the extended synthetic code has no physical scancode.
     */
    private static void invokeKeyCallback(long window, int code, int action, int mods) {
        GLFWKeyCallback callback = GLFW.glfwSetKeyCallback(window, null);
        if (callback != null) {
            try {
                callback.invoke(window, code, 0, action, mods);
            } finally {
                GLFW.glfwSetKeyCallback(window, callback);
            }
        }
    }

    /** Resolve the synthetic internal name (used for diagnostics / logging only). */
    public static String internalName(int kb, String phys) {
        return VoxKbdNames.internalName(kb, phys);
    }
}
