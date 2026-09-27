package com.voxkbd.mod.glfw;

import com.voxkbd.core.input.InputState;
import net.minecraft.client.Minecraft;

/**
 * Window-focus tracking (MC 26.3 / SDL era). SDL exposes no focus callback through MC's window
 * wrapper, so focus is polled from the client tick; the state is pushed only when it changes.
 */
public final class FocusListener {

    private static InputState state;
    private static Runnable pusher;
    private static boolean lastFocus = true;

    private FocusListener() {}

    public static void register(InputState inputState, Runnable statePusher) {
        state = inputState;
        pusher = statePusher;
        Minecraft mc = Minecraft.getInstance();
        lastFocus = mc == null || mc.getWindow() == null || mc.getWindow().isFocused();
        inputState.setFocus(lastFocus);
        if (statePusher != null) statePusher.run();
    }

    /** Called from the client tick; cheap (a single boolean read on the window). */
    public static void poll() {
        if (state == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return;
        boolean focused = mc.getWindow().isFocused();
        if (focused != lastFocus) {
            lastFocus = focused;
            state.setFocus(focused);
            if (pusher != null) pusher.run();
        }
    }
}
