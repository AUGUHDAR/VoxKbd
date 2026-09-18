package com.voxkbd.mod.glfw;

import com.voxkbd.core.input.InputState;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.glfw.GLFWWindowFocusCallbackI;

/**
 * Registers a GLFW window-focus callback (decision D7 / §3.2 / §4.6). Wraps any pre-existing callback
 * so other mods keep working. On focus change it updates {@link InputState#setFocus(boolean)} and pushes
 * the new {@code state} to the daemon (or mixin) so capture is suspended while MC is not foreground.
 */
public final class FocusListener {

    private FocusListener() {}

    public static void register(InputState inputState, Runnable statePusher) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return;
        long window = mc.getWindow().handle();

        GLFWWindowFocusCallbackI previous = GLFW.glfwSetWindowFocusCallback(window, null);
        GLFWWindowFocusCallbackI wrapper = (w, focused) -> {
            if (previous != null) previous.invoke(w, focused);
            inputState.setFocus(focused);
            if (statePusher != null) statePusher.run();
        };
        GLFW.glfwSetWindowFocusCallback(window, wrapper);
        // initialise from the current focus state
        inputState.setFocus(GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE);
        if (statePusher != null) statePusher.run();
    }
}
