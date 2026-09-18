package com.voxkbd.fclbridge;

import java.lang.reflect.Method;

/**
 * Reaches the FCL / ZL2 native key-injection sink purely by reflection (decision D6: class-loader
 * isolation — the mod runs in MC's class loader, the launcher's {@code CallbackBridge} lives in the
 * app/launcher class loader, so we must never import those classes directly).
 *
 * <p>Two candidate sinks are probed, in order:
 * <ol>
 *   <li>{@code org.lwjgl.glfw.CallbackBridge#sendKeycode(int keycode, char keychar, int scancode,
 *       int modifiers, boolean isDown)} — the public, documented injection sink used by both FCL
 *       (1.3.2.6) and ZL2 (2.4.11). It internally drives the native {@code GLFW_invoke_Key} JNI path.</li>
 *   <li>The private native {@code CallbackBridge#nativeSendKey(int key, int scancode, int action,
 *       int mods)} — used only as a fallback if the public method is absent in a given launcher build.</li>
 * </ol>
 * A class/method literally named {@code GLFW_invoke_Key} does not exist as a Java method (it is the
 * C-side JNI function); {@code sendKeycode} is its Java-reachable front door, so we resolve that.</p>
 *
 * <p>If the launcher bridge class is absent (e.g. plain Fabric desktop), {@link #tryCreate()} returns
 * {@code null} and {@link #inject} degrades to a no-op after logging exactly once. Never throws.</p>
 */
public final class FclInputInjector implements PlatformBridge {

    /** Candidate host classes carrying the injection sink (FCL and ZL2 share the package). */
    private static final String[] HOST_CLASSES = {
            "org.lwjgl.glfw.CallbackBridge",
            "com.tungsten.fclauncher.bridge.FCLBridge"
    };

    private final Method sink;
    /** True when the resolved sink takes the (key, char, scancode, mods, isDown) shape. */
    private final boolean isSendKeycodeShape;

    private static volatile boolean warned = false;

    private FclInputInjector(Method sink, boolean isSendKeycodeShape) {
        this.sink = sink;
        this.isSendKeycodeShape = isSendKeycodeShape;
    }

    /**
     * Resolve the reflection sink. Returns an injector, or {@code null} when no launcher bridge is
     * present on the classpath (desktop fallback). Safe to call repeatedly.
     */
    public static PlatformBridge tryCreate() {
        for (String className : HOST_CLASSES) {
            try {
                Class<?> host = Class.forName(className);
                // Preferred: public sendKeycode(int, char, int, int, boolean).
                Method sendKeycode = host.getMethod("sendKeycode",
                        int.class, char.class, int.class, int.class, boolean.class);
                sendKeycode.setAccessible(true);
                return new FclInputInjector(sendKeycode, true);
            } catch (ClassNotFoundException ignored) {
                // Launcher bridge class not on this classpath; try next candidate.
            } catch (NoSuchMethodException ignored) {
                // Class present but shape differs; fall back to nativeSendKey below.
                PlatformBridge via = tryNativeSendKey(className);
                if (via != null) return via;
            }
        }
        if (!warned) {
            warned = true;
            System.err.println("[voxkbd-fclbridge] FCL/ZL2 input bridge not found on classpath; "
                    + "FclInputInjector is a no-op (desktop / non-Android).");
        }
        return null;
    }

    private static PlatformBridge tryNativeSendKey(String className) {
        try {
            Class<?> host = Class.forName(className);
            Method nativeSend = host.getDeclaredMethod("nativeSendKey",
                    int.class, int.class, int.class, int.class);
            nativeSend.setAccessible(true);
            return new FclInputInjector(nativeSend, false);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            return null;
        }
    }

    @Override
    public void inject(int window, int key, int scancode, int action, int mods) {
        if (sink == null) return; // desktop no-op
        try {
            if (isSendKeycodeShape) {
                // sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown)
                sink.invoke(null, key, (char) 0, scancode, mods, action != 0);
            } else {
                // nativeSendKey(int key, int scancode, int action, int mods)
                sink.invoke(null, key, scancode, action, mods);
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.err.println("[voxkbd-fclbridge] FCL/ZL2 input injection failed; "
                        + "disabling bridge. " + t);
            }
        }
    }
}
