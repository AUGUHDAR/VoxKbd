package com.voxkbd.fclbridge;

/**
 * Abstraction over a native GLFW key-injection sink, so the Fabric mixin can deliver a translated
 * synthetic key WITHOUT hard-depending on any FCL/ZL2 class (class-loader isolation, decision D6).
 *
 * <p>The mixin's primary sink is the wrapped {@code glfwSetKeyCallback} chain itself. This bridge is
 * the OPTIONAL alternate sink: on Android (FCL/ZL2) the launcher feeds touch / gamepad input into
 * GLFW through its own native {@code GLFW_invoke_Key} / {@code CallbackBridge.sendKeycode} path, and
 * {@link FclInputInjector} reaches it purely by reflection. The mixin simply calls
 * {@link #inject(int, int, int, int, int)} and remains ignorant of which concrete launcher is present.</p>
 *
 * <p>Signature mirrors the GLFW key callback: (window, key, scancode, action, mods). The {@code window}
 * argument may be ignored by a particular launcher implementation that only drives a single window.</p>
 */
public interface PlatformBridge {

    /**
     * Inject / deliver a key event into the platform GLFW input layer.
     *
     * @param window   GLFW window handle (ignored by single-window launchers)
     * @param key      GLFW keycode (physical, or a synthetic VOXKBD extended code)
     * @param scancode hardware scancode (0 when synthesized)
     * @param action   GLFW action: 1 = press, 0 = release, 2 = repeat
     * @param mods     modifier bitmask
     */
    void inject(int window, int key, int scancode, int action, int mods);
}
